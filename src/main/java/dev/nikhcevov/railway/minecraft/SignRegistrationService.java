package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.StationNames;
import dev.nikhcevov.railway.logging.EventLog;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.persistence.WorldState;
import dev.nikhcevov.railway.sign.BlockSample;
import dev.nikhcevov.railway.sign.DockResolution;
import dev.nikhcevov.railway.sign.HorizontalAxis;
import dev.nikhcevov.railway.sign.PillarError;
import dev.nikhcevov.railway.sign.PillarGeometry;
import dev.nikhcevov.railway.sign.PillarUnit;
import dev.nikhcevov.railway.sign.PillarValidation;
import dev.nikhcevov.railway.sign.SignBlockView;
import dev.nikhcevov.railway.sign.SignFormat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Registers service signs and maintains their markers and bindings. Every method is
 * called on the server thread.
 *
 * <p>One physical pillar carries one wall sign and, for a destination unit, one stone
 * button. The edited sign side is the registration side: an existing marker may only be
 * updated from that side, and all rejections restore the previous text when a marker
 * already exists for the sign.
 */
public final class SignRegistrationService {
	private static final Direction[] HORIZONTAL = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};
	private static final int SUPPORT_SCAN_DEPTH = 4;
	private static final int EXIT_RADIUS = 4;
	private static final int EXIT_VERTICAL_RANGE = 3;
	/** A new unit must chain to an existing unit within this many horizontal blocks. */
	private static final int MAX_CHAIN_STEP = 4;
	/** A station may hold at most this many sign units. */
	private static final int MAX_SIGN_UNITS = 16;
	/** A station's sign units may span at most this many horizontal blocks from main. */
	private static final int MAX_CHAIN_SPAN = 64;

	private final Railway railway;
	private final Logger logger;

	public SignRegistrationService(Railway railway, Logger logger) {
		this.railway = railway;
		this.logger = logger;
	}

	public sealed interface Result {
		record Registered(Station from, Station to, Binding binding, SignMarker marker) implements Result {
		}

		record Rejected(Failure failure) implements Result {
		}

		record Ignored() implements Result {
		}
	}

	// ------------------------------------------------------------ registration

	public Result register(ServerPlayer player, SignBlockEntity sign, boolean front, SignText previous) {
		if (railway.disabled() || !(sign.getLevel() instanceof ServerLevel level)) {
			return new Result.Ignored();
		}
		WorldState state = railway.state();
		String dimension = RailWorld.dimension(level);
		RailWorld railWorld = new RailWorld(level);
		BlockLocation signLocation = RailWorld.loc(sign.getBlockPos());
		SignMarker marker = state.signMarker(dimension, signLocation).orElse(null);
		BlockState blockState = level.getBlockState(sign.getBlockPos());

		if (marker != null && marker.editedFront() != front) {
			return reject(player, sign, front, previous, marker, Failure.of(FailureCode.SIGN_SIDE));
		}
		List<String> lines = rawLines(sign, front);
		String markerLine = SignFormat.normalize(lines.get(0));
		if (marker == null && !SignFormat.isKnownMarkerLine(markerLine)) {
			// A brand-new ordinary sign (or a blank one) is not a Railway sign. An
			// unregistered sign that uses a known service marker still gets diagnostics.
			return new Result.Ignored();
		}
		if (markerLine.isEmpty()) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.SIGN_FORMAT, "blank sign"));
		}
		if (!(blockState.getBlock() instanceof WallSignBlock)) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.SIGN_GEOMETRY, "wall sign required"));
		}

		Direction facing = blockState.getValue(WallSignBlock.FACING);
		HorizontalAxis axis = facing.getAxis() == Direction.Axis.X ? HorizontalAxis.X : HorizontalAxis.Z;
		BlockLocation support = RailWorld.loc(sign.getBlockPos().relative(facing.getOpposite()));

		SignFormat.Result parsedResult = SignFormat.parse(lines);
		if (parsedResult instanceof SignFormat.Rejected rejected) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.SIGN_FORMAT, rejected.error().name()));
		}
		SignFormat.Parsed parsed = (SignFormat.Parsed) parsedResult;
		String fromName = StationNames.normalize(parsed.from());
		String toName = StationNames.normalize(parsed.to());
		if (fromName.isEmpty()) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.SIGN_FORMAT, "missing source name"));
		}
		boolean stationOnly = toName.isEmpty();

		List<Station> fromMatches = state.stationsNamed(dimension, fromName);
		if (fromMatches.size() > 1) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.STATION_NAME_AMBIGUOUS, fromName));
		}
		Station from = fromMatches.isEmpty() ? null : fromMatches.getFirst();
		boolean anchoredFrom = from != null && from.isAnchored();
		boolean anchorMode = !anchoredFrom;
		StationId fromId = from == null
				? StationNames.generatedId(dimension, fromName, state.stations())
				: from.id();

		if (!stationOnly && StationNames.lookupKey(dimension, fromName)
				.equals(StationNames.lookupKey(dimension, toName))) {
			return reject(player, sign, front, previous, marker, Failure.of(FailureCode.SIGN_SELF_BINDING));
		}

		if (stationOnly && anchoredFrom) {
			// An existing buttonless unit may be re-saved (including clearing m). A new
			// buttonless sign is only useful with m: it becomes the station main unit
			// without a route. A destination unit may never drop its destination.
			boolean unitResave = marker != null
					&& marker.button() == null
					&& from.id().equals(marker.ownerStationId());
			boolean newMainOnly = marker == null && parsed.main();
			if (!unitResave && !newMainOnly) {
				return reject(player, sign, front, previous, marker,
						Failure.detail(FailureCode.SIGN_FORMAT, "blank destination"));
			}
		}

		Station resolvedTo = null;
		boolean newTo = false;
		if (!stationOnly) {
			List<Station> toMatches = state.stationsNamed(dimension, toName);
			if (toMatches.size() > 1) {
				return reject(player, sign, front, previous, marker,
						Failure.detail(FailureCode.STATION_NAME_AMBIGUOUS, toName));
			}
			if (toMatches.isEmpty()) {
				StationId toId = StationNames.generatedId(dimension, toName, state.stations());
				resolvedTo = Station.provisional(toId, toName, dimension, 0);
				newTo = true;
			} else {
				resolvedTo = toMatches.getFirst();
			}
			if (resolvedTo.id().equals(fromId)) {
				return reject(player, sign, front, previous, marker, Failure.of(FailureCode.SIGN_SELF_BINDING));
			}
		}

		Failure identityFailure = markerIdentity(state, dimension, marker, fromId,
				resolvedTo == null ? null : resolvedTo.id());
		if (identityFailure != null) {
			return reject(player, sign, front, previous, marker, identityFailure);
		}

		// Every unit resolves its own local dock from its normal axis and rail. A
		// destination unit departs from this dock; the station main is untouched.
		DockResolution resolution = PillarGeometry.resolveDock(signLocation, axis, blockView(level));
		if (resolution instanceof DockResolution.Rejected rejected) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.SIGN_GEOMETRY, rejected.error().name()));
		}
		BlockLocation dock = ((DockResolution.Found) resolution).dock();

		// A station disabled by destroying its last sign has no units left. The
		// restoring sign then defines the station again, including its arrival point.
		boolean restoreDisabled = !anchorMode && !from.enabled()
				&& state.signMarkersForStation(from.id()).isEmpty();
		// A station disabled because its main unit disappeared can be re-enabled by
		// explicitly assigning a new main with the m flag. An admin-disabled station
		// still has its main marker, so this does not override that decision.
		boolean restoreMissingMain = !anchorMode && !from.enabled() && parsed.main()
				&& state.mainMarkerForStation(from.id()).isEmpty();
		// A unit that redefines the station main may live on a new component: the old
		// graph cannot contain its dock yet, and the post-registration rescan from the
		// new main is what makes it authoritative.
		boolean redefinesMain = parsed.main() || restoreDisabled || restoreMissingMain;

		if (!anchorMode && !redefinesMain) {
			Failure chainFailure = chainedUnitFailure(state, from, signLocation, support, dock, marker);
			if (chainFailure != null) {
				return reject(player, sign, front, previous, marker, chainFailure);
			}
		}

		if (countWallSigns(level, support, signLocation.y()) != 1) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.SIGN_GEOMETRY, "exactly one wall sign per pillar"));
		}

		List<BlockLocation> buttons = buttonsOnColumn(level, support, dock.y(), signLocation.y());
		BlockLocation button = null;
		if (stationOnly) {
			if (!buttons.isEmpty()) {
				return reject(player, sign, front, previous, marker,
						Failure.detail(FailureCode.SIGN_BUTTON, "a station-only anchor must not have a button"));
			}
		} else {
			if (buttons.isEmpty()) {
				return reject(player, sign, front, previous, marker,
						Failure.detail(FailureCode.SIGN_BUTTON, "missing stone button"));
			}
			if (buttons.size() > 1) {
				return reject(player, sign, front, previous, marker,
						Failure.detail(FailureCode.SIGN_BUTTON, "exactly one stone button per pillar"));
			}
			button = buttons.getFirst();
			if (marker != null && marker.button() != null && !marker.button().equals(button)) {
				return reject(player, sign, front, previous, marker, Failure.of(FailureCode.SIGN_LOCKED));
			}
		}

		PillarUnit unit = new PillarUnit(signLocation, support, axis, button);
		PillarValidation validation = validateUnit(unit, dock, stationOnly, level);
		if (validation instanceof PillarValidation.Invalid invalid) {
			return reject(player, sign, front, previous, marker,
					Failure.detail(failureCode(invalid.error()), invalid.error().name()));
		}

		Binding binding = null;
		Binding existingBinding = null;
		String buttonBlockId = "";
		if (!stationOnly) {
			buttonBlockId = railWorld.blockIdAt(button);
			binding = new Binding(dimension, button, buttonBlockId, fromId, resolvedTo.id(), dock);
			existingBinding = state.binding(dimension, button).orElse(null);
			// A legacy binding has a null source dock. Its identity is unchanged, so a
			// re-save may fill in the local dock instead of being rejected.
			if (existingBinding != null
					&& !existingBinding.withSourceDock(null).equals(binding.withSourceDock(null))) {
				return reject(player, sign, front, previous, marker,
						Failure.at(FailureCode.BUTTON_ALREADY_BOUND, button));
			}
			SignMarker owner = state.signMarkerForButton(dimension, button).orElse(null);
			if (owner != null && !owner.sign().equals(signLocation)) {
				return reject(player, sign, front, previous, marker,
						Failure.at(FailureCode.BUTTON_ALREADY_BOUND, button));
			}
			Failure dockOwnerFailure = sourceDockOwnerFailure(state, dimension, fromId, existingBinding, dock);
			if (dockOwnerFailure != null) {
				return reject(player, sign, front, previous, marker, dockOwnerFailure);
			}
		}

		StationId ownerId = anchorMode ? fromId : from.id();
		if (parsed.main()) {
			SignMarker currentMain = state.mainMarkerForStation(ownerId).orElse(null);
			if (currentMain != null && !currentMain.sign().equals(signLocation)) {
				return reject(player, sign, front, previous, marker,
						Failure.detail(FailureCode.SIGN_MAIN_CONFLICT,
								"clear the m flag on " + currentMain.sign().shortString() + " first"));
			}
		}

		BlockLocation exit = null;
		if (anchorMode || parsed.main() || restoreDisabled) {
			exit = selectExit(level, railWorld, player, dock);
			if (exit == null) {
				return reject(player, sign, front, previous, marker, Failure.at(FailureCode.EXIT_UNSAFE, dock));
			}
		}

		Station stationFrom;
		Station stationTo = null;
		boolean mainMoved = false;
		boolean reactivated = false;
		boolean bindingAddedOrChanged = binding != null
				&& (existingBinding == null || !existingBinding.equals(binding));
		try {
			if (anchorMode) {
				if (from == null) {
					stationFrom = new Station(fromId, fromName, dimension, dock, exit,
							player.getYRot(), true, state.nextRevision());
					state.addStation(stationFrom);
				} else {
					stationFrom = new Station(from.id(), from.displayName(), dimension, dock, exit,
							player.getYRot(), true, state.nextRevision(), from.arrivalPlatforms());
					state.updateStation(stationFrom);
				}
			} else {
				// Destroying the last sign disables the station but keeps its data. A
				// newly restored sign with the same station name re-enables it when no
				// other units remain; an admin-disabled station that still has signs is
				// left disabled until explicitly enabled. With no units left, the
				// restoring sign also becomes the station's arrival point.
				boolean moveMain = (parsed.main() || restoreDisabled)
						&& (!java.util.Objects.equals(from.dock(), dock)
						|| !java.util.Objects.equals(from.exit(), exit)
						|| Float.compare(from.exitYaw(), player.getYRot()) != 0);
				if (restoreDisabled || restoreMissingMain || moveMain) {
					long revision = state.nextRevision();
					Station updated = restoreDisabled || restoreMissingMain
							? from.withEnabled(true, revision) : from;
					if (moveMain) {
						updated = updated.withMain(dock, exit, player.getYRot(), revision);
					}
					stationFrom = updated;
					state.updateStation(updated);
					reactivated = restoreDisabled || restoreMissingMain;
					mainMoved = moveMain;
				} else {
					stationFrom = from;
				}
			}

			if (resolvedTo != null) {
				if (newTo) {
					stationTo = Station.provisional(resolvedTo.id(), resolvedTo.displayName(), dimension,
							state.nextRevision());
					state.addStation(stationTo);
				} else {
					stationTo = resolvedTo;
				}
			}

			if (binding != null) {
				if (existingBinding == null) {
					state.addBinding(binding);
				} else if (!existingBinding.equals(binding)) {
					// Legacy bindings gain their local dock on re-save.
					state.replaceBinding(binding);
				}
			}

			StationId anchorStationId = anchorMode
					? stationFrom.id()
					: marker == null ? null : marker.anchorStationId();
			StationId stationId = anchorMode ? stationFrom.id() : from.id();
			boolean mainFlag = parsed.main() || restoreDisabled;
			SignMarker desiredMarker = new SignMarker(dimension, signLocation, support, front, button,
					anchorStationId, stationId, mainFlag);
			state.updateSignMarker(desiredMarker);
			railway.saveState();

			if (anchorMode) {
				railway.scans().start(stationFrom, level, railway.config(),
						railway.server().getTickCount(), state.revision(), ScanTrigger.SIGN_ANCHOR);
				refreshConnectedScans(stationFrom, stationFrom.id());
			} else if (mainMoved || bindingAddedOrChanged) {
				// A new route, a moved main, or a legacy sign gaining its local source
				// dock needs connected station graphs at the current revision.
				refreshConnectedScans(stationFrom, null);
			}

			sendSuccess(player, stationFrom, stationTo, stationOnly, anchorMode, mainMoved,
					reactivated, marker, existingBinding);
			String bindingState = binding == null ? null
					: existingBinding == null ? "added"
					: existingBinding.equals(binding) ? "unchanged" : "replaced";
			EventLog.event("sign.register")
					.field("pos", signLocation)
					.field("dim", dimension)
					.field("player", player.getPlainTextName())
					.field("uuid", player.getUUID())
					.station(stationFrom.id(), stationFrom.displayName())
					.field("to", stationTo == null ? null : stationTo.id().value())
					.field("toName", stationTo == null ? null : stationTo.displayName())
					.field("dock", stationFrom.dock())
					.field("exit", exit)
					.field("anchorMode", Boolean.toString(anchorMode))
					.field("stationOnly", Boolean.toString(stationOnly))
					.field("mainMoved", Boolean.toString(mainMoved))
					.field("reactivated", Boolean.toString(reactivated))
					.field("marker", "updated")
					.field("binding", bindingState)
					.info(logger);
			if (anchorMode) {
				level.sendParticles(ParticleTypes.END_ROD,
						stationFrom.dock().x() + 0.5, stationFrom.dock().y() + 1.0,
						stationFrom.dock().z() + 0.5, 12, 0.25, 0.25, 0.25, 0.01);
			}
			return new Result.Registered(stationFrom, stationTo, binding, desiredMarker);
		} catch (IllegalStateException | IllegalArgumentException e) {
			Failure failure = Failure.fromThrowable(FailureCode.INTERNAL, e);
			return reject(player, sign, front, previous, marker, failure);
		}
	}

	/**
	 * Re-scans anchored stations in the connected component so every graph used together
	 * has the same state revision. {@code alreadyScanned} is the station whose fresh scan
	 * must not be started twice; pass {@code null} to include the origin.
	 */
	private void refreshConnectedScans(Station origin, StationId alreadyScanned) {
		WorldState state = railway.state();
		Set<StationId> seen = new HashSet<>();
		Deque<StationId> queue = new ArrayDeque<>();
		queue.add(origin.id());
		long tick = railway.server().getTickCount();
		while (!queue.isEmpty()) {
			StationId id = queue.removeFirst();
			if (!seen.add(id)) {
				continue;
			}
			Station station = state.station(id).orElse(null);
			if (station == null) {
				continue;
			}
			if (!id.equals(alreadyScanned) && station.isAnchored()) {
				ServerLevel level = RailWorld.level(railway.server(), station.dimension());
				if (level != null) {
					railway.scans().invalidate(id);
					railway.scans().start(station, level,
							railway.config(), tick, state.revision(), ScanTrigger.RESCAN);
				}
			}
			for (Binding binding : state.bindingsFor(id)) {
				queue.add(binding.fromStationId().equals(id)
						? binding.toStationId() : binding.fromStationId());
			}
		}
	}


	private static Failure markerIdentity(
			WorldState state,
			String dimension,
			SignMarker marker,
			StationId fromId,
			StationId toId
	) {
		if (marker == null) {
			return null;
		}
		// A registered unit is owned by exactly one station, including a buttonless
		// main-only unit. Changing line 2 must not silently move that ownership.
		if (marker.ownerStationId() != null && !marker.ownerStationId().equals(fromId)) {
			return Failure.of(FailureCode.SIGN_LOCKED);
		}
		if (marker.button() != null) {
			Binding binding = state.binding(dimension, marker.button()).orElse(null);
			if (binding == null || !binding.fromStationId().equals(fromId)) {
				return Failure.of(FailureCode.SIGN_LOCKED);
			}
			if (toId == null || !binding.toStationId().equals(toId)) {
				return Failure.of(FailureCode.SIGN_LOCKED);
			}
		}
		return null;
	}

	private static PillarValidation validateUnit(
			PillarUnit unit,
			BlockLocation dock,
			boolean stationOnly,
			ServerLevel level
	) {
		SignBlockView view = blockView(level);
		return stationOnly
				? PillarGeometry.validateAnchor(unit, dock, view)
				: PillarGeometry.validate(unit, dock, view);
	}

	/**
	 * Chaining and caps for a unit of an already anchored station. The first unit of a
	 * command-created station needs no chain neighbour; a sign that is already
	 * registered never has to satisfy the rule again.
	 */
	private Failure chainedUnitFailure(
			WorldState state,
			Station station,
			BlockLocation sign,
			BlockLocation support,
			BlockLocation dock,
			SignMarker marker
	) {
		List<SignMarker> others = new ArrayList<>();
		for (SignMarker unit : state.signMarkersForStation(station.id())) {
			if (unit.sign().equals(sign)) {
				continue;
			}
			others.add(unit);
		}
		if (others.size() >= MAX_SIGN_UNITS) {
			return Failure.detail(FailureCode.SIGN_CHAIN,
					"station already has " + others.size() + " sign units (max " + MAX_SIGN_UNITS + ")");
		}
		if (horizontalDistance(support, station.dock()) > MAX_CHAIN_SPAN) {
			return Failure.detail(FailureCode.SIGN_CHAIN,
					"pillar is farther than " + MAX_CHAIN_SPAN + " blocks from the station main dock");
		}
		RailGraph graph = railway.scans().graph(station.id());
		if (graph != null && graph.rail(dock) == null) {
			return Failure.detail(FailureCode.SIGN_GEOMETRY, "local dock is not on the station rail");
		}
		if (others.isEmpty()) {
			return null;
		}
		if (marker == null) {
			for (SignMarker unit : others) {
				if (horizontalDistance(support, unit.support()) <= MAX_CHAIN_STEP) {
					return null;
				}
			}
			return Failure.detail(FailureCode.SIGN_CHAIN,
					"place the pillar within " + MAX_CHAIN_STEP + " blocks of another station sign");
		}
		return null;
	}

	/** A local dock may not be shared by units of different stations. */
	private static Failure sourceDockOwnerFailure(
			WorldState state,
			String dimension,
			StationId fromId,
			Binding existingBinding,
			BlockLocation dock
	) {
		for (Station station : state.stations()) {
			if (!station.dimension().equals(dimension) || !station.isAnchored()
					|| !dock.equals(station.dock()) || station.id().equals(fromId)) {
				continue;
			}
			return Failure.detail(FailureCode.SIGN_CHAIN,
					"local dock " + dock.shortString() + " is the main dock of station "
							+ station.displayName());
		}
		for (Binding other : state.bindings()) {
			if (!other.dimension().equals(dimension) || !dock.equals(other.sourceDock())) {
				continue;
			}
			if (existingBinding != null && other.equals(existingBinding)) {
				continue;
			}
			if (!other.fromStationId().equals(fromId)) {
				return Failure.detail(FailureCode.SIGN_CHAIN,
						"local dock " + dock.shortString() + " belongs to another station");
			}
		}
		return null;
	}

	private static double horizontalDistance(BlockLocation a, BlockLocation b) {
		return Math.hypot(a.x() - b.x(), a.z() - b.z());
	}

	private static FailureCode failureCode(PillarError error) {
		return switch (error) {
			case MISSING_BUTTON, BUTTON_NOT_STONE, BUTTON_NOT_ON_PILLAR,
					BUTTON_SUPPORT_NOT_SOLID, BUTTON_HEIGHT_OUT_OF_RANGE -> FailureCode.SIGN_BUTTON;
			default -> FailureCode.SIGN_GEOMETRY;
		};
	}

	private void sendSuccess(
			ServerPlayer player,
			Station from,
			Station to,
			boolean stationOnly,
			boolean anchoredNow,
			boolean mainMoved,
			boolean reactivated,
			SignMarker previousMarker,
			Binding existingBinding
	) {
		if (reactivated) {
			player.sendSystemMessage(Messages.text(player, "sign.station_restored", from.displayName()));
		}
		if (anchoredNow) {
			if (stationOnly) {
				player.sendSystemMessage(Messages.text(player, "sign.station_only",
						from.displayName(), from.dock().shortString()));
			} else {
				player.sendSystemMessage(Messages.text(player, "sign.anchored",
						from.displayName(), from.dock().shortString()));
			}
			return;
		}
		if (mainMoved) {
			player.sendSystemMessage(Messages.text(player, "sign.main_moved",
					from.displayName(), from.dock().shortString()));
			return;
		}
		if (!stationOnly && (previousMarker == null || existingBinding == null)) {
			player.sendSystemMessage(Messages.text(player, "sign.destination",
					from.displayName(), to == null ? "" : to.displayName()));
			return;
		}
		player.sendSystemMessage(Messages.text(player, "sign.updated"));
	}

	/** Restores the edited side for a non-builder and reports the permission failure. */
	public void rejectUnauthorized(ServerPlayer player, SignBlockEntity sign, boolean front, SignText previous) {
		if (!(sign.getLevel() instanceof ServerLevel level)) {
			return;
		}
		String dimension = RailWorld.dimension(level);
		SignMarker marker = railway.state().signMarker(dimension, RailWorld.loc(sign.getBlockPos())).orElse(null);
		if (marker == null) {
			return;
		}
		restore(sign, front, previous);
		player.sendSystemMessage(Messages.failure(player, Failure.of(FailureCode.PERMISSION)));
	}

	private Result reject(
			ServerPlayer player,
			SignBlockEntity sign,
			boolean front,
			SignText previous,
			SignMarker marker,
			Failure failure
	) {
		if (marker != null) {
			restore(sign, front, previous);
		}
		EventLog log = EventLog.event("sign.reject")
				.field("pos", RailWorld.loc(sign.getBlockPos()))
				.field("front", Boolean.toString(front))
				.field("marker", Boolean.toString(marker != null));
		if (sign.getLevel() instanceof ServerLevel level) {
			log.field("dim", RailWorld.dimension(level));
		}
		if (player != null) {
			log.field("player", player.getPlainTextName())
					.field("uuid", player.getUUID());
		}
		log.failure(failure).warn(logger);
		if (player != null) {
			player.sendSystemMessage(Messages.failure(player, failure));
		}
		return new Result.Rejected(failure);
	}

	private static void restore(SignBlockEntity sign, boolean front, SignText previous) {
		if (previous == null) {
			return;
		}
		sign.setText(previous, front);
		if (sign.getLevel() != null) {
			sign.getLevel().sendBlockUpdated(sign.getBlockPos(), sign.getBlockState(), sign.getBlockState(),
					Block.UPDATE_ALL);
		}
	}

	// --------------------------------------------------------------- lifecycle

	/**
	 * Periodic safety net for marker blocks removed outside the player-break event,
	 * for example by pistons or explosions. Markers in unloaded chunks are left alone;
	 * a loaded but invalid unit is removed exactly like the corresponding block break.
	 */
	public void verifyMarkers(MinecraftServer server) {
		if (railway.disabled()) {
			return;
		}
		WorldState worldState = railway.state();
		Set<String> handledSigns = new HashSet<>();
		boolean changed = false;
		for (SignMarker marker : List.copyOf(worldState.signMarkers())) {
			ServerLevel level = RailWorld.level(server, marker.dimension());
			if (level == null || !markerPositionsLoaded(level, marker)) {
				continue;
			}
			if (!signStillValid(level, marker)) {
				changed |= breakSignMarker(worldState, level, marker.dimension(), marker, handledSigns, null);
				continue;
			}
			if (marker.button() != null && !buttonStillValid(level, marker)) {
				changed |= breakButtonSide(worldState, level, marker.dimension(), marker.button(), marker,
						handledSigns, null);
			}
		}
		if (changed) {
			railway.saveState();
		}
	}

	/** All recorded positions must be loaded before a marker may be invalidated. */
	private static boolean markerPositionsLoaded(ServerLevel level, SignMarker marker) {
		if (!level.isLoaded(RailWorld.pos(marker.sign()))
				|| !level.isLoaded(RailWorld.pos(marker.support()))) {
			return false;
		}
		if (marker.button() == null) {
			return true;
		}
		return level.isLoaded(RailWorld.pos(marker.button()))
				&& level.isLoaded(RailWorld.pos(buttonSupport(marker)));
	}

	private static boolean signStillValid(ServerLevel level, SignMarker marker) {
		BlockPos pos = RailWorld.pos(marker.sign());
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof WallSignBlock)) {
			return false;
		}
		Direction back = state.getValue(WallSignBlock.FACING).getOpposite();
		BlockLocation attached = marker.sign().offset(back.getStepX(), 0, back.getStepZ());
		if (!attached.equals(marker.support())) {
			return false;
		}
		BlockPos support = RailWorld.pos(marker.support());
		return level.getBlockState(support).isCollisionShapeFullBlock(level, support);
	}

	private static boolean buttonStillValid(ServerLevel level, SignMarker marker) {
		BlockPos pos = RailWorld.pos(marker.button());
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.STONE_BUTTON)) {
			return false;
		}
		if (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) != AttachFace.WALL) {
			return false;
		}
		Direction back = state.getValue(HorizontalDirectionalBlock.FACING).getOpposite();
		BlockLocation attached = marker.button().offset(back.getStepX(), 0, back.getStepZ());
		return attached.x() == marker.support().x() && attached.z() == marker.support().z();
	}

	public void onBlockBreak(ServerLevel level, BlockPos pos, BlockState state, BlockEntity entity,
			ServerPlayer player) {
		if (railway.disabled()) {
			return;
		}
		WorldState worldState = railway.state();
		String dimension = RailWorld.dimension(level);
		BlockLocation broken = RailWorld.loc(pos);
		Set<String> handledSigns = new HashSet<>();
		boolean changed = false;

		SignMarker markerAtSign = worldState.signMarker(dimension, broken).orElse(null);
		if (markerAtSign != null) {
			changed |= breakSignMarker(worldState, level, dimension, markerAtSign, handledSigns, player);
		}

		if (state.getBlock() instanceof ButtonBlock) {
			SignMarker marker = worldState.signMarkerForButton(dimension, broken).orElse(null);
			changed |= breakButtonSide(worldState, level, dimension, broken, marker, handledSigns, player);
		}

		for (SignMarker marker : List.copyOf(worldState.signMarkers())) {
			if (!marker.dimension().equals(dimension)) {
				continue;
			}
			if (handledSigns.contains(WorldState.signMarkerKey(marker.dimension(), marker.sign()))) {
				continue;
			}
			if (marker.support().equals(broken)) {
				changed |= breakSignMarker(worldState, level, dimension, marker, handledSigns, player);
			} else if (marker.button() != null && buttonSupport(marker).equals(broken)) {
				changed |= breakButtonSide(worldState, level, dimension, marker.button(), marker, handledSigns, player);
			}
		}

		if (changed) {
			railway.saveState();
		}
	}

	private boolean breakSignMarker(
			WorldState state,
			ServerLevel level,
			String dimension,
			SignMarker marker,
			Set<String> handledSigns,
			ServerPlayer player
	) {
		if (!handledSigns.add(WorldState.signMarkerKey(marker.dimension(), marker.sign()))) {
			return false;
		}
		boolean changed = state.removeSignMarker(dimension, marker.sign()).isPresent();
		if (marker.button() != null) {
			changed |= state.removeBinding(dimension, marker.button()).isPresent();
		}
		StationId ownerId = marker.anchorStationId() != null
				? marker.anchorStationId() : marker.stationId();
		boolean disabled = false;
		if (changed && ownerId != null) {
			if (state.signMarkersForStation(ownerId).isEmpty()) {
				// No units left: this is terminal. The station keeps its data for a
				// later restore; there is no sign to carry an m flag.
				disabled = disableForNoUnits(state, player, ownerId, marker);
			} else if (marker.main() || marker.anchorStationId() != null) {
				replaceMissingMain(state, level, player, ownerId, marker);
			}
		}
		if (changed) {
			Station logStation = ownerId == null ? null : state.station(ownerId).orElse(null);
			EventLog log = EventLog.event("sign.marker.remove")
					.field("pos", marker.sign())
					.field("dim", dimension)
					.field("disabled", Boolean.toString(disabled));
			if (logStation != null) {
				log.station(logStation.id(), logStation.displayName());
			} else if (ownerId != null) {
				log.field("station", ownerId.value());
			}
			log.info(logger);
		}
		return changed;
	}

	/**
	 * The last sign unit is gone. Disable the station (if enabled) and tell the
	 * breaker how to restore it; there is no sign left on which to set {@code m}.
	 */
	private boolean disableForNoUnits(WorldState state, ServerPlayer player, StationId stationId,
			SignMarker removed) {
		Station station = state.station(stationId).orElse(null);
		if (station == null) {
			return false;
		}
		boolean disabled = false;
		if (station.enabled()) {
			state.updateStation(station.withEnabled(false, state.nextRevision()));
			disabled = true;
		}
		railway.scans().invalidate(stationId);
		EventLog.event("sign.last_unit.remove")
				.station(station.id(), station.displayName())
				.field("pos", removed.sign())
				.field("units", 0)
				.field("disabled", Boolean.toString(disabled))
				.info(logger);
		if (player != null) {
			player.sendSystemMessage(Messages.text(player, "sign.station_disabled", station.displayName()));
		}
		return disabled;
	}

	private boolean breakButtonSide(
			WorldState state,
			ServerLevel level,
			String dimension,
			BlockLocation button,
			SignMarker marker,
			Set<String> handledSigns,
			ServerPlayer player
	) {
		boolean bindingRemoved = state.removeBinding(dimension, button).isPresent();
		if (marker == null) {
			if (bindingRemoved) {
				logButtonRemoval(dimension, button, null, null, true);
			}
			return bindingRemoved;
		}
		if (!handledSigns.add(WorldState.signMarkerKey(marker.dimension(), marker.sign()))) {
			return bindingRemoved;
		}
		StationId stationId = marker.anchorStationId() != null
				? marker.anchorStationId() : marker.ownerStationId();
		Station station = stationId == null ? null : state.station(stationId).orElse(null);
		if (marker.anchorStationId() != null) {
			state.updateSignMarker(marker.withButton(null));
		} else {
			state.removeSignMarker(dimension, marker.sign());
			if (stationId != null) {
				if (state.signMarkersForStation(stationId).isEmpty()) {
					disableForNoUnits(state, player, stationId, marker);
				} else if (marker.main()) {
					replaceMissingMain(state, level, player, stationId, marker);
				}
			}
		}
		logButtonRemoval(dimension, button, stationId, station, bindingRemoved);
		return true;
	}

	/**
	 * A removed anchor or main unit must never leave the station pointing at a dead
	 * dock. Promote the nearest remaining unit that still has a resolvable dock and a
	 * safe exit; if none exists, disable the station and ask for an explicit main.
	 */
	private void replaceMissingMain(WorldState state, ServerLevel level, ServerPlayer player,
			StationId stationId, SignMarker removed) {
		Station station = state.station(stationId).orElse(null);
		if (station == null || !station.enabled() || station.dock() == null
				|| state.mainMarkerForStation(stationId).isPresent()) {
			return;
		}
		MainCandidate candidate = findMainCandidate(state, level, station);
		String cause = removed.main() ? "main_removed" : "anchor_removed";
		if (candidate != null) {
			BlockLocation oldDock = station.dock();
			float yaw = yawAway(candidate.dock(), candidate.exit());
			Station updated = station.withMain(candidate.dock(), candidate.exit(), yaw,
					state.nextRevision());
			state.updateStation(updated);
			state.updateSignMarker(candidate.marker().withMain(true));
			EventLog.event("sign.main.promoted")
					.field("station", updated.id().value())
					.field("name", updated.displayName())
					.field("from", oldDock)
					.field("to", candidate.dock())
					.field("unit", candidate.marker().sign())
					.field("cause", cause)
					.info(logger);
			if (player != null) {
				player.sendSystemMessage(Messages.text(player, "sign.main_moved",
						updated.displayName(), candidate.dock().shortString()));
			}
			refreshConnectedScans(updated, null);
			return;
		}
		state.updateStation(station.withEnabled(false, state.nextRevision()));
		railway.scans().invalidate(stationId);
		EventLog.event("sign.main.missing")
				.field("station", station.id().value())
				.field("name", station.displayName())
				.field("units", state.signUnitCount(stationId))
				.field("cause", cause)
				.warn(logger);
		if (player != null) {
			player.sendSystemMessage(Messages.text(player, "sign.main_missing", station.displayName()));
		}
	}

	private record MainCandidate(SignMarker marker, BlockLocation dock, BlockLocation exit) {
	}

	private MainCandidate findMainCandidate(WorldState state, ServerLevel level, Station station) {
		RailWorld world = new RailWorld(level);
		MainCandidate best = null;
		long bestDistance = Long.MAX_VALUE;
		for (SignMarker marker : state.signMarkersForStation(station.id())) {
			if (!marker.dimension().equals(station.dimension())) {
				continue;
			}
			BlockLocation dock = unitDock(state, level, marker, station);
			if (dock == null) {
				continue;
			}
			BlockLocation exit = findSafeExit(level, world, dock).orElse(null);
			if (exit == null) {
				continue;
			}
			long distance = distanceSquared(station.dock(), dock);
			if (best == null || distance < bestDistance
					|| (distance == bestDistance
					&& BlockLocation.POSITION_ORDER.compare(marker.sign(), best.marker().sign()) < 0)) {
				best = new MainCandidate(marker, dock, exit);
				bestDistance = distance;
			}
		}
		return best;
	}

	private BlockLocation unitDock(WorldState state, ServerLevel level, SignMarker marker, Station station) {
		if (marker.button() != null) {
			Binding binding = state.binding(station.dimension(), marker.button()).orElse(null);
			if (binding != null && binding.hasSourceDock()
					&& station.id().equals(binding.fromStationId())) {
				return binding.sourceDock();
			}
		}
		BlockState signState = level.getBlockState(RailWorld.pos(marker.sign()));
		if (!(signState.getBlock() instanceof WallSignBlock)) {
			return null;
		}
		Direction facing = signState.getValue(WallSignBlock.FACING);
		HorizontalAxis axis = facing.getAxis() == Direction.Axis.X ? HorizontalAxis.X : HorizontalAxis.Z;
		DockResolution resolution = PillarGeometry.resolveDock(marker.sign(), axis, blockView(level));
		return resolution instanceof DockResolution.Found found ? found.dock() : null;
	}

	private static long distanceSquared(BlockLocation from, BlockLocation to) {
		long dx = (long) from.x() - to.x();
		long dy = (long) from.y() - to.y();
		long dz = (long) from.z() - to.z();
		return dx * dx + dy * dy + dz * dz;
	}

	private static float yawAway(BlockLocation dock, BlockLocation exit) {
		int dx = exit.x() - dock.x();
		int dz = exit.z() - dock.z();
		if (dx == 0 && dz == 0) {
			return 0.0F;
		}
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}


	private void logButtonRemoval(
			String dimension,
			BlockLocation button,
			StationId stationId,
			Station station,
			boolean bindingRemoved
	) {
		EventLog log = EventLog.event("sign.button.remove")
				.field("pos", button)
				.field("dim", dimension)
				.field("bindingRemoved", Boolean.toString(bindingRemoved));
		if (station != null) {
			log.station(station.id(), station.displayName());
		} else if (stationId != null) {
			log.field("station", stationId.value());
		}
		log.info(logger);
	}

	private static BlockLocation buttonSupport(SignMarker marker) {
		return new BlockLocation(marker.support().x(), marker.button().y(), marker.support().z());
	}

	// ------------------------------------------------------------------ world

	private static SignBlockView blockView(ServerLevel level) {
		return position -> {
			BlockPos pos = RailWorld.pos(position);
			if (!level.isLoaded(pos)) {
				return BlockSample.AIR;
			}
			BlockState state = level.getBlockState(pos);
			boolean rail = BaseRailBlock.isRail(state);
			boolean straight = false;
			if (rail) {
				RailShape shape = state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
				straight = shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST;
			}
			return new BlockSample(RailWorld.blockId(state),
					state.isCollisionShapeFullBlock(level, pos) && !rail,
					state.is(Blocks.STONE_BUTTON), rail, straight);
		};
	}

	private static int countWallSigns(ServerLevel level, BlockLocation support, int signY) {
		int count = 0;
		for (int y = signY - SUPPORT_SCAN_DEPTH; y <= signY + SUPPORT_SCAN_DEPTH; y++) {
			for (Direction direction : HORIZONTAL) {
				BlockLocation candidate = support.offset(direction.getStepX(), y - support.y(),
						direction.getStepZ());
				if (!level.isLoaded(RailWorld.pos(candidate))) {
					continue;
				}
				BlockState state = level.getBlockState(RailWorld.pos(candidate));
				if (!(state.getBlock() instanceof WallSignBlock)) {
					continue;
				}
				Direction facing = state.getValue(WallSignBlock.FACING);
				BlockLocation behind = candidate.offset(facing.getOpposite().getStepX(), 0,
						facing.getOpposite().getStepZ());
				if (behind.x() == support.x() && behind.z() == support.z()) {
					count++;
				}
			}
		}
		return count;
	}

	private static List<BlockLocation> buttonsOnColumn(
			ServerLevel level,
			BlockLocation support,
			int dockY,
			int signY
	) {
		int minY = Math.min(dockY + PillarGeometry.MIN_UNIT_HEIGHT - 1, signY - SUPPORT_SCAN_DEPTH);
		int maxY = Math.max(dockY + PillarGeometry.MAX_UNIT_HEIGHT - 1, signY + SUPPORT_SCAN_DEPTH);
		List<BlockLocation> found = new ArrayList<>();
		for (int y = minY; y <= maxY; y++) {
			for (Direction direction : HORIZONTAL) {
				BlockLocation candidate = support.offset(direction.getStepX(), y - support.y(),
						direction.getStepZ());
				if (!level.isLoaded(RailWorld.pos(candidate))) {
					continue;
				}
				BlockState state = level.getBlockState(RailWorld.pos(candidate));
				if (!(state.getBlock() instanceof ButtonBlock)) {
					continue;
				}
				if (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) != AttachFace.WALL) {
					continue;
				}
				Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
				BlockLocation behind = candidate.offset(facing.getOpposite().getStepX(), 0,
						facing.getOpposite().getStepZ());
				if (behind.x() == support.x() && behind.z() == support.z()) {
					found.add(candidate);
				}
			}
		}
		return found;
	}

	private BlockLocation selectExit(ServerLevel level, RailWorld world, ServerPlayer player, BlockLocation dock) {
		BlockLocation feet = RailWorld.loc(player.blockPosition());
		if (world.isSafeStand(feet) && withinExitEnvelope(feet, dock)) {
			return feet;
		}
		return findSafeExit(level, world, dock).orElse(null);
	}

	private static boolean withinExitEnvelope(BlockLocation position, BlockLocation dock) {
		double dx = position.x() - dock.x();
		double dz = position.z() - dock.z();
		return dx * dx + dz * dz <= EXIT_RADIUS * EXIT_RADIUS
				&& Math.abs(position.y() - dock.y()) <= EXIT_VERTICAL_RANGE;
	}

	private static Optional<BlockLocation> findSafeExit(ServerLevel level, RailWorld world, BlockLocation dock) {
		BlockLocation best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int dy = -EXIT_VERTICAL_RANGE; dy <= EXIT_VERTICAL_RANGE; dy++) {
			int verticalScore = Math.abs(dy) * 8;
			for (int dx = -EXIT_RADIUS; dx <= EXIT_RADIUS; dx++) {
				for (int dz = -EXIT_RADIUS; dz <= EXIT_RADIUS; dz++) {
					if (dx * dx + dz * dz > EXIT_RADIUS * EXIT_RADIUS) {
						continue;
					}
					BlockLocation candidate = dock.offset(dx, dy, dz);
					if (candidate.equals(dock) || !level.isLoaded(RailWorld.pos(candidate))) {
						continue;
					}
					if (BaseRailBlock.isRail(level.getBlockState(RailWorld.pos(candidate)))) {
						continue;
					}
					if (!world.isSafeStand(candidate)) {
						continue;
					}
					int score = dx * dx + dz * dz + verticalScore;
					if (score < bestScore) {
						bestScore = score;
						best = candidate;
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static List<String> rawLines(SignBlockEntity sign, boolean front) {
		SignText text = sign.getText(front);
		return List.of(
				text.getMessage(0, false).getString(),
				text.getMessage(1, false).getString(),
				text.getMessage(2, false).getString(),
				text.getMessage(3, false).getString()
		);
	}
}
