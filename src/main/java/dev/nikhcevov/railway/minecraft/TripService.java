package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.diagnostics.DiagnosticsReport;
import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.dispatch.ReservationManager;
import dev.nikhcevov.railway.dispatch.RouteResourcePlanner;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.Trip;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;
import dev.nikhcevov.railway.motion.MotionProfile;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.LaunchCheck;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.network.RailGraphPlanner;
import dev.nikhcevov.railway.network.RailShapeType;
import dev.nikhcevov.railway.network.RouteStep;
import dev.nikhcevov.railway.network.SwitchRequirement;
import dev.nikhcevov.railway.persistence.Config;
import dev.nikhcevov.railway.persistence.TripRecord;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Launch, graph route following, turnout control, arrival, abort and restart
 * recovery. All state is touched on the server thread.
 */
public final class TripService {
	private static final double STOP_DISTANCE = 0.25;
	private static final double STOP_SPEED = 1.0;
	private static final double FINAL_SPEED = 0.1;
	private static final double TURN_SPEED_LIMIT = 4.0;
	private static final long ORPHAN_SWEEP_INTERVAL = 100;
	private static final long WARNING_INTERVAL = 100;
	/** Ticks the cart may wait for the ticket window to become entity-ticking. */
	private static final int CHUNK_READY_WAIT_TICKS = 100;
	/** Checkpoint cadence: a crash may rewind the recorded position by one second. */
	private static final long CHECKPOINT_INTERVAL_TICKS = 20;

	private final Railway railway;
	private final Logger logger;
	private final ChunkTicketManager tickets;
	private final Map<TripId, ActiveTrip> active = new LinkedHashMap<>();
	private final Map<UUID, TripId> byPlayer = new HashMap<>();
	private final Map<UUID, TripId> byCart = new HashMap<>();
	private final Map<UUID, Long> cooldowns = new HashMap<>();
	private final ReservationManager reservations = new ReservationManager();

	private static final class ActiveTrip {
		final Trip trip;
		final Minecart cart;
		final RailWorld world;
		final GraphRoutePlan plan;
		final MotionProfile profile;
		final Map<BlockLocation, RailShapeType> requiredSwitches;
		final Set<BlockLocation> preparedSwitches = new LinkedHashSet<>();
		final List<BlockLocation> route;
		final Set<ChunkWindow.Key> routeChunks;
		final double safetyMargin;
		final long graphRevision;
		final int maxTripTicks;
		final int maxChunkTickets;
		final long departureTick;
		double speed;
		int stepIndex;
		long lastWarningTick;
		long lastSafeRetryTick;
		long lastProgressTick;
		long chunkWaitSinceTick = -1;
		long arrivalWaitSinceTick = -1;
		List<ChunkWindow.Key> ticketWindow = List.of();
		int ticketWindowStep = -1;
		double lastX;
		double lastZ;
		long lastCheckpointTick;
		TripPhase lastCheckpointPhase;

		ActiveTrip(Trip trip, Minecart cart, RailWorld world, GraphRoutePlan plan, MotionProfile profile,
				double safetyMargin, long graphRevision, int maxTripTicks, long departureTick,
				int maxChunkTickets) {
			this.trip = trip;
			this.cart = cart;
			this.world = world;
			this.plan = plan;
			this.profile = profile;
			this.safetyMargin = safetyMargin;
			this.graphRevision = graphRevision;
			this.maxTripTicks = maxTripTicks;
			this.departureTick = departureTick;
			this.maxChunkTickets = maxChunkTickets;
			this.route = new ArrayList<>(plan.steps().size());
			Set<ChunkWindow.Key> chunks = new LinkedHashSet<>();
			for (RouteStep step : plan.steps()) {
				route.add(step.pos());
				chunks.add(ChunkWindow.Key.of(step.pos()));
			}
			this.routeChunks = chunks;
			this.requiredSwitches = new HashMap<>();
			for (var requirement : plan.switches()) {
				requiredSwitches.put(requirement.pos(), requirement.requiredShape());
			}
			this.lastProgressTick = trip.startedTick();
			this.lastX = cart.getX();
			this.lastZ = cart.getZ();
		}
	}

	public sealed interface StartResult {
		record Started(TripId id) implements StartResult {
		}

		record Rejected(Failure failure) implements StartResult {
		}
	}

	public sealed interface AbortResult {
		record Aborted(TripId id) implements AbortResult {
		}

		record Holding(TripId id) implements AbortResult {
		}

		record NotFound() implements AbortResult {
		}
	}

	public TripService(Railway railway, Logger logger, ChunkTicketManager tickets) {
		this.railway = railway;
		this.logger = logger;
		this.tickets = tickets;
	}

	// ------------------------------------------------------------------ launch

