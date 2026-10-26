package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.dispatch.ReservationManager;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.RailAxis;
import dev.nikhcevov.railway.domain.RoutePlan;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.Trip;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;
import dev.nikhcevov.railway.motion.MotionProfile;
import dev.nikhcevov.railway.network.LineScan;
import dev.nikhcevov.railway.network.RailKind;
import dev.nikhcevov.railway.network.RailProbe;
import dev.nikhcevov.railway.network.RoutePlanner;
import dev.nikhcevov.railway.persistence.Config;
import dev.nikhcevov.railway.persistence.TripRecord;
import dev.nikhcevov.railway.persistence.WorldState;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Launch, motion control, arrival, abort and restart recovery. All state is touched
 * on the server thread; the vanilla minecart remains the moving entity.
 */
public final class TripService {
	private static final double STOP_DISTANCE = 0.25;
	private static final double STOP_SPEED = 1.0;
	private static final double FINAL_SPEED = 0.1;
	private static final long ORPHAN_SWEEP_INTERVAL = 100;
	private static final long WARNING_INTERVAL = 100;

	private final Railway railway;
	private final Logger logger;
	private final Map<TripId, ActiveTrip> active = new LinkedHashMap<>();
	private final Map<UUID, TripId> byPlayer = new HashMap<>();
	private final Map<UUID, TripId> byCart = new HashMap<>();
	private final Map<UUID, Long> cooldowns = new HashMap<>();
	private final ReservationManager reservations = new ReservationManager();

	private static final class ActiveTrip {
		final Trip trip;
		final Minecart cart;
		final RailWorld world;
		final MotionProfile profile;
		final double safetyMargin;
		final int maxTripTicks;
		final long departureTick;
		double speed;
		long lastWarningTick;
		long lastSafeRetryTick;
		long lastProgressTick;
		double lastX;
		double lastZ;
		boolean pathWarningSent;

