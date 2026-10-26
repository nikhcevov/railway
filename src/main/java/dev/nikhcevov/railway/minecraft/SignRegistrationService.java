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

		if (!anchorMode) {
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
		if (anchorMode || parsed.main()) {
			exit = selectExit(level, railWorld, player, dock);
			if (exit == null) {
				return reject(player, sign, front, previous, marker, Failure.at(FailureCode.EXIT_UNSAFE, dock));
			}
		}

		Station stationFrom;
		Station stationTo = null;
		boolean mainMoved = false;
		try {
			if (anchorMode) {
				if (from == null) {
					stationFrom = new Station(fromId, fromName, dimension, dock, exit,
							player.getYRot(), true, state.nextRevision());
					state.addStation(stationFrom);
				} else {
					stationFrom = new Station(from.id(), from.displayName(), dimension, dock, exit,
							player.getYRot(), true, state.nextRevision());
					state.updateStation(stationFrom);
				}
			} else {
				stationFrom = from;
				if (parsed.main() && (!java.util.Objects.equals(from.dock(), dock)
						|| !java.util.Objects.equals(from.exit(), exit)
						|| Float.compare(from.exitYaw(), player.getYRot()) != 0)) {
					stationFrom = from.withMain(dock, exit, player.getYRot(), state.nextRevision());
					state.updateStation(stationFrom);
					mainMoved = true;
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
			SignMarker desiredMarker = new SignMarker(dimension, signLocation, support, front, button,
					anchorStationId, stationId, parsed.main());
			state.updateSignMarker(desiredMarker);
			railway.saveState();

			if (anchorMode) {
				ScanService.StartResult scan = railway.scans().start(stationFrom, level, railway.config(),
						railway.server().getTickCount(), state.revision());
				if (scan instanceof ScanService.StartResult.Rejected rejected) {
					logger.warn("Sign station {} anchored but scan did not start: {} {}",
							stationFrom.displayName(), rejected.failure().code(), rejected.failure().detail());
				}
				refreshConnectedScans(stationFrom, stationFrom.id());
			} else if (mainMoved || (binding != null && existingBinding == null)) {
				// A new route or a moved main needs both endpoints at the current
				// revision. A main move plus a new binding still refreshes once.
				refreshConnectedScans(stationFrom, null);
			}

			sendSuccess(player, stationFrom, stationTo, stationOnly, anchorMode, mainMoved,
					marker, existingBinding);
			if (anchorMode) {
				level.sendParticles(ParticleTypes.END_ROD,
						stationFrom.dock().x() + 0.5, stationFrom.dock().y() + 1.0,
						stationFrom.dock().z() + 0.5, 12, 0.25, 0.25, 0.25, 0.01);
			}
			return new Result.Registered(stationFrom, stationTo, binding, desiredMarker);
		} catch (IllegalStateException | IllegalArgumentException e) {
			logger.error("Sign registration failed after validation", e);
			return reject(player, sign, front, previous, marker,
					Failure.detail(FailureCode.INTERNAL, e.getMessage()));
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
					ScanService.StartResult result = railway.scans().start(station, level,
							railway.config(), tick, state.revision());
					if (result instanceof ScanService.StartResult.Rejected rejected) {
						logger.warn("Connected station {} was not rescanned: {} {}",
								station.displayName(), rejected.failure().code(), rejected.failure().detail());
					}
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
			SignMarker previousMarker,
			Binding existingBinding
	) {
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
		logger.debug("Sign registration rejected at {}: {} {}",
				RailWorld.loc(sign.getBlockPos()).shortString(), failure.code(), failure.detail());
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

	public void onBlockBreak(ServerLevel level, BlockPos pos, BlockState state, BlockEntity entity) {
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
			changed |= breakSignMarker(worldState, dimension, markerAtSign, handledSigns);
		}

		if (state.getBlock() instanceof ButtonBlock) {
			SignMarker marker = worldState.signMarkerForButton(dimension, broken).orElse(null);
			changed |= breakButtonSide(worldState, dimension, broken, marker, handledSigns);
		}

		for (SignMarker marker : List.copyOf(worldState.signMarkers())) {
			if (!marker.dimension().equals(dimension)) {
				continue;
			}
			if (handledSigns.contains(WorldState.signMarkerKey(marker.dimension(), marker.sign()))) {
				continue;
			}
			if (marker.support().equals(broken)) {
				changed |= breakSignMarker(worldState, dimension, marker, handledSigns);
			} else if (marker.button() != null && buttonSupport(marker).equals(broken)) {
				changed |= breakButtonSide(worldState, dimension, marker.button(), marker, handledSigns);
			}
		}

		if (changed) {
			railway.saveState();
		}
	}

	private boolean breakSignMarker(
			WorldState state,
			String dimension,
			SignMarker marker,
			Set<String> handledSigns
	) {
		if (!handledSigns.add(WorldState.signMarkerKey(marker.dimension(), marker.sign()))) {
			return false;
		}
		boolean changed = state.removeSignMarker(dimension, marker.sign()).isPresent();
		if (marker.button() != null) {
			changed |= state.removeBinding(dimension, marker.button()).isPresent();
		}
		if (marker.anchorStationId() != null) {
			Station station = state.station(marker.anchorStationId()).orElse(null);
			if (station != null && station.isAnchored() && station.enabled()
					&& !state.hasOtherSignMarkers(station.id(), dimension, marker.sign())) {
				// The last unit is gone: disable the station but keep its data. With
				// other units left the station stays enabled and keeps its main.
				state.updateStation(station.withEnabled(false, state.nextRevision()));
				changed = true;
			}
		}
		if (changed) {
			logger.info("Removed sign marker at {}", marker.sign().shortString());
		}
		return changed;
	}

	private boolean breakButtonSide(
			WorldState state,
			String dimension,
			BlockLocation button,
			SignMarker marker,
			Set<String> handledSigns
	) {
		boolean changed = state.removeBinding(dimension, button).isPresent();
		if (marker == null) {
			return changed;
		}
		if (!handledSigns.add(WorldState.signMarkerKey(marker.dimension(), marker.sign()))) {
			return changed;
		}
		if (marker.anchorStationId() != null) {
			state.updateSignMarker(marker.withButton(null));
		} else {
			state.removeSignMarker(dimension, marker.sign());
		}
		return true;
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