	public StartResult start(ServerPlayer player, Binding binding) {
		MinecraftServer server = railway.server();
		long tick = server.getTickCount();
		Config config = railway.config();

		if (railway.disabled()) {
			return reject(FailureCode.DISABLED, railway.disabledReason());
		}
		// Interrupted checkpoints must be reconciled before a new cart is spawned,
		// otherwise recovery could mistake the new cart for a crashed one.
		if (railway.recovery().blocksNewTrips()) {
			return reject(FailureCode.RECOVERY_PENDING,
					Integer.toString(railway.recovery().pendingCount()));
		}
		if (byPlayer.containsKey(player.getUUID())) {
			return reject(FailureCode.PLAYER_BUSY, null);
		}
		if (player.isSpectator()) {
			return reject(FailureCode.PLAYER_SPECTATOR, null);
		}
		if (player.isPassenger()) {
			return reject(FailureCode.PLAYER_IN_VEHICLE, null);
		}
		if (!player.isAlive()) {
			return reject(FailureCode.INTERNAL, "player is not alive");
		}
		if (active.size() >= config.maxActiveTrips()) {
			return reject(FailureCode.TRIP_LIMIT, Integer.toString(config.maxActiveTrips()));
		}

		Optional<Station> fromOpt = railway.state().station(binding.fromStationId());
		Optional<Station> toOpt = railway.state().station(binding.toStationId());
		if (fromOpt.isEmpty()) {
			return reject(FailureCode.STATION_NOT_FOUND, binding.fromStationId().value());
		}
		if (toOpt.isEmpty()) {
			return reject(FailureCode.STATION_NOT_FOUND, binding.toStationId().value());
		}
		Station from = fromOpt.get();
		Station to = toOpt.get();
		if (from.isProvisional()) {
			return reject(FailureCode.STATION_INCOMPLETE, from.displayName());
		}
		if (to.isProvisional()) {
			return reject(FailureCode.DESTINATION_NOT_ANCHORED, to.displayName());
		}
		if (!from.enabled()) {
			return reject(FailureCode.STATION_DISABLED, from.id().value());
		}
		if (!to.enabled()) {
			return reject(FailureCode.STATION_DISABLED, to.id().value());
		}
		if (!to.hasExit()) {
			return reject(FailureCode.STATION_INCOMPLETE, to.displayName());
		}

		ServerLevel level = RailWorld.level(server, from.dimension());
		if (level == null) {
			return reject(FailureCode.WORLD_NOT_LOADED, from.dimension());
		}
		if (player.level() != level) {
			return reject(FailureCode.PLAYER_TOO_FAR, from.id().value());
		}
		RailWorld world = new RailWorld(level);
		if (!binding.expectedButtonBlockId().equals(world.blockIdAt(binding.button()))) {
			return reject(FailureCode.BUTTON_REPLACED, binding.button().shortString());
		}
		Long lastPress = cooldowns.get(player.getUUID());
		if (lastPress != null && tick - lastPress < config.playerCooldownTicks()) {
			return reject(FailureCode.COOLDOWN, Integer.toString(config.playerCooldownTicks()));
		}
		// Destination units depart from their own local dock. Legacy and command
		// bindings keep the station main dock and its boarding behavior.
		BlockLocation departureDock = binding.hasSourceDock() ? binding.sourceDock() : from.dock();
		boolean signBoarding = binding.hasSourceDock() || railway.state().hasAnchorMarker(from.id());
		if (signBoarding) {
			if (!PlayerActions.withinSignRadius(player, departureDock)) {
				return reject(FailureCode.PLAYER_TOO_FAR, "4");
			}
		} else if (!PlayerActions.withinRadius(player, departureDock, config.boardingRadius())) {
			return reject(FailureCode.PLAYER_TOO_FAR, Integer.toString(config.boardingRadius()));
		}

		GraphLookup fromLookup = graphOrFailure(from);
		GraphLookup toLookup = graphOrFailure(to);
		RailGraph fromGraph = fromLookup instanceof GraphLookup.Found found ? found.graph() : null;
		RailGraph toGraph = toLookup instanceof GraphLookup.Found found ? found.graph() : null;
		// A scan covers the whole connected component from its station. One graph is
		// therefore enough when it already contains the other endpoint's dock: after a
		// restart only the origin may have been rescanned, while its graph still spans
		// the destination. A separate destination scan is not required.
		if (fromGraph == null && toGraph != null && toGraph.rail(departureDock) != null) {
			fromGraph = toGraph;
		}
		if (toGraph == null && fromGraph != null && fromGraph.rail(to.dock()) != null) {
			toGraph = fromGraph;
		}
		if (fromGraph == null) {
			return reject(((GraphLookup.Failed) fromLookup).failure());
		}
		if (toGraph == null) {
			return reject(((GraphLookup.Failed) toLookup).failure());
		}
		if (!fromGraph.componentKey().equals(toGraph.componentKey())
				|| fromGraph.revision() != toGraph.revision()) {
			return reject(Failure.of(FailureCode.NO_PATH));
		}
		for (ActiveTrip other : active.values()) {
			if (other.plan.componentKey().equals(fromGraph.componentKey())
					&& other.graphRevision != fromGraph.revision()) {
				return reject(Failure.detail(FailureCode.RESERVATION_CONFLICT, "topology changed"));
			}
		}

		RailGraphPlanner.Result planned = RailGraphPlanner.plan(fromGraph, from.id(), to.id(),
				departureDock, to.dock(), config.maxRouteLength());
		if (planned instanceof RailGraphPlanner.Result.Rejected rejected) {
			return reject(rejected.failure());
		}
		GraphRoutePlan plan = ((RailGraphPlanner.Result.Planned) planned).plan();

		Failure routeFailure = world.validateGraphRoute(plan);
		if (routeFailure != null) {
			return reject(routeFailure);
		}
		if (!world.isDockFree(plan.fromDock(), player.getUUID())) {
			return reject(FailureCode.DOCK_OCCUPIED, from.id().value());
		}
		// If the destination is already loaded, keep failing early on an unsafe
		// exit. Otherwise the exit is checked once the cart reaches its chunk.
		if (world.isLoaded(to.exit()) && !world.isSafeStand(to.exit())) {
			return reject(Failure.at(FailureCode.EXIT_UNSAFE, to.exit()));
		}
		if (world.isPathOccupied(plan, player.getUUID(), new UUID(0L, 0L))) {
			return reject(FailureCode.PATH_OCCUPIED, null);
		}

		TripId tripId = TripId.random();
		java.util.Set<BlockLocation> docks = new java.util.HashSet<>();
		for (Station station : railway.state().stations()) {
			if (station.isAnchored()) {
				docks.add(station.dock());
			}
		}
		for (Binding other : railway.state().bindings()) {
			if (other.hasSourceDock()) {
				docks.add(other.sourceDock());
			}
		}
		List<String> resources = RouteResourcePlanner.derive(fromGraph, plan, docks);
		String blocked = null;
		for (String resource : resources) {
			if (!reservations.isFree(resource)) {
				blocked = resource;
				break;
			}
		}
		if (blocked != null || !reservations.tryAcquire(resources, tripId.value())) {
			return reject(Failure.detail(FailureCode.RESERVATION_CONFLICT,
					blocked == null ? "resource conflict" : blocked));
		}

		Minecart cart = null;
		try {
			cart = PlayerActions.spawnServiceCart(level, plan.fromDock(), tripId);
			if (cart == null) {
				reservations.releaseAll(tripId.value());
				return reject(FailureCode.CANNOT_PLACE_CART, null);
			}
			if (!PlayerActions.mount(player, cart)) {
				PlayerActions.discardCart(cart);
				reservations.releaseAll(tripId.value());
				return reject(FailureCode.MOUNT_FAILED, null);
			}

			Trip trip = new Trip(tripId, player.getUUID(), cart.getUUID(), plan, tick);
			trip.transitionTo(TripPhase.BOARDING, tick);
			MotionProfile profile = new MotionProfile(
					config.effectiveSpeed(),
					config.acceleration(),
					config.braking(),
					0.1
			);
			ActiveTrip activeTrip = new ActiveTrip(trip, cart, world, plan, profile,
					config.safetyMargin(), fromGraph.revision(), config.maxTripTicks(),
					tick + config.boardingDelayTicks(), config.maxManagedChunkTickets());
			Failure switchFailure = prepareLoadedSwitches(activeTrip);
			if (switchFailure != null) {
				PlayerActions.discardCart(cart);
				reservations.releaseAll(tripId.value());
				return reject(switchFailure);
			}
			Failure ticketFailure = updateChunkTickets(activeTrip);
			if (ticketFailure != null) {
				PlayerActions.discardCart(cart);
				tickets.release(tripId);
				reservations.releaseAll(tripId.value());
				return reject(ticketFailure);
			}
			active.put(tripId, activeTrip);
			byPlayer.put(player.getUUID(), tripId);
			byCart.put(cart.getUUID(), tripId);
			cooldowns.put(player.getUUID(), tick);
			railway.state().addTrip(new TripRecord(tripId, player.getUUID(), cart.getUUID(),
					plan.from(), plan.to(), TripPhase.BOARDING, tick, plan.dimension(),
					RailWorld.loc(cart.blockPosition())));
			railway.saveState();
			Messages.actionBar(player, "actionbar.boarding");
			return new StartResult.Started(tripId);
		} catch (RuntimeException e) {
			logger.error("Failed to start trip", e);
			if (cart != null) {
				PlayerActions.discardCart(cart);
			}
			tickets.release(tripId);
			reservations.releaseAll(tripId.value());
			return reject(FailureCode.INTERNAL, e.getMessage());
		}
	}