		ActiveTrip(Trip trip, Minecart cart, RailWorld world, MotionProfile profile, double safetyMargin,
				int maxTripTicks, long departureTick) {
			this.trip = trip;
			this.cart = cart;
			this.world = world;
			this.profile = profile;
			this.safetyMargin = safetyMargin;
			this.maxTripTicks = maxTripTicks;
			this.departureTick = departureTick;
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

	public TripService(Railway railway, Logger logger) {
		this.railway = railway;
		this.logger = logger;
	}

	// ------------------------------------------------------------------ launch

	public StartResult start(ServerPlayer player, Binding binding) {
		MinecraftServer server = railway.server();
		long tick = server.getTickCount();
		Config config = railway.config();
		WorldState state = railway.state();

		if (railway.disabled()) {
			return reject(FailureCode.DISABLED, railway.disabledReason());
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

		Optional<Station> fromOpt = state.station(binding.fromStationId());
		Optional<Station> toOpt = state.station(binding.toStationId());
		if (fromOpt.isEmpty()) {
			return reject(FailureCode.STATION_NOT_FOUND, binding.fromStationId().value());
		}
		if (toOpt.isEmpty()) {
			return reject(FailureCode.STATION_NOT_FOUND, binding.toStationId().value());
		}
		Station from = fromOpt.get();
		Station to = toOpt.get();
		if (!from.enabled()) {
			return reject(FailureCode.STATION_DISABLED, from.id().value());
		}
		if (!to.enabled()) {
			return reject(FailureCode.STATION_DISABLED, to.id().value());
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
		if (!PlayerActions.withinRadius(player, from.dock(), config.boardingRadius())) {
			return reject(FailureCode.PLAYER_TOO_FAR, Integer.toString(config.boardingRadius()));
		}

		LineScan fromLine = railway.scans().line(from.id());
		if (fromLine == null) {
			if (railway.scans().isRunning(from.id())) {
				return reject(FailureCode.SCAN_RUNNING, from.id().value());
			}
			Failure scanFailure = railway.scans().failure(from.id());
			return reject(scanFailure != null ? scanFailure
					: Failure.detail(FailureCode.SCAN_INCOMPLETE, from.id().value()));
		}
		LineScan toLine = railway.scans().line(to.id());
		if (toLine == null) {
			if (railway.scans().isRunning(to.id())) {
				return reject(FailureCode.SCAN_RUNNING, to.id().value());
			}
			Failure scanFailure = railway.scans().failure(to.id());
			return reject(scanFailure != null ? scanFailure
					: Failure.detail(FailureCode.SCAN_INCOMPLETE, to.id().value()));
		}

		RoutePlanner.Result planned = RoutePlanner.plan(from, to, fromLine, toLine, config.maxRouteLength());
		if (planned instanceof RoutePlanner.Result.Rejected rejected) {
			return reject(rejected.failure());
		}
		RoutePlan plan = ((RoutePlanner.Result.Planned) planned).plan();

		Failure routeFailure = world.validateRoute(plan);
		if (routeFailure != null) {
			return reject(routeFailure);
		}
		if (!world.isDockFree(plan.fromDock(), player.getUUID())) {
			return reject(FailureCode.DOCK_OCCUPIED, from.id().value());
		}
		if (!world.isSafeStand(plan.exit())) {
			return reject(Failure.at(FailureCode.EXIT_UNSAFE, plan.exit()));
		}
		if (world.isPathOccupied(plan, player.getUUID(), new UUID(0L, 0L))) {
			return reject(FailureCode.PATH_OCCUPIED, null);
		}

		TripId tripId = TripId.random();
		if (!reservations.tryAcquire(List.of(plan.componentKey()), tripId.value())) {
			return reject(FailureCode.RESERVATION_CONFLICT, plan.componentKey());
		}

		Minecart cart = null;
		try {
			cart = PlayerActions.spawnServiceCart(level, plan.fromDock(), tripId);
			if (cart == null) {
				reservations.release(plan.componentKey(), tripId.value());
				return reject(FailureCode.CANNOT_PLACE_CART, null);
			}
			if (!PlayerActions.mount(player, cart)) {
				PlayerActions.discardCart(cart);
				reservations.release(plan.componentKey(), tripId.value());
				return reject(FailureCode.MOUNT_FAILED, null);
			}

			Trip trip = new Trip(tripId, player.getUUID(), cart.getUUID(), plan, tick);
			trip.transitionTo(TripPhase.BOARDING, tick);
			MotionProfile profile = new MotionProfile(
					config.effectiveStage1Speed(),
					config.acceleration(),
					config.braking(),
					0.1
			);
			ActiveTrip activeTrip = new ActiveTrip(trip, cart, world, profile, config.safetyMargin(),
					config.maxTripTicks(), tick + config.boardingDelayTicks());
			active.put(tripId, activeTrip);
			byPlayer.put(player.getUUID(), tripId);
			byCart.put(cart.getUUID(), tripId);
			cooldowns.put(player.getUUID(), tick);
			state.addTrip(new TripRecord(tripId, player.getUUID(), cart.getUUID(),
					plan.from(), plan.to(), TripPhase.BOARDING, tick));
			railway.saveState();
			Messages.actionBar(player, "actionbar.boarding");
			return new StartResult.Started(tripId);
		} catch (RuntimeException e) {
			logger.error("Failed to start trip", e);
			if (cart != null) {
				PlayerActions.discardCart(cart);
			}
			reservations.release(plan.componentKey(), tripId.value());
			return reject(FailureCode.INTERNAL, e.getMessage());
		}
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
		}
		cooldowns.entrySet().removeIf(entry -> tick - entry.getValue() > 10 * 60 * 20L);
		if (tick - lastSweepTick >= ORPHAN_SWEEP_INTERVAL) {
			lastSweepTick = tick;
			sweepOrphans(server);
		}
	}

	private long lastSweepTick;

	private void tickOne(MinecraftServer server, ActiveTrip at, long tick) {
		Trip trip = at.trip;
		ServerPlayer player = server.getPlayerList().getPlayer(trip.playerUuid());
		Minecart cart = at.cart;

		if (player == null) {
			logger.info("Trip {} ended: player offline", trip.id());
			releaseAndRemove(at);
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
			// Vanilla dismount (Shift) or an external ejection: keep the player where
			// vanilla put them, remove the now-empty service cart and free resources.
			PlayerActions.discardCart(cart);
			releaseAndRemove(at);
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
				if (tick >= at.departureTick) {
					Failure ready = at.world.validateRoute(trip.plan());
					if (ready != null) {
						abort(at, tick, ready);
						return;
					}
					trip.transitionTo(TripPhase.RUNNING, tick);
					Station to = railway.state().station(trip.plan().to()).orElse(null);
					Messages.actionBar(player, "actionbar.departing", to == null ? trip.plan().to().value() : to.displayName());
				}
			}
			case RUNNING -> tickRunning(at, player, tick);
			case ARRIVING -> finishArrival(at, player, tick);
			case ABORTING -> retrySafeExit(at, player, tick);
			case PREPARING, COMPLETED, ABORTED, RECOVERING -> {
			}
		}
	}

	private void tickRunning(ActiveTrip at, ServerPlayer player, long tick) {
		Trip trip = at.trip;
		Minecart cart = at.cart;
		RailProbe under = at.world.probe(RailWorld.loc(cart.blockPosition()));
		if (!(under instanceof RailProbe.Rail rail) || !rail.info().straight() || rail.info().slope()
				|| rail.info().axis() != trip.plan().axis()) {
			abort(at, tick, Failure.at(FailureCode.SCAN_INCOMPLETE, RailWorld.loc(cart.blockPosition())));
			return;
		}
		double moved = Math.abs(cart.getX() - at.lastX) + Math.abs(cart.getZ() - at.lastZ);
		if (moved > 0.005) {
			at.lastProgressTick = tick;
			at.lastX = cart.getX();
			at.lastZ = cart.getZ();
		}
		double remaining = remaining(at);
		if (tick - at.lastProgressTick > 40 && remaining > STOP_DISTANCE) {
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "cart made no progress"));
			return;
		}
		if (remaining < -0.3) {
			abort(at, tick, Failure.detail(FailureCode.INTERNAL, "passed destination dock"));
			return;
		}

