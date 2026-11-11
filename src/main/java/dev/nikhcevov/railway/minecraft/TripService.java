package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.diagnostics.DiagnosticsReport;
import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.dispatch.ReservationManager;
import dev.nikhcevov.railway.dispatch.RouteResourcePlanner;
import dev.nikhcevov.railway.domain.ArrivalPlatform;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.Trip;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;
import dev.nikhcevov.railway.logging.EventLog;
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
import java.util.Locale;
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
		final RouteResourcePlanner.RouteSchedule schedule;
		final BlockLocation arrivalExit;
		final float arrivalYaw;
		final MotionProfile profile;
		final Map<BlockLocation, RailShapeType> requiredSwitches;
		final Set<BlockLocation> preparedSwitches = new LinkedHashSet<>();
		final Set<String> heldExclusive = new LinkedHashSet<>();
		final Set<String> heldCorridors = new LinkedHashSet<>();
		final List<BlockLocation> route;
		final Set<ChunkWindow.Key> routeChunks;
		final double safetyMargin;
		final int segmentLength;
		final int initialLookaheadSteps;
		final long graphRevision;
		final int maxTripTicks;
		final int maxChunkTickets;
		final long departureTick;
		boolean initialResourcesAcquired;
		double speed;
		int stepIndex;
		long lastWarningTick;
		long lastWaitStatusTick = -1;
		int lastWaitEtaSeconds = -1;
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

		ActiveTrip(Trip trip, Minecart cart, RailWorld world, GraphRoutePlan plan,
				RouteResourcePlanner.RouteSchedule schedule,
				BlockLocation arrivalExit, float arrivalYaw, MotionProfile profile,
				double safetyMargin, int segmentLength, long graphRevision, int maxTripTicks,
				long departureTick, int maxChunkTickets) {
			this.trip = trip;
			this.cart = cart;
			this.world = world;
			this.plan = plan;
			this.schedule = schedule;
			this.arrivalExit = arrivalExit;
			this.arrivalYaw = arrivalYaw;
			this.profile = profile;
			this.safetyMargin = safetyMargin;
			this.segmentLength = Math.max(4, segmentLength);
			this.initialLookaheadSteps = Math.min(plan.steps().size() - 1, this.segmentLength);
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
		long tick = railway.server().getTickCount();
		StartResult result = startInternal(player, binding, tick);
		if (result instanceof StartResult.Rejected rejected) {
			logLaunchRejection(player, binding, rejected.failure(), tick);
		}
		return result;
	}

	private StartResult startInternal(ServerPlayer player, Binding binding, long tick) {
		MinecraftServer server = railway.server();
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
			return reject(FailureCode.STATION_MISSING, null);
		}
		if (toOpt.isEmpty()) {
			return reject(FailureCode.STATION_MISSING, null);
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
			return reject(FailureCode.STATION_DISABLED, from.displayName());
		}
		if (!to.enabled()) {
			return reject(FailureCode.STATION_DISABLED, to.displayName());
		}
		if (!to.hasExit()) {
			return reject(FailureCode.STATION_INCOMPLETE, to.displayName());
		}
		ArrivalPlatform platform = to.arrivalFrom(from.id()).orElse(null);
		BlockLocation arrivalDock = platform != null ? platform.dock() : to.dock();
		BlockLocation arrivalExit = platform != null ? platform.exit() : to.exit();
		float arrivalYaw = platform != null ? platform.yaw() : to.exitYaw();

		ServerLevel level = RailWorld.level(server, from.dimension());
		if (level == null) {
			return reject(FailureCode.WORLD_NOT_LOADED, from.dimension());
		}
		if (player.level() != level) {
			return reject(FailureCode.DIMENSION_MISMATCH, null);
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
		RailGraph fromGraph = fromLookup instanceof GraphLookup.Found found ? found.graph() : null;
		GraphLookup toLookup = null;
		RailGraph toGraph = railway.scans().graph(to.id());
		// A scan covers the whole connected component from its station. One graph is
		// therefore enough when it already contains the other endpoint's dock: after a
		// restart only the origin may have been rescanned, while its graph still spans
		// the destination. Do not start a destination scan in that case.
		if (toGraph == null && (fromGraph == null || fromGraph.rail(arrivalDock) == null)) {
			toLookup = graphOrFailure(to);
			toGraph = toLookup instanceof GraphLookup.Found found ? found.graph() : null;
		}
		if (fromGraph == null && toGraph != null && toGraph.rail(departureDock) != null) {
			fromGraph = toGraph;
		}
		if (toGraph == null && fromGraph != null && fromGraph.rail(arrivalDock) != null) {
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
				departureDock, arrivalDock, config.maxRouteLength());
		if (planned instanceof RailGraphPlanner.Result.Rejected rejected) {
			return reject(rejected.failure());
		}
		GraphRoutePlan plan = ((RailGraphPlanner.Result.Planned) planned).plan();

		Failure routeFailure = world.validateGraphRoute(plan);
		if (routeFailure != null) {
			return reject(routeFailure);
		}
		if (!world.isDockFree(plan.fromDock(), player.getUUID())) {
			return reject(FailureCode.DOCK_OCCUPIED, null);
		}
		// If the destination is already loaded, keep failing early on an unsafe
		// exit. Otherwise the exit is checked once the cart reaches its chunk.
		if (world.isLoaded(arrivalExit) && !world.isSafeStand(arrivalExit)) {
			return reject(Failure.at(FailureCode.EXIT_UNSAFE, arrivalExit));
		}
		TripId tripId = TripId.random();
		java.util.Set<BlockLocation> docks = new java.util.HashSet<>();
		for (Station station : railway.state().stations()) {
			if (station.isAnchored()) {
				docks.add(station.dock());
				for (ArrivalPlatform arrival : station.arrivalPlatforms()) {
					docks.add(arrival.dock());
				}
			}
		}
		for (Binding other : railway.state().bindings()) {
			if (other.hasSourceDock()) {
				docks.add(other.sourceDock());
			}
		}
		RouteResourcePlanner.RouteSchedule schedule = RouteResourcePlanner.planSchedule(
				fromGraph, plan, docks, config.segmentLength());
		for (RouteResourcePlanner.CorridorSpan corridor : schedule.corridors()) {
			if (reservations.isQuarantined(corridor.key())) {
				return reject(Failure.detail(FailureCode.RESERVATION_CONFLICT, corridor.key()));
			}
		}
		for (RouteResourcePlanner.ExclusiveSpan span : schedule.exclusiveSpans()) {
			if (reservations.isQuarantined(span.key())) {
				return reject(Failure.detail(FailureCode.RESERVATION_CONFLICT, span.key()));
			}
		}
		for (ActiveTrip other : active.values()) {
			if (other.plan.toDock().equals(plan.fromDock())) {
				return reject(Failure.detail(FailureCode.RESERVATION_CONFLICT, schedule.departurePlatformKey()));
			}
		}
		if (!reservations.tryAcquire(List.of(schedule.departurePlatformKey()), tripId.value())) {
			return reject(Failure.detail(FailureCode.RESERVATION_CONFLICT, schedule.departurePlatformKey()));
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
			ActiveTrip activeTrip = new ActiveTrip(trip, cart, world, plan, schedule, arrivalExit, arrivalYaw,
					profile, config.safetyMargin(), config.segmentLength(), fromGraph.revision(),
					config.maxTripTicks(), tick + config.boardingDelayTicks(), config.maxManagedChunkTickets());
			activeTrip.heldExclusive.add(schedule.departurePlatformKey());
			Failure initialAcquireFailure = tryAcquireInitialResources(activeTrip);
			if (initialAcquireFailure != null) {
				PlayerActions.discardCart(cart);
				reservations.releaseAll(tripId.value());
				return reject(initialAcquireFailure);
			}
			if (activeTrip.initialResourcesAcquired) {
				Failure switchFailure = prepareLoadedSwitches(activeTrip);
				if (switchFailure != null) {
					PlayerActions.discardCart(cart);
					reservations.releaseAll(tripId.value());
					return reject(switchFailure);
				}
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
			EventLog.event("trip.start")
					.tick(tick)
					.trip(tripId)
					.field("player", player.getPlainTextName())
					.field("playerId", player.getUUID())
					.field("from", plan.from().value())
					.field("fromName", stationName(plan.from()))
					.field("to", plan.to().value())
					.field("toName", stationName(plan.to()))
					.field("dim", plan.dimension())
					.field("depart", plan.fromDock())
					.field("arrive", plan.toDock())
					.field("length", plan.length())
					.field("steps", plan.steps().size())
					.revision(fromGraph.revision())
					.field("component", plan.componentKey())
					.field("speed", String.format(Locale.ROOT, "%.2f", config.effectiveSpeed()))
					.field("maxTripTicks", config.maxTripTicks())
					.info(logger);
			if (activeTrip.initialResourcesAcquired) {
				Messages.actionBar(player, "actionbar.boarding");
			} else {
				updateBoardingWaitMessage(activeTrip, player, tick, true);
			}
			return new StartResult.Started(tripId);
		} catch (RuntimeException e) {
			if (cart != null) {
				PlayerActions.discardCart(cart);
			}
			tickets.release(tripId);
			reservations.releaseAll(tripId.value());
			return reject(Failure.fromThrowable(FailureCode.INTERNAL, e));
		}
	}

	private sealed interface GraphLookup {
		record Found(RailGraph graph) implements GraphLookup {
		}

		record Failed(Failure failure) implements GraphLookup {
		}
	}

	private GraphLookup graphOrFailure(Station station) {
		ScanService scans = railway.scans();
		RailGraph graph = scans.graph(station.id());
		if (graph != null) {
			return new GraphLookup.Found(graph);
		}
		if (scans.isRunning(station.id())) {
			return new GraphLookup.Failed(Failure.detail(FailureCode.SCAN_RUNNING, station.displayName()));
		}
		// Graphs are derived and not persisted: after a restart, an invalidation
		// or a previous failure the first launch must rebuild it in the
		// background. The player only has to press the button again.
		ServerLevel level = RailWorld.level(railway.server(), station.dimension());
		if (level != null) {
			ScanService.StartResult started = scans.autoStart(station, level, railway.config(),
					railway.server().getTickCount(), railway.state().revision(), ScanTrigger.TRIP_LAUNCH);
			if (started instanceof ScanService.StartResult.Started) {
				return new GraphLookup.Failed(ScanService.autoStartedFailure(station.displayName()));
			}
			if (started instanceof ScanService.StartResult.Rejected rejected) {
				return new GraphLookup.Failed(rejected.failure());
			}
		}
		Failure scanFailure = scans.failure(station.id());
		return new GraphLookup.Failed(scanFailure != null ? scanFailure
				: Failure.detail(FailureCode.SCAN_INCOMPLETE, station.displayName()));
	}

	private void logLaunchRejection(ServerPlayer player, Binding binding, Failure failure, long tick) {
		Station from = railway.state().station(binding.fromStationId()).orElse(null);
		Station to = railway.state().station(binding.toStationId()).orElse(null);
		EventLog event = EventLog.event("trip.reject")
				.tick(tick)
				.field("player", player.getPlainTextName())
				.field("playerId", player.getUUID())
				.field("button", binding.button())
				.field("from", binding.fromStationId().value())
				.field("fromName", from == null ? null : from.displayName())
				.field("to", binding.toStationId().value())
				.field("toName", to == null ? null : to.displayName())
				.failure(failure);
		if (failure.code() == FailureCode.INTERNAL) {
			event.warn(logger);
		} else {
			event.info(logger);
		}
	}

	private String stationName(StationId id) {
		return railway.state().station(id).map(Station::displayName).orElse(id.value());
	}

	private void logDetach(ActiveTrip at, ServerPlayer player, long tick) {
		EventLog.event("trip.detach")
				.tick(tick)
				.trip(at.trip.id())
				.field("player", player.getPlainTextName())
				.phase(at.trip.phase())
				.field("cart", RailWorld.loc(at.cart.blockPosition()))
				.field("reason", "not riding service cart")
				.warn(logger);
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
				if (at.trip.phase().isTerminal()) {
					ServerPlayer player = server.getPlayerList().getPlayer(at.trip.playerUuid());
					EventLog.event("trip.tick.error")
							.tick(tick)
							.trip(id)
							.field("player", player == null
									? at.trip.playerUuid().toString() : player.getPlainTextName())
							.phase(at.trip.phase())
							.cause(e)
							.error(logger);
				} else {
					abort(at, tick, Failure.fromThrowable(FailureCode.INTERNAL, e));
				}
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
			EventLog.event("trip.end")
					.tick(tick)
					.trip(trip.id())
					.field("player", trip.playerUuid().toString())
					.phase(trip.phase())
					.field("reason", "player_offline")
					.info(logger);
			discardAndRemove(at);
			return;
		}
		if (cart.isRemoved()) {
			EventLog.event("trip.end")
					.tick(tick)
					.trip(trip.id())
					.field("player", player.getPlainTextName())
					.phase(trip.phase())
					.field("reason", "cart_removed")
					.info(logger);
			releaseAndRemove(at);
			if (trip.phase() != TripPhase.ABORTING) {
				notifyAborted(player);
			}
			return;
		}
		if (!player.isAlive()) {
			EventLog.event("trip.end")
					.tick(tick)
					.trip(trip.id())
					.field("player", player.getPlainTextName())
					.phase(trip.phase())
					.field("reason", "player_dead")
					.info(logger);
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "player died"));
			return;
		}
		if (player.getVehicle() != cart) {
			logDetach(at, player, tick);
			discardAndRemove(at);
			notifyAborted(player);
			return;
		}
		if (tick - trip.startedTick() > at.maxTripTicks) {
			Failure timeout = Failure.detail(FailureCode.INTERNAL, "trip timeout");
			EventLog.event("trip.timeout")
					.tick(tick)
					.trip(trip.id())
					.field("player", player.getPlainTextName())
					.field("ticks", tick - trip.startedTick())
					.failure(timeout)
					.warn(logger);
			abort(at, tick, timeout);
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
				if (!at.initialResourcesAcquired) {
					Failure acquireFailure = tryAcquireInitialResources(at);
					if (acquireFailure != null) {
						abort(at, tick, acquireFailure);
						return;
					}
					if (!at.initialResourcesAcquired) {
						at.lastProgressTick = tick;
						updateBoardingWaitMessage(at, player, tick, false);
						return;
					}
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
					EventLog.event("trip.depart")
							.tick(tick)
							.trip(trip.id())
							.field("player", player.getPlainTextName())
							.field("dock", at.plan.fromDock())
							.field("steps", at.plan.steps().size())
							.info(logger);
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
		releasePassedResources(at);
		LookaheadResult lookahead = acquireLookaheadResources(at);
		if (lookahead.failure() != null) {
			abort(at, tick, lookahead.failure());
			return;
		}
		int maxAuthorizedStep = lookahead.maxAuthorizedStep();

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

		Failure upcomingFailure = ensureUpcomingSwitches(at, maxAuthorizedStep);
		if (upcomingFailure != null) {
			abort(at, tick, upcomingFailure);
			return;
		}
		Failure currentFailure = ensureStepShape(at, current, true);
		if (currentFailure != null) {
			abort(at, tick, currentFailure);
			return;
		}
		if (next != null && at.stepIndex + 1 <= maxAuthorizedStep) {
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
		int lastStep = at.plan.steps().size() - 1;
		double remaining = remaining(at, cart);
		double authorizedRemaining = maxAuthorizedStep < lastStep
				? remainingToStep(at, cart, maxAuthorizedStep) : remaining;
		if (maxAuthorizedStep < lastStep && authorizedRemaining <= STOP_DISTANCE) {
			cart.setDeltaMovement(Vec3.ZERO);
			at.speed = 0;
			at.lastProgressTick = tick;
			if ( lookahead.blockedKey() != null ) {
				int eta = estimateExclusiveEtaSeconds(at, lookahead.blockedKey(), tick);
				if (eta != at.lastWaitEtaSeconds || tick - at.lastWaitStatusTick >= 20) {
					at.lastWaitEtaSeconds = eta;
					at.lastWaitStatusTick = tick;
					Messages.actionBar(player, "actionbar.holding_ahead", eta);
				}
			}
			return;
		}
		if (tick - at.lastProgressTick > 40 && remaining > STOP_DISTANCE) {
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "cart made no progress"));
			return;
		}

		if (at.stepIndex == lastStep && maxAuthorizedStep == lastStep) {
			if (remaining < -0.3) {
				abort(at, tick, Failure.detail(FailureCode.INTERNAL, "passed destination dock"));
				return;
			}
			if (remaining <= STOP_DISTANCE && at.speed <= STOP_SPEED) {
				stopAtDock(at);
				at.trip.transitionTo(TripPhase.ARRIVING, tick);
				EventLog.event("trip.arriving")
						.tick(tick)
						.trip(at.trip.id())
						.field("player", player.getPlainTextName())
						.field("dock", at.plan.toDock())
						.info(logger);
				finishArrival(at, player, tick);
				return;
			}
		}

		double limit = speedLimit(at, current);
		if (next != null) {
			limit = Math.min(limit, speedLimit(at, next));
		}
		double target = Math.min(at.profile.targetSpeed(authorizedRemaining), limit);
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

	private record LookaheadResult(int maxAuthorizedStep, String blockedKey, Failure failure) {
	}

	private Failure tryAcquireInitialResources(ActiveTrip at) {
		List<ReservationManager.CorridorRequest> corridorRequests = new ArrayList<>(at.schedule.corridors().size());
		for (RouteResourcePlanner.CorridorSpan corridor : at.schedule.corridors()) {
			if (reservations.isQuarantined(corridor.key())) {
				return Failure.detail(FailureCode.RESERVATION_CONFLICT, corridor.key());
			}
			corridorRequests.add(new ReservationManager.CorridorRequest(corridor.key(), corridor.direction()));
		}
		List<String> initialExclusive = new ArrayList<>();
		for (RouteResourcePlanner.ExclusiveSpan span : at.schedule.exclusiveSpans()) {
			if (span.startStep() > at.initialLookaheadSteps) {
				break;
			}
			if (reservations.isQuarantined(span.key())) {
				return Failure.detail(FailureCode.RESERVATION_CONFLICT, span.key());
			}
			initialExclusive.add(span.key());
		}
		if (reservations.tryAcquire(initialExclusive, corridorRequests, at.trip.id().value())) {
			at.heldExclusive.addAll(initialExclusive);
			for (ReservationManager.CorridorRequest req : corridorRequests) {
				at.heldCorridors.add(req.resource());
			}
			at.initialResourcesAcquired = true;
		}
		return null;
	}

	private void releasePassedResources(ActiveTrip at) {
		UUID tripUuid = at.trip.id().value();
		for (String key : List.copyOf(at.heldExclusive)) {
			boolean stillNeeded = false;
			for (RouteResourcePlanner.ExclusiveSpan span : at.schedule.exclusiveSpans()) {
				if (span.key().equals(key) && at.stepIndex <= span.endStep()) {
					stillNeeded = true;
					break;
				}
			}
			if (!stillNeeded) {
				reservations.release(key, tripUuid);
				at.heldExclusive.remove(key);
			}
		}
		for (String key : List.copyOf(at.heldCorridors)) {
			boolean stillNeeded = false;
			for (RouteResourcePlanner.CorridorSpan corridor : at.schedule.corridors()) {
				if (corridor.key().equals(key) && at.stepIndex <= corridor.endStep()) {
					stillNeeded = true;
					break;
				}
			}
			if (!stillNeeded) {
				reservations.release(key, tripUuid);
				at.heldCorridors.remove(key);
			}
		}
	}

	private LookaheadResult acquireLookaheadResources(ActiveTrip at) {
		int lookaheadSteps = Math.max(at.segmentLength,
				(int) Math.ceil(at.profile.brakingDistance(at.speed) + at.safetyMargin) + 2);
		int maxStep = at.plan.steps().size() - 1;
		String blockedKey = null;
		UUID tripUuid = at.trip.id().value();
		for (RouteResourcePlanner.ExclusiveSpan span : at.schedule.exclusiveSpans()) {
			if (span.endStep() < at.stepIndex) {
				continue;
			}
			if (span.startStep() > at.stepIndex + lookaheadSteps) {
				break;
			}
			if (at.heldExclusive.contains(span.key())) {
				continue;
			}
			if (reservations.isQuarantined(span.key())) {
				return new LookaheadResult(at.stepIndex, span.key(),
						Failure.detail(FailureCode.RESERVATION_CONFLICT, span.key()));
			}
			if (reservations.tryAcquire(List.of(span.key()), tripUuid)) {
				at.heldExclusive.add(span.key());
			} else {
				maxStep = Math.min(maxStep, Math.max(at.stepIndex, span.startStep() - 1));
				blockedKey = span.key();
				break;
			}
		}
		return new LookaheadResult(maxStep, blockedKey, null);
	}

	private void updateBoardingWaitMessage(ActiveTrip at, ServerPlayer player, long tick, boolean force) {
		UUID selfUuid = at.trip.id().value();
		for (RouteResourcePlanner.CorridorSpan corridor : at.schedule.corridors()) {
			if (!reservations.isCorridorCompatible(corridor.key(), corridor.direction(), selfUuid)) {
				int eta = estimateCorridorEtaSeconds(corridor.key(), tick);
				if (force || eta != at.lastWaitEtaSeconds || tick - at.lastWaitStatusTick >= 20) {
					at.lastWaitEtaSeconds = eta;
					at.lastWaitStatusTick = tick;
					Messages.actionBar(player, "actionbar.waiting_oncoming", eta);
				}
				return;
			}
		}
		for (RouteResourcePlanner.ExclusiveSpan span : at.schedule.exclusiveSpans()) {
			if (span.startStep() > at.initialLookaheadSteps) {
				break;
			}
			if (!reservations.canAcquireExclusive(span.key(), selfUuid)) {
				int eta = estimateExclusiveEtaSeconds(at, span.key(), tick);
				if (force || eta != at.lastWaitEtaSeconds || tick - at.lastWaitStatusTick >= 20) {
					at.lastWaitEtaSeconds = eta;
					at.lastWaitStatusTick = tick;
					Messages.actionBar(player, "actionbar.waiting_ahead", eta);
				}
				return;
			}
		}
	}

	private int estimateCorridorEtaSeconds(String corridorKey, long tick) {
		int maxSeconds = 1;
		for (UUID ownerUuid : reservations.corridorOwners(corridorKey)) {
			ActiveTrip blocker = active.get(new TripId(ownerUuid));
			if (blocker == null) {
				continue;
			}
			int clearStep = blocker.plan.steps().size() - 1;
			for (RouteResourcePlanner.CorridorSpan c : blocker.schedule.corridors()) {
				if (c.key().equals(corridorKey)) {
					clearStep = Math.max(clearStep, c.endStep() + 1);
				}
			}
			maxSeconds = Math.max(maxSeconds, estimateStepsEtaSeconds(blocker, clearStep, tick));
		}
		return maxSeconds;
	}

	private int estimateExclusiveEtaSeconds(ActiveTrip waiter, String resourceKey, long tick) {
		UUID ownerUuid = reservations.ownerOf(resourceKey);
		if (ownerUuid == null) {
			return 1;
		}
		ActiveTrip blocker = active.get(new TripId(ownerUuid));
		if (blocker == null) {
			return 1;
		}
		int clearStep = blocker.stepIndex + 1;
		for (RouteResourcePlanner.ExclusiveSpan span : blocker.schedule.exclusiveSpans()) {
			if (span.key().equals(resourceKey) && span.endStep() >= blocker.stepIndex) {
				clearStep = span.endStep() + 1;
				break;
			}
		}
		return estimateStepsEtaSeconds(blocker, clearStep, tick);
	}

	private static int estimateStepsEtaSeconds(ActiveTrip blocker, int clearStep, long tick) {
		int remainingSteps = Math.max(1, clearStep - blocker.stepIndex);
		double effectiveSpeed = blocker.speed > 1.0
				? blocker.speed
				: Math.max(4.0, blocker.profile.maxSpeed() * 0.7);
		double travelSeconds = remainingSteps / effectiveSpeed;
		if (blocker.trip.phase() == TripPhase.BOARDING && blocker.departureTick > tick) {
			travelSeconds += (blocker.departureTick - tick) / 20.0;
		}
		return Math.max(1, (int) Math.ceil(travelSeconds));
	}

	private boolean ownsSwitchZone(ActiveTrip at, BlockLocation switchPos) {
		return at.heldExclusive.contains(RouteResourcePlanner.switchZoneKey(at.plan.dimension(), switchPos));
	}

	private Failure ensureUpcomingSwitches(ActiveTrip at, int maxAuthorizedStep) {
		int lookahead = (int) Math.ceil(at.profile.brakingDistance(at.speed) + at.safetyMargin) + 1;
		int last = Math.min(maxAuthorizedStep,
				Math.min(at.plan.steps().size() - 1, at.stepIndex + lookahead));
		for (int i = at.stepIndex; i <= last; i++) {
			RouteStep step = at.plan.steps().get(i);
			RailShapeType required = at.requiredSwitches.get(step.pos());
			if (required == null || !at.world.isLoaded(step.pos()) || !ownsSwitchZone(at, step.pos())) {
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
	 * Prepares a turnout the first time its chunk is loaded and owned, then keeps
	 * checking it so an external shape change still aborts the trip.
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

	/** Applies every owned switch whose chunk is loaded now; the rest wait for ownership/chunk. */
	private Failure prepareLoadedSwitches(ActiveTrip at) {
		for (SwitchRequirement requirement : LaunchCheck.loadedSwitches(at.plan, at.world::isLoaded)) {
			if (!ownsSwitchZone(at, requirement.pos())) {
				continue;
			}
			Failure failure = ensureSwitch(at, requirement.pos(), requirement.requiredShape());
			if (failure != null) {
				return failure;
			}
		}
		return null;
	}

	private Failure ensureStepShape(ActiveTrip at, RouteStep step, boolean current) {
		if (!current && at.requiredSwitches.containsKey(step.pos()) && !ownsSwitchZone(at, step.pos())) {
			return null;
		}
		RailShapeType required = at.requiredSwitches.getOrDefault(step.pos(), step.shape());
		RailShapeType actual = at.world.shapeAt(step.pos());
		if (actual == required) {
			return null;
		}
		// Turnouts are set before entry. Any later change is external and must
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
		return remainingToStep(at, cart, at.plan.steps().size() - 1);
	}

	private double remainingToStep(ActiveTrip at, Minecart cart, int targetStep) {
		int clampedTarget = Math.min(at.plan.steps().size() - 1, Math.max(0, targetStep));
		BlockLocation targetPos = at.plan.steps().get(clampedTarget).pos();
		if (at.stepIndex >= clampedTarget) {
			return horizontalDistance(cart.getX(), cart.getZ(), targetPos.x() + 0.5,
					targetPos.z() + 0.5);
		}
		RouteStep next = at.plan.steps().get(at.stepIndex + 1);
		double distanceToNext = horizontalDistance(cart.getX(), cart.getZ(),
				next.pos().x() + 0.5, next.pos().z() + 0.5);
		double transitions = clampedTarget - at.stepIndex;
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
			logDetach(at, player, tick);
			discardAndRemove(at);
			notifyAborted(player);
			return;
		}
		BlockLocation exit = at.arrivalExit;
		Station station = railway.state().station(plan.to()).orElse(null);
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

		PlayerActions.dismountTo(player, at.world.level(), target, at.arrivalYaw);
		boolean removed = PlayerActions.discardCart(at.cart);
		at.trip.transitionTo(TripPhase.COMPLETED, tick);
		EventLog.event("trip.complete")
				.tick(tick)
				.trip(at.trip.id())
				.field("player", player.getPlainTextName())
				.field("from", plan.from().value())
				.field("fromName", stationName(plan.from()))
				.field("to", plan.to().value())
				.field("toName", stationName(plan.to()))
				.field("dim", plan.dimension())
				.field("durationTicks", tick - at.trip.startedTick())
				.field("dock", plan.toDock())
				.field("exit", at.arrivalExit)
				.field("cartRemoved", Boolean.toString(removed))
				.info(logger);
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
			abort(at, tick, Failure.at(code, target));
			return;
		}
		warnPeriodically(at, player, tick,
				code == FailureCode.CHUNKS_NOT_READY ? "error.chunks_not_ready" : "error.exit_unsafe");
	}

	private void retrySafeExit(ActiveTrip at, ServerPlayer player, long tick) {
		if (player.getVehicle() != at.cart) {
			logDetach(at, player, tick);
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
		GraphRoutePlan plan = at.plan;
		ServerPlayer player = railway.server().getPlayerList().getPlayer(trip.playerUuid());
		EventLog.event("trip.abort")
				.tick(tick)
				.trip(trip.id())
				.field("player", player == null ? trip.playerUuid().toString() : player.getPlainTextName())
				.field("playerId", trip.playerUuid())
				.phase(trip.phase())
				.field("from", plan.from().value())
				.field("fromName", stationName(plan.from()))
				.field("to", plan.to().value())
				.field("toName", stationName(plan.to()))
				.field("dim", plan.dimension())
				.field("steps", plan.steps().size())
				.field("cart", RailWorld.loc(at.cart.blockPosition()))
				.field("speed", String.format(Locale.ROOT, "%.2f", at.speed))
				.failure(failure)
				.warn(logger);
		at.cart.setDeltaMovement(Vec3.ZERO);
		at.speed = 0;

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