	private sealed interface GraphLookup {
		record Found(RailGraph graph) implements GraphLookup {
		}

		record Failed(Failure failure) implements GraphLookup {
		}
	}

	private GraphLookup graphOrFailure(Station station) {
		RailGraph graph = railway.scans().graph(station.id());
		if (graph != null) {
			return new GraphLookup.Found(graph);
		}
		if (railway.scans().isRunning(station.id())) {
			return new GraphLookup.Failed(Failure.detail(FailureCode.SCAN_RUNNING, station.id().value()));
		}
		Failure scanFailure = railway.scans().failure(station.id());
		return new GraphLookup.Failed(scanFailure != null ? scanFailure
				: Failure.detail(FailureCode.SCAN_INCOMPLETE, station.id().value()));
	}

	private StartResult reject(FailureCode code, String detail) {
		return new StartResult.Rejected(Failure.detail(code, detail));
	}

	private StartResult reject(Failure failure) {
		return new StartResult.Rejected(failure);
	}

	// -------------------------------------------------------------------- tick

	public void tick(MinecraftServer server) {
		if (railway.disabled()) {
			return;
		}
		long tick = server.getTickCount();
		for (TripId id : List.copyOf(active.keySet())) {
			ActiveTrip at = active.get(id);
			if (at == null) {
				continue;
			}
			try {
				tickOne(server, at, tick);
			} catch (RuntimeException e) {
				logger.error("Trip {} failed unexpectedly", id, e);
				abort(at, tick, Failure.detail(FailureCode.INTERNAL, e.getMessage()));
			}
			if (active.get(id) == at) {
				checkpoint(at, tick);
			}
		}
		cooldowns.entrySet().removeIf(entry -> tick - entry.getValue() > 10 * 60 * 20L);
		if (tick - lastSweepTick >= ORPHAN_SWEEP_INTERVAL) {
			lastSweepTick = tick;
			sweepOrphans(server);
			reconcileQuarantine(server);
		}
	}