		if (remaining <= STOP_DISTANCE && at.speed <= STOP_SPEED) {
			stopAtDock(at);
			trip.transitionTo(TripPhase.ARRIVING, tick);
			finishArrival(at, player, tick);
			return;
		}

		int lookahead = Math.max(2, Math.min(32,
				(int) Math.ceil(at.profile.brakingDistance(at.speed) + at.safetyMargin)));
		AheadCheck ahead = checkAhead(at, lookahead);
		double target;
		if (ahead.failure() != null) {
			if (!at.pathWarningSent) {
				at.pathWarningSent = true;
				Messages.actionBar(player, "actionbar.path_changed");
			}
			target = Math.min(at.profile.targetSpeed(remaining), at.profile.targetSpeed(Math.max(0, ahead.distance() - 0.5)));
			if (ahead.distance() <= 0.5 || at.speed <= FINAL_SPEED) {
				abort(at, tick, ahead.failure());
				return;
			}
		} else if (at.world.isPathBlockedAhead(trip.plan(), currentCoordinate(at),
				at.profile.brakingDistance(at.speed) + at.safetyMargin, cart.getUUID(), player.getUUID())) {
			target = 0;
			if (at.speed < FINAL_SPEED) {
				abort(at, tick, Failure.detail(FailureCode.PATH_OCCUPIED, null));
				return;
			}
		} else {
			target = at.profile.targetSpeed(remaining);
		}

		at.speed = at.profile.step(at.speed, target);
		applyVelocity(at);
	}

	private record AheadCheck(Failure failure, double distance) {
	}