	private long lastSweepTick;

	private void tickOne(MinecraftServer server, ActiveTrip at, long tick) {
		Trip trip = at.trip;
		ServerPlayer player = server.getPlayerList().getPlayer(trip.playerUuid());
		Minecart cart = at.cart;

		if (player == null) {
			logger.info("Trip {} ended: player offline", trip.id());
			discardAndRemove(at);
			return;
		}
		if (cart.isRemoved()) {
			logger.info("Trip {} ended: cart removed", trip.id());
			releaseAndRemove(at);
			if (trip.phase() != TripPhase.ABORTING) {
				notifyAborted(player);
			}
			return;
		}
		if (!player.isAlive()) {
			logger.info("Trip {} ended: player dead", trip.id());
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "player died"));
			return;
		}
		if (player.getVehicle() != cart) {
			discardAndRemove(at);
			notifyAborted(player);
			return;
		}
		if (tick - trip.startedTick() > at.maxTripTicks) {
			logger.warn("Trip {} timed out after {} ticks", trip.id(), tick - trip.startedTick());
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "trip timeout"));
			return;
		}

		switch (trip.phase()) {
			case BOARDING -> {
				cart.setDeltaMovement(Vec3.ZERO);
				Failure ticketFailure = updateChunkTickets(at);
				if (ticketFailure != null) {
					abort(at, tick, ticketFailure);
					return;
				}
				if (tick >= at.departureTick) {
					Failure ready = at.world.validateGraphRoute(at.plan);
					if (ready == null) {
						ready = prepareLoadedSwitches(at);
					}
					if (ready != null) {
						abort(at, tick, ready);
						return;
					}
					trip.transitionTo(TripPhase.RUNNING, tick);
					Station to = railway.state().station(at.plan.to()).orElse(null);
					Messages.actionBar(player, "actionbar.departing",
							to == null ? at.plan.to().value() : to.displayName());
				}
			}
			case RUNNING -> tickGraph(at, player, tick);
			case ARRIVING -> finishArrival(at, player, tick);
			case ABORTING -> retrySafeExit(at, player, tick);
			case PREPARING, COMPLETED, ABORTED, RECOVERING -> {
			}
		}
	}

	private void tickGraph(ActiveTrip at, ServerPlayer player, long tick) {
		Minecart cart = at.cart;
		RouteStep current = at.plan.steps().get(at.stepIndex);
		BlockLocation cartBlock = RailWorld.loc(cart.blockPosition());
		if (!matchesStep(cartBlock, cart, current)) {
			int found = -1;
			for (int i = at.stepIndex + 1; i < at.plan.steps().size() && i <= at.stepIndex + 3; i++) {
				if (matchesStep(cartBlock, cart, at.plan.steps().get(i))) {
					found = i;
					break;
				}
			}
			if (found < 0) {
				abort(at, tick, Failure.at(FailureCode.INTERNAL, cartBlock, "cart left planned route"));
				return;
			}
			at.stepIndex = found;
			current = at.plan.steps().get(at.stepIndex);
		}

		RouteStep next = at.stepIndex + 1 < at.plan.steps().size()
				? at.plan.steps().get(at.stepIndex + 1) : null;
		Failure ticketFailure = updateChunkTickets(at);
		if (ticketFailure != null) {
			abort(at, tick, ticketFailure);
			return;
		}
		BlockLocation blocked = null;
		if (!tickets.isPositionReady(at.trip.id(), at.world.level(), current.pos())) {
			blocked = current.pos();
		} else if (next != null && !tickets.isPositionReady(at.trip.id(), at.world.level(), next.pos())) {
			blocked = next.pos();
		}
		if (blocked != null) {
			cart.setDeltaMovement(Vec3.ZERO);
			at.speed = 0;
			// A deliberate wait is not a stuck cart; the readiness timeout below
			// owns this failure instead of the generic no-progress check.
			at.lastProgressTick = tick;
			if (at.chunkWaitSinceTick < 0) {
				at.chunkWaitSinceTick = tick;
			} else if (tick - at.chunkWaitSinceTick > CHUNK_READY_WAIT_TICKS) {
				abort(at, tick, Failure.at(FailureCode.CHUNKS_NOT_READY, blocked));
			}
			return;
		}
		at.chunkWaitSinceTick = -1;

		Failure upcomingFailure = ensureUpcomingSwitches(at);
		if (upcomingFailure != null) {
			abort(at, tick, upcomingFailure);
			return;
		}
		Failure currentFailure = ensureStepShape(at, current, true);
		if (currentFailure != null) {
			abort(at, tick, currentFailure);
			return;
		}
		if (next != null) {
			Failure nextFailure = ensureStepShape(at, next, false);
			if (nextFailure != null) {
				abort(at, tick, nextFailure);
				return;
			}
		}

		double moved = Math.abs(cart.getX() - at.lastX) + Math.abs(cart.getZ() - at.lastZ);
		if (moved > 0.005) {
			at.lastProgressTick = tick;
			at.lastX = cart.getX();
			at.lastZ = cart.getZ();
		}
		double remaining = remaining(at, cart);
		if (tick - at.lastProgressTick > 40 && remaining > STOP_DISTANCE) {
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "cart made no progress"));
			return;
		}

		if (at.stepIndex == at.plan.steps().size() - 1) {
			if (remaining < -0.3) {
				abort(at, tick, Failure.detail(FailureCode.INTERNAL, "passed destination dock"));
				return;
			}
			if (remaining <= STOP_DISTANCE && at.speed <= STOP_SPEED) {
				stopAtDock(at);
				at.trip.transitionTo(TripPhase.ARRIVING, tick);
				finishArrival(at, player, tick);
				return;
			}
		}

		double limit = speedLimit(at, current);
		if (next != null) {
			limit = Math.min(limit, speedLimit(at, next));
		}
		double target = Math.min(at.profile.targetSpeed(remaining), limit);
		at.speed = at.profile.step(at.speed, target);
		applyVelocity(at, cart, current, next);
	}

	/**
	 * Reconciles the cart's local ticket window. Only chunks covered by the
	 * scanned route are ever ticketed, so a ticket cannot target an unknown chunk.
	 */
	private Failure updateChunkTickets(ActiveTrip at) {
		if (at.maxChunkTickets <= 0) {
			return null;
		}
		if (at.ticketWindowStep != at.stepIndex) {
			double distance = at.profile.brakingDistance(at.profile.maxSpeed())
					+ at.safetyMargin
					+ at.profile.maxSpeed() * MotionProfile.TICK_SECONDS;
			ChunkWindow.Window window = ChunkWindow.compute(at.route, at.stepIndex, distance,
					at.maxChunkTickets);
			if (window.overBudget()) {
				return Failure.detail(FailureCode.CHUNKS_NOT_READY, "ticket budget " + at.maxChunkTickets);
			}
			at.ticketWindow = window.chunks();
			at.ticketWindowStep = at.stepIndex;
		}
		ChunkTicketManager.Update update = tickets.update(at.world.level(), at.trip.id(), at.routeChunks,
				at.ticketWindow, at.maxChunkTickets);
		if (update instanceof ChunkTicketManager.Update.BudgetExceeded exceeded) {
			return Failure.detail(FailureCode.CHUNKS_NOT_READY, "ticket budget " + exceeded.budget());
		}
		return null;
	}

	/**
	 * Persists the cart position for crash recovery. Writes on every phase change
	 * and at most once per {@link #CHECKPOINT_INTERVAL_TICKS}; the checkpoint is
	 * reconciliation data, not a claim about the entity.
	 */
	private void checkpoint(ActiveTrip at, long tick) {
		TripPhase phase = at.trip.phase();
		boolean phaseChanged = phase != at.lastCheckpointPhase;
		if (!phaseChanged && tick - at.lastCheckpointTick < CHECKPOINT_INTERVAL_TICKS) {
			return;
		}
		at.lastCheckpointPhase = phase;
		at.lastCheckpointTick = tick;
		boolean changed = railway.state().updateTripCheckpoint(at.trip.id(), at.plan.dimension(),
				RailWorld.loc(at.cart.blockPosition()), phase);
		if (changed) {
			railway.saveState();
		}
	}

	private boolean matchesStep(BlockLocation cartBlock, Minecart cart, RouteStep step) {
		return cartBlock.x() == step.pos().x()
				&& cartBlock.z() == step.pos().z()
				&& Math.abs(cartBlock.y() - step.pos().y()) <= 1;
	}

	private Failure ensureUpcomingSwitches(ActiveTrip at) {
		int lookahead = (int) Math.ceil(at.profile.brakingDistance(at.speed) + at.safetyMargin) + 1;
		int last = Math.min(at.plan.steps().size() - 1, at.stepIndex + lookahead);
		for (int i = at.stepIndex; i <= last; i++) {
			RouteStep step = at.plan.steps().get(i);
			RailShapeType required = at.requiredSwitches.get(step.pos());
			if (required == null || !at.world.isLoaded(step.pos())) {
				continue;
			}
			Failure failure = ensureSwitch(at, step.pos(), required);
			if (failure != null) {
				return failure;
			}
		}
		return null;
	}

	/**
	 * Prepares a turnout the first time its chunk is loaded, then keeps checking
	 * it so an external shape change still aborts the trip.
	 */
	private Failure ensureSwitch(ActiveTrip at, BlockLocation pos, RailShapeType required) {
		if (!at.preparedSwitches.contains(pos)) {
			Failure failure = at.world.prepareSwitch(pos, required);
			if (failure != null) {
				return failure;
			}
			at.preparedSwitches.add(pos);
			return null;
		}
		if (at.world.shapeAt(pos) != required) {
			return Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, pos, "turnout changed ahead");
		}
		return null;
	}

	/** Applies every switch whose chunk is loaded now; the rest wait for their chunk. */
	private Failure prepareLoadedSwitches(ActiveTrip at) {
		for (SwitchRequirement requirement : LaunchCheck.loadedSwitches(at.plan, at.world::isLoaded)) {
			Failure failure = ensureSwitch(at, requirement.pos(), requirement.requiredShape());
			if (failure != null) {
				return failure;
			}
		}
		return null;
	}

	private Failure ensureStepShape(ActiveTrip at, RouteStep step, boolean current) {
		RailShapeType required = at.requiredSwitches.getOrDefault(step.pos(), step.shape());
		RailShapeType actual = at.world.shapeAt(step.pos());
		if (actual == required) {
			return null;
		}
		// Turnouts are set before departure. Any later change is external and must
		// stop the trip at the conflict zone rather than being silently rewritten.
		return Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, step.pos(), "rail shape changed");
	}

	private double speedLimit(ActiveTrip at, RouteStep step) {
		if (step.shape().isStraightHorizontal()) {
			return at.profile.maxSpeed();
		}
		return Math.min(at.profile.maxSpeed(), TURN_SPEED_LIMIT);
	}

	private void applyVelocity(ActiveTrip at, Minecart cart, RouteStep current, RouteStep next) {
		double targetX;
		double targetZ;
		if (next != null) {
			targetX = next.pos().x() + 0.5;
			targetZ = next.pos().z() + 0.5;
		} else {
			targetX = at.plan.toDock().x() + 0.5;
			targetZ = at.plan.toDock().z() + 0.5;
		}
		double dx = targetX - cart.getX();
		double dz = targetZ - cart.getZ();
		double length = Math.hypot(dx, dz);
		double perTick = at.speed * MotionProfile.TICK_SECONDS;
		if (length < 1.0E-4) {
			cart.setDeltaMovement(Vec3.ZERO);
			return;
		}
		cart.setDeltaMovement(dx / length * perTick, 0.0, dz / length * perTick);
	}

	private double remaining(ActiveTrip at, Minecart cart) {
		int last = at.plan.steps().size() - 1;
		if (at.stepIndex >= last) {
			return horizontalDistance(cart.getX(), cart.getZ(), at.plan.toDock().x() + 0.5,
					at.plan.toDock().z() + 0.5);
		}
		RouteStep next = at.plan.steps().get(at.stepIndex + 1);
		double distanceToNext = horizontalDistance(cart.getX(), cart.getZ(),
				next.pos().x() + 0.5, next.pos().z() + 0.5);
		double transitions = last - at.stepIndex;
		double covered = 1.0 - Math.min(1.0, distanceToNext);
		return Math.max(0.0, transitions - covered);
	}

	private static double horizontalDistance(double x1, double z1, double x2, double z2) {
		return Math.hypot(x2 - x1, z2 - z1);
	}

	private void stopAtDock(ActiveTrip at) {
		at.cart.setDeltaMovement(Vec3.ZERO);
		at.speed = 0;
		at.cart.setPos(at.plan.toDock().x() + 0.5, at.cart.getY(), at.plan.toDock().z() + 0.5);
	}

	private void finishArrival(ActiveTrip at, ServerPlayer player, long tick) {
		GraphRoutePlan plan = at.plan;
		if (player.getVehicle() != at.cart) {
			discardAndRemove(at);
			notifyAborted(player);
			return;
		}
		BlockLocation exit = plan.steps().getLast().pos().offset(0, 0, 0);
		Station station = railway.state().station(plan.to()).orElse(null);
		if (station != null && station.hasExit()) {
			exit = station.exit();
		}
		if (!at.world.isPositionReady(exit)) {
			waitForArrival(at, player, tick, exit, FailureCode.CHUNKS_NOT_READY);
			return;
		}
		BlockLocation target;
		if (at.world.isSafeStand(exit)) {
			target = exit;
		} else {
			Optional<BlockLocation> safe = at.world.findSafeSpot(exit, 3);
			if (safe.isEmpty()) {
				waitForArrival(at, player, tick, exit, FailureCode.EXIT_UNSAFE);
				return;
			}
			target = safe.get();
			if ((tick - at.lastWarningTick) >= WARNING_INTERVAL) {
				at.lastWarningTick = tick;
				player.sendSystemMessage(Messages.failure(player, Failure.at(FailureCode.EXIT_UNSAFE, exit)));
			}
		}

		PlayerActions.dismountTo(player, at.world.level(), target, station == null ? 0.0F : station.exitYaw());
		boolean removed = PlayerActions.discardCart(at.cart);
		at.trip.transitionTo(TripPhase.COMPLETED, tick);
		if (removed) {
			removeTrip(at);
		} else {
			quarantineAndRemove(at, "cart removal unverified after arrival");
		}
		Messages.actionBar(player, "actionbar.arrived", station == null ? plan.to().value() : station.displayName());
	}

	private void waitForArrival(ActiveTrip at, ServerPlayer player, long tick, BlockLocation target,
			FailureCode code) {
		if (at.arrivalWaitSinceTick < 0) {
			at.arrivalWaitSinceTick = tick;
		}
		if (tick - at.arrivalWaitSinceTick > CHUNK_READY_WAIT_TICKS) {
			logger.warn("Trip {} arrival target {} unusable: {}", at.trip.id(),
					target.shortString(), code);
			abort(at, tick, Failure.at(code, target));
			return;
		}
		warnPeriodically(at, player, tick,
				code == FailureCode.CHUNKS_NOT_READY ? "error.chunks_not_ready" : "error.exit_unsafe");
	}

	private void retrySafeExit(ActiveTrip at, ServerPlayer player, long tick) {
		if (player.getVehicle() != at.cart) {
			discardAndRemove(at);
			notifyAborted(player);
			return;
		}
		if ((tick - at.lastSafeRetryTick) < 20) {
			return;
		}
		at.lastSafeRetryTick = tick;
		BlockLocation center = RailWorld.loc(at.cart.blockPosition());
		Optional<BlockLocation> safe = at.world.findSafeSpot(center, 3);
		if (safe.isPresent()) {
			PlayerActions.dismountTo(player, at.world.level(), safe.get(), exitYaw(at));
			boolean removed = PlayerActions.discardCart(at.cart);
			abortComplete(at, tick, removed);
			notifyAborted(player);
		} else {
			warnPeriodically(at, player, tick, "error.exit_unsafe");
		}
	}

	private void warnPeriodically(ActiveTrip at, ServerPlayer player, long tick, String key) {
		if (tick - at.lastWarningTick >= WARNING_INTERVAL) {
			at.lastWarningTick = tick;
			player.sendSystemMessage(Messages.text(player, key));
		}
	}

	private void abort(ActiveTrip at, long tick, Failure failure) {
		abort(at, tick, failure, true);
	}

	private void abort(ActiveTrip at, long tick, Failure failure, boolean notifyFailure) {
		Trip trip = at.trip;
		if (trip.phase().isTerminal()) {
			return;
		}
		logger.info("Trip {} aborting: {} {}", trip.id(), failure.code(),
				failure.location() == null ? "" : failure.location().shortString());
		at.cart.setDeltaMovement(Vec3.ZERO);
		at.speed = 0;
		ServerPlayer player = railway.server().getPlayerList().getPlayer(trip.playerUuid());

		if (player != null && player.getVehicle() == at.cart) {
			Optional<BlockLocation> safe = at.world.findSafeSpot(RailWorld.loc(at.cart.blockPosition()), 3);
			if (safe.isEmpty()) {
				trip.aborting(failure.code().name(), tick);
				if (notifyFailure) {
					notifyTrip(trip, player, failure);
				}
				warnPeriodically(at, player, tick, "error.exit_unsafe");
				return;
			}
			PlayerActions.dismountTo(player, at.world.level(), safe.get(), exitYaw(at));
			boolean removed = PlayerActions.discardCart(at.cart);
			abortComplete(at, tick, removed);
			if (notifyFailure) {
				notifyTrip(trip, player, failure);
			} else {
				Messages.actionBar(player, "actionbar.aborted");
			}
			return;
		}

		boolean removed = PlayerActions.discardCart(at.cart);
		abortComplete(at, tick, removed);
		if (player != null) {
			if (notifyFailure) {
				notifyTrip(trip, player, failure);
			} else {
				Messages.actionBar(player, "actionbar.aborted");
			}
		}
	}

	private void abortComplete(ActiveTrip at, long tick, boolean cartRemoved) {
		if (at.trip.phase() != TripPhase.ABORTING) {
			at.trip.transitionTo(TripPhase.ABORTING, tick);
		}
		at.trip.transitionTo(TripPhase.ABORTED, tick);
		if (cartRemoved) {
			removeTrip(at);
		} else {
			quarantineAndRemove(at, "cart removal unverified after abort");
		}
	}

	private void removeTrip(ActiveTrip at) {
		tickets.release(at.trip.id());
		active.remove(at.trip.id());
		byPlayer.remove(at.trip.playerUuid(), at.trip.id());
		byCart.remove(at.cart.getUUID(), at.trip.id());
		reservations.releaseAll(at.trip.id().value());
		railway.state().removeTrip(at.trip.id());
		railway.saveState();
	}

	private void discardAndRemove(ActiveTrip at) {
		boolean removed = PlayerActions.discardCart(at.cart);
		if (removed) {
			releaseAndRemove(at);
		} else {
			quarantineAndRemove(at, "cart removal unverified");
		}
	}

	private void quarantineAndRemove(ActiveTrip at, String reason) {
		int count = reservations.quarantineAll(at.trip.id().value(), at.cart.getUUID(), reason);
		logger.warn("Trip {} quarantined {} resource(s): {}", at.trip.id(), count, reason);
		tickets.release(at.trip.id());
		active.remove(at.trip.id());
		byPlayer.remove(at.trip.playerUuid());
		byCart.remove(at.cart.getUUID());
		railway.state().removeTrip(at.trip.id());
		railway.saveState();
	}

	private void releaseAndRemove(ActiveTrip at) {
		tickets.release(at.trip.id());
		active.remove(at.trip.id());
		byPlayer.remove(at.trip.playerUuid());
		byCart.remove(at.cart.getUUID());
		reservations.releaseAll(at.trip.id().value());
		railway.state().removeTrip(at.trip.id());
		railway.saveState();
	}

	private float exitYaw(ActiveTrip at) {
		Station to = railway.state().station(at.plan.to()).orElse(null);
		return to == null ? 0.0F : to.exitYaw();
	}

	private void notifyTrip(Trip trip, ServerPlayer player, Failure failure) {
		player.sendSystemMessage(Messages.failure(player, failure));
		Messages.actionBar(player, "actionbar.aborted");
	}

	private void notifyAborted(ServerPlayer player) {
		Messages.actionBar(player, "actionbar.aborted");
	}

	// --------------------------------------------------------------- external

	public AbortResult abort(TripId id) {
		ActiveTrip at = active.get(id);
		if (at == null) {
			return new AbortResult.NotFound();
		}
		long tick = railway.server().getTickCount();
		abort(at, tick, Failure.detail(FailureCode.INTERNAL, "aborted by command"), false);
		if (active.containsKey(id)) {
			return new AbortResult.Holding(id);
		}
		return new AbortResult.Aborted(id);
	}

	public void playerLeft(UUID playerUuid) {
		TripId id = byPlayer.get(playerUuid);
		if (id == null) {
			return;
		}
		ActiveTrip at = active.get(id);
		if (at == null) {
			return;
		}
		discardAndRemove(at);
	}

	public boolean hasActiveTrip(UUID playerUuid) {
		return byPlayer.containsKey(playerUuid);
	}

	/** Whether this trip is still driven by the runtime service. */
	public boolean isActive(TripId id) {
		return active.containsKey(id);
	}

	/** Whether a live trip owns the cart; recovery and the sweep must leave it alone. */
	public boolean managesCart(UUID cartUuid) {
		return byCart.containsKey(cartUuid);
	}

	public boolean stationInUse(StationId id) {
		for (ActiveTrip at : active.values()) {
			GraphRoutePlan plan = at.plan;
			if (plan.from().equals(id) || plan.to().equals(id)) {
				return true;
			}
		}
		return false;
	}

	public List<Trip> activeTrips() {
		List<Trip> trips = new ArrayList<>();
		for (ActiveTrip at : active.values()) {
			trips.add(at.trip);
		}
		return trips;
	}

	public int activeCount() {
		return active.size();
	}

	// ------------------------------------------------------------ diagnostics

	/** Read-only view of every active trip, including its ticket window state. */
	public List<DiagnosticsReport.TripSummary> activeSummaries() {
		List<DiagnosticsReport.TripSummary> summaries = new ArrayList<>(active.size());
		for (ActiveTrip at : active.values()) {
			Optional<DiagnosticsReport.TicketStats> stats = tickets.stats(at.trip.id())
					.map(ticket -> new DiagnosticsReport.TicketStats(ticket.windowChunks(),
							ticket.tickets(), ticket.pendingChunks(), ticket.readinessChecks()));
			summaries.add(new DiagnosticsReport.TripSummary(at.trip.id(), at.trip.phase(),
					at.plan.from(), at.plan.to(), at.trip.playerUuid(), at.trip.cartUuid(),
					at.graphRevision, at.plan.componentKey(), at.plan.length(), at.plan.fromDock(),
					at.plan.toDock(), at.trip.abortReason(), stats));
		}
		return List.copyOf(summaries);
	}

	/** Number of route resources currently owned by active trips. */
	public int ownedReservationCount() {
		return reservations.snapshot().size();
	}

	/** Read-only list of resources waiting for cart reconciliation. */
	public List<ReservationManager.QuarantinedResource> quarantinedResources() {
		return reservations.quarantinedResources();
	}

	// --------------------------------------------------------------- recovery

	public void shutdown() {
		for (ActiveTrip at : List.copyOf(active.values())) {
			PlayerActions.discardCart(at.cart);
			tickets.release(at.trip.id());
			reservations.releaseAll(at.trip.id().value());
			railway.state().removeTrip(at.trip.id());
		}
		active.clear();
		byPlayer.clear();
		byCart.clear();
		cooldowns.clear();
		tickets.releaseAll();
		railway.saveState();
	}

	private void reconcileQuarantine(MinecraftServer server) {
		Set<UUID> cartIds = new LinkedHashSet<>();
		for (var entry : reservations.quarantinedResources()) {
			cartIds.add(entry.quarantine().cartUuid());
		}
		EntityTypeTest<Entity, Minecart> minecartType = EntityTypeTest.forClass(Minecart.class);
		for (UUID cartId : cartIds) {
			boolean present = false;
			for (ServerLevel level : server.getAllLevels()) {
				for (Minecart cart : level.getEntities(minecartType, RailWorld.allEntities(),
						candidate -> candidate.getUUID().equals(cartId))) {
					if (!cart.isRemoved()) {
						if (!cart.isVehicle()) {
							PlayerActions.discardCart(cart);
						}
						if (!cart.isRemoved()) {
							present = true;
						}
					}
				}
			}
			if (!present && reservations.reconcile(cartId) > 0) {
				logger.info("Quarantine cleared for removed service cart {}", cartId);
			}
		}
	}

	private void sweepOrphans(MinecraftServer server) {
		EntityTypeTest<Entity, Minecart> minecartType = EntityTypeTest.forClass(Minecart.class);
		for (ServerLevel level : server.getAllLevels()) {
			List<Minecart> carts = level.getEntities(minecartType, RailWorld.allEntities(), cart ->
					cart.entityTags().contains(PlayerActions.MANAGED_TAG)
							&& !byCart.containsKey(cart.getUUID())
							// A cart still referenced by an unresolved checkpoint is
							// recovery's business, not an orphan yet.
							&& !railway.recovery().isPendingCart(cart.getUUID()));
			for (Minecart cart : carts) {
				if (cart.isVehicle()) {
					logger.warn("Orphan service cart {} still has passengers; leaving it stopped", cart.getUUID());
					cart.setDeltaMovement(Vec3.ZERO);
				} else {
					logger.info("Removing orphan service cart {}", cart.getUUID());
					PlayerActions.discardCart(cart);
				}
			}
		}
	}
}