	/** Looks for a broken or unsupported rail within the stop distance. */
	private AheadCheck checkAhead(ActiveTrip at, int lookahead) {
		RoutePlan plan = at.trip.plan();
		double current = currentCoordinate(at);
		int currentBlock = (int) Math.floor(current);
		int targetBlock = plan.axis() == RailAxis.X ? plan.toDock().x() : plan.toDock().z();
		int sign = plan.departureSign();
		for (int block = 1; block <= lookahead; block++) {
			int coord = currentBlock + sign * block;
			if ((coord - targetBlock) * sign > 0) {
				break;
			}
			BlockLocation location = plan.axis() == RailAxis.X
					? new BlockLocation(coord, plan.fromDock().y(), plan.fromDock().z())
					: new BlockLocation(plan.fromDock().x(), plan.fromDock().y(), coord);
			RailProbe probe = at.world.probe(location);
			boolean broken = !(probe instanceof RailProbe.Rail rail)
					|| !rail.info().straight()
					|| rail.info().slope()
					|| rail.info().axis() != plan.axis()
					|| rail.info().kind() == RailKind.DETECTOR
					|| rail.info().kind() == RailKind.ACTIVATOR;
			if (broken) {
				double distance = Math.abs(coord + 0.5 - current);
				return new AheadCheck(Failure.at(FailureCode.SCAN_INCOMPLETE, location), distance);
			}
		}
		return new AheadCheck(null, Double.MAX_VALUE);
	}

	private void applyVelocity(ActiveTrip at) {
		RoutePlan plan = at.trip.plan();
		double perTick = at.speed * MotionProfile.TICK_SECONDS;
		int sign = plan.departureSign();
		if (plan.axis() == RailAxis.X) {
			at.cart.setDeltaMovement(sign * perTick, 0.0, 0.0);
		} else {
			at.cart.setDeltaMovement(0.0, 0.0, sign * perTick);
		}
	}

	private void stopAtDock(ActiveTrip at) {
		RoutePlan plan = at.trip.plan();
		at.cart.setDeltaMovement(Vec3.ZERO);
		at.speed = 0;
		if (plan.axis() == RailAxis.X) {
			at.cart.setPos(plan.toDock().x() + 0.5, at.cart.getY(), at.cart.getZ());
		} else {
			at.cart.setPos(at.cart.getX(), at.cart.getY(), plan.toDock().z() + 0.5);
		}
	}

	private void finishArrival(ActiveTrip at, ServerPlayer player, long tick) {
		RoutePlan plan = at.trip.plan();
		if (player.getVehicle() != at.cart) {
			PlayerActions.discardCart(at.cart);
			releaseAndRemove(at);
			notifyAborted(player);
			return;
		}
		BlockLocation exit = plan.exit();
		BlockLocation target;
		if (at.world.isSafeStand(exit)) {
			target = exit;
		} else {
			Optional<BlockLocation> safe = at.world.findSafeSpot(exit, 3);
			if (safe.isEmpty()) {
				warnPeriodically(at, player, tick, "error.exit_unsafe");
				return;
			}
			target = safe.get();
			if ((tick - at.lastWarningTick) >= WARNING_INTERVAL) {
				at.lastWarningTick = tick;
				player.sendSystemMessage(Messages.failure(player, Failure.at(FailureCode.EXIT_UNSAFE, exit)));
			}
		}

		PlayerActions.dismountTo(player, at.world.level(), target, plan.exitYaw());
		PlayerActions.discardCart(at.cart);
		at.trip.transitionTo(TripPhase.COMPLETED, tick);
		removeTrip(at);
		Station station = railway.state().station(plan.to()).orElse(null);
		Messages.actionBar(player, "actionbar.arrived", station == null ? plan.to().value() : station.displayName());
	}

	private void retrySafeExit(ActiveTrip at, ServerPlayer player, long tick) {
		if (player.getVehicle() != at.cart) {
			PlayerActions.discardCart(at.cart);
			releaseAndRemove(at);
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
			PlayerActions.dismountTo(player, at.world.level(), safe.get(), at.trip.plan().exitYaw());
			PlayerActions.discardCart(at.cart);
			abortComplete(at, tick);
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
			PlayerActions.dismountTo(player, at.world.level(), safe.get(), at.trip.plan().exitYaw());
			PlayerActions.discardCart(at.cart);
			abortComplete(at, tick);
			if (notifyFailure) {
				notifyTrip(trip, player, failure);
			} else {
				Messages.actionBar(player, "actionbar.aborted");
			}
			return;
		}

		PlayerActions.discardCart(at.cart);
		abortComplete(at, tick);
		if (player != null) {
			if (notifyFailure) {
				notifyTrip(trip, player, failure);
			} else {
				Messages.actionBar(player, "actionbar.aborted");
			}
		}
	}

	private void abortComplete(ActiveTrip at, long tick) {
		if (at.trip.phase() != TripPhase.ABORTING) {
			at.trip.transitionTo(TripPhase.ABORTING, tick);
		}
		at.trip.transitionTo(TripPhase.ABORTED, tick);
		removeTrip(at);
	}

	private void removeTrip(ActiveTrip at) {
		active.remove(at.trip.id());
		byPlayer.remove(at.trip.playerUuid(), at.trip.id());
		byCart.remove(at.cart.getUUID(), at.trip.id());
		reservations.releaseAll(at.trip.id().value());
		railway.state().removeTrip(at.trip.id());
		railway.saveState();
	}

	/** Releases runtime state without inventing a safe exit; used when the player is gone. */
	private void releaseAndRemove(ActiveTrip at) {
		active.remove(at.trip.id());
		byPlayer.remove(at.trip.playerUuid());
		byCart.remove(at.cart.getUUID());
		reservations.releaseAll(at.trip.id().value());
		railway.state().removeTrip(at.trip.id());
		railway.saveState();
	}

	private void notifyTrip(Trip trip, ServerPlayer player, Failure failure) {
		player.sendSystemMessage(Messages.failure(player, failure));
		Messages.actionBar(player, "actionbar.aborted");
	}

	private void notifyAborted(ServerPlayer player) {
		Messages.actionBar(player, "actionbar.aborted");
	}

	private double remaining(ActiveTrip at) {
		RoutePlan plan = at.trip.plan();
		double target = plan.axis() == RailAxis.X ? plan.toDock().x() + 0.5 : plan.toDock().z() + 0.5;
		double current = currentCoordinate(at);
		return (target - current) * plan.departureSign();
	}

	private double currentCoordinate(ActiveTrip at) {
		RoutePlan plan = at.trip.plan();
		return plan.axis() == RailAxis.X ? at.cart.getX() : at.cart.getZ();
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
		PlayerActions.discardCart(at.cart);
		releaseAndRemove(at);
	}

	public boolean hasActiveTrip(UUID playerUuid) {
		return byPlayer.containsKey(playerUuid);
	}

	public boolean stationInUse(StationId id) {
		for (ActiveTrip at : active.values()) {
			RoutePlan plan = at.trip.plan();
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

	// --------------------------------------------------------------- recovery

	public void shutdown() {
		for (ActiveTrip at : List.copyOf(active.values())) {
			PlayerActions.discardCart(at.cart);
			reservations.releaseAll(at.trip.id().value());
			railway.state().removeTrip(at.trip.id());
		}
		active.clear();
		byPlayer.clear();
		byCart.clear();
		cooldowns.clear();
		railway.saveState();
	}

	private void sweepOrphans(MinecraftServer server) {
		EntityTypeTest<Entity, Minecart> minecartType = EntityTypeTest.forClass(Minecart.class);
		for (ServerLevel level : server.getAllLevels()) {
			List<Minecart> carts = level.getEntities(minecartType, RailWorld.allEntities(), cart ->
					cart.entityTags().contains(PlayerActions.MANAGED_TAG) && !byCart.containsKey(cart.getUUID()));
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
