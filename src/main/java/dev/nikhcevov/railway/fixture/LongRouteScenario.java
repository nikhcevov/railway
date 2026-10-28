package dev.nikhcevov.railway.fixture;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.network.GraphRailView;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.network.RailGraphPlanner;
import dev.nikhcevov.railway.network.RailShapeType;
import dev.nikhcevov.railway.persistence.Config;
import dev.nikhcevov.railway.persistence.WorldState;
import dev.nikhcevov.railway.sign.SignFormat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and checks the real-world {@code long-route-unloaded} fixture through
 * the production {@link WorldState} models. The fixture deliberately does not
 * call {@code ScanTicketSelection}, start a trip, or write NBT/JSON state by
 * hand: it places ordinary vanilla blocks and saves through {@code Railway}.
 */
public final class LongRouteScenario {
	public static final String SCENARIO = "long-route-unloaded";
	public static final String HOME_ID = "fixture_long_home";
	public static final String MINE_ID = "fixture_long_mine";
	public static final String HOME_NAME = "Railway Fixture Long Home";
	public static final String MINE_NAME = "Railway Fixture Long Mine";
	public static final String BUTTON_BLOCK_ID = "minecraft:stone_button";

	private LongRouteScenario() {
	}

	public record BuildResult(
			int rails,
			int signs,
			int buttons,
			int chunks,
			BlockLocation origin,
			List<String> notes
	) {
	}

	public record RouteResult(
			String component,
			int forwardLength,
			int reverseLength,
			List<String> failures
	) {
	}

	/** Places the fixture and persists both the Railway state and the world chunks. */
	public static BuildResult build(Railway railway, ServerLevel level) {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		WorldState state = railway.state();
		String dimension = RailWorld.dimension(level);
		StationId homeId = new StationId(HOME_ID);
		StationId mineId = new StationId(MINE_ID);

		for (StationId id : List.of(homeId, mineId)) {
			if (state.station(id).isPresent()) {
				state.removeStation(id);
			}
			railway.scans().invalidate(id);
		}
		if (state.hasStationNamed(dimension, HOME_NAME) || state.hasStationNamed(dimension, MINE_NAME)) {
			throw new FixtureException("a non-fixture station already uses a fixture name in " + dimension);
		}
		if (state.binding(dimension, layout.homeButton()).isPresent()
				|| state.binding(dimension, layout.destinationButton()).isPresent()) {
			throw new FixtureException("a binding already occupies a fixture button position");
		}
		if (state.signMarker(dimension, layout.homeSign()).isPresent()
				|| state.signMarker(dimension, layout.destinationSign()).isPresent()) {
			throw new FixtureException("a sign marker already occupies a fixture sign position");
		}

		int chunks = placeChunks(level, layout);
		placeRailLine(level, layout);
		placeStation(level, layout, layout.homeDockX(), HOME_NAME, MINE_NAME);
		placeStation(level, layout, layout.destinationDockX(), MINE_NAME, HOME_NAME);

		Station home = new Station(homeId, HOME_NAME, dimension, layout.homeDock(), layout.homeExit(),
				0.0F, true, state.nextRevision());
		Station mine = new Station(mineId, MINE_NAME, dimension, layout.destinationDock(),
				layout.destinationExit(), 0.0F, true, state.nextRevision());
		state.addStation(home);
		state.addStation(mine);

		state.addBinding(new Binding(dimension, layout.homeButton(), BUTTON_BLOCK_ID,
				homeId, mineId, layout.homeDock()));
		state.addBinding(new Binding(dimension, layout.destinationButton(), BUTTON_BLOCK_ID,
				mineId, homeId, layout.destinationDock()));

		state.addSignMarker(new SignMarker(dimension, layout.homeSign(), layout.homeSupport(), true,
				layout.homeButton(), homeId, homeId, false));
		state.addSignMarker(new SignMarker(dimension, layout.destinationSign(), layout.destinationSupport(),
				true, layout.destinationButton(), mineId, mineId, false));
		railway.saveState();

		boolean saved = level.getServer().saveEverything(true, true, true);
		List<String> notes = new ArrayList<>();
		if (!saved) {
			notes.add("server.saveEverything reported a failure; chunks may not be durable");
		}
		notes.add("destination chunk " + layout.destinationChunkX() + "," + layout.destinationChunkZ()
				+ " must be unloaded after restart; use simulation-distance 4 and no player at the fixture");
		return new BuildResult(layout.railCount(), 2, 2, chunks, layout.origin(), List.copyOf(notes));
	}

	/** Checks persisted state, and optionally the physical blocks once chunks are loaded. */
	public static List<String> verify(Railway railway, ServerLevel level, boolean includeBlocks) {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		List<String> failures = new ArrayList<>();
		WorldState state = railway.state();
		String dimension = RailWorld.dimension(level);
		Station home = fixtureStation(state, new StationId(HOME_ID), layout.homeDock(),
				layout.homeExit(), dimension, failures);
		Station mine = fixtureStation(state, new StationId(MINE_ID), layout.destinationDock(),
				layout.destinationExit(), dimension, failures);

		verifyBinding(state, dimension, layout.homeButton(), HOME_ID, MINE_ID, layout.homeDock(), failures);
		verifyBinding(state, dimension, layout.destinationButton(), MINE_ID, HOME_ID,
				layout.destinationDock(), failures);
		verifyMarker(state, dimension, layout.homeSign(), layout.homeSupport(), layout.homeButton(),
				new StationId(HOME_ID), failures);
		verifyMarker(state, dimension, layout.destinationSign(), layout.destinationSupport(),
				layout.destinationButton(), new StationId(MINE_ID), failures);

		if (home != null && mine != null && home.id().equals(mine.id())) {
			failures.add("fixture stations share an id");
		}
		if (includeBlocks) {
			verifyBlocks(level, layout, failures);
		}
		return List.copyOf(failures);
	}

	/** Checks the scanned graph only; no chunk is loaded or generated here. */
	public static void verifyGraph(RailGraph graph, List<String> failures) {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		if (graph.rails().size() != layout.railCount()) {
			failures.add("scanned rails " + graph.rails().size() + ", expected " + layout.railCount());
		}
		for (BlockLocation position : layout.rails()) {
			var rail = graph.rail(position);
			if (rail == null) {
				failures.add("scanned graph is missing rail " + position.shortString());
				continue;
			}
			if (rail.shape() != RailShapeType.EAST_WEST) {
				failures.add("scanned rail " + position.shortString() + " has shape " + rail.shape());
			}
		}
	}

	/** Plans both directions on the scanned component and checks the expected length. */
	public static RouteResult verifyRoute(Railway railway, ServerLevel level, RailGraph graph) {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		List<String> failures = new ArrayList<>();
		Station home = railway.state().station(new StationId(HOME_ID)).orElse(null);
		Station mine = railway.state().station(new StationId(MINE_ID)).orElse(null);
		if (home == null || mine == null) {
			failures.add("cannot verify route without both fixture stations");
			return new RouteResult(graph == null ? "" : graph.componentKey(), -1, -1, List.copyOf(failures));
		}
		if (graph == null) {
			failures.add("no scanned graph");
			return new RouteResult("", -1, -1, List.copyOf(failures));
		}
		Config config = railway.config();
		RailWorld world = new RailWorld(level);
		int forward = planAndValidate(graph, home, mine, config, world, "home->destination", failures);
		int reverse = planAndValidate(graph, mine, home, config, world, "destination->home", failures);
		if (forward != layout.routeLength()) {
			failures.add("home->destination route length " + forward + ", expected " + layout.routeLength());
		}
		if (reverse != layout.routeLength()) {
			failures.add("destination->home route length " + reverse + ", expected " + layout.routeLength());
		}
		return new RouteResult(graph.componentKey(), forward, reverse, List.copyOf(failures));
	}

	private static int planAndValidate(RailGraph graph, Station from, Station to, Config config,
			RailWorld world, String label, List<String> failures) {
		RailGraphPlanner.Result result = RailGraphPlanner.plan(graph, from.id(), to.id(),
				from.dock(), to.dock(), config.maxRouteLength());
		if (result instanceof RailGraphPlanner.Result.Rejected rejected) {
			failures.add(label + " rejected: " + FixtureReport.failureText(rejected.failure()));
			return -1;
		}
		GraphRoutePlan plan = ((RailGraphPlanner.Result.Planned) result).plan();
		var failure = world.validateGraphRoute(plan);
		if (failure != null) {
			failures.add(label + " world validation failed: " + FixtureReport.failureText(failure));
		}
		return plan.length();
	}

	private static Station fixtureStation(WorldState state, StationId id, BlockLocation dock,
			BlockLocation exit, String dimension, List<String> failures) {
		Station station = state.station(id).orElse(null);
		if (station == null) {
			failures.add("missing station " + id.value());
			return null;
		}
		if (!station.dimension().equals(dimension)) {
			failures.add("station " + id.value() + " dimension " + station.dimension());
		}
		if (!station.isAnchored() || !station.enabled()) {
			failures.add("station " + id.value() + " is not anchored and enabled");
		}
		if (!dock.equals(station.dock())) {
			failures.add("station " + id.value() + " dock " + station.dock() + ", expected " + dock);
		}
		if (!exit.equals(station.exit())) {
			failures.add("station " + id.value() + " exit " + station.exit() + ", expected " + exit);
		}
		return station;
	}

	private static void verifyBinding(WorldState state, String dimension, BlockLocation button,
			String from, String to, BlockLocation sourceDock, List<String> failures) {
		Binding binding = state.binding(dimension, button).orElse(null);
		if (binding == null) {
			failures.add("missing binding at " + button.shortString());
			return;
		}
		if (!binding.fromStationId().value().equals(from) || !binding.toStationId().value().equals(to)) {
			failures.add("binding at " + button.shortString() + " is "
					+ binding.fromStationId().value() + "->" + binding.toStationId().value());
		}
		if (!BUTTON_BLOCK_ID.equals(binding.expectedButtonBlockId())) {
			failures.add("binding at " + button.shortString() + " expects "
					+ binding.expectedButtonBlockId());
		}
		if (!sourceDock.equals(binding.sourceDock())) {
			failures.add("binding at " + button.shortString() + " source dock "
					+ binding.sourceDock() + ", expected " + sourceDock);
		}
	}

	private static void verifyMarker(WorldState state, String dimension, BlockLocation sign,
			BlockLocation support, BlockLocation button, StationId station, List<String> failures) {
		SignMarker marker = state.signMarker(dimension, sign).orElse(null);
		if (marker == null) {
			failures.add("missing sign marker at " + sign.shortString());
			return;
		}
		if (!support.equals(marker.support()) || !button.equals(marker.button())) {
			failures.add("sign marker at " + sign.shortString() + " support/button mismatch");
		}
		if (!station.equals(marker.anchorStationId()) || !station.equals(marker.stationId())) {
			failures.add("sign marker at " + sign.shortString() + " does not own " + station.value());
		}
	}

	private static int placeChunks(ServerLevel level, LongRouteLayout layout) {
		int chunks = 0;
		for (int cx = layout.homeChunkX(); cx <= layout.destinationChunkX(); cx++) {
			level.getChunk(cx, layout.homeChunkZ());
			chunks++;
		}
		return chunks;
	}

	private static void placeRailLine(ServerLevel level, LongRouteLayout layout) {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = layout.homeDockX(); x <= layout.destinationDockX(); x++) {
			for (int z = layout.corridorMinZ(); z <= layout.corridorMaxZ(); z++) {
				set(level, new BlockLocation(x, layout.baseY(), z), stone);
				for (int y = layout.baseY() + 1; y <= layout.railY() + 4; y++) {
					set(level, new BlockLocation(x, y, z), air);
				}
			}
			set(level, layout.rail(x), rail);
		}
	}

	private static void placeStation(ServerLevel level, LongRouteLayout layout, int x,
			String fromName, String toName) {
		BlockState stone = Blocks.STONE.defaultBlockState();
		for (int y = layout.baseY(); y <= layout.signY(); y++) {
			set(level, new BlockLocation(x, y, layout.supportZ()), stone);
		}
		placeSign(level, new BlockLocation(x, layout.signY(), layout.signZ()), fromName, toName);
		placeButton(level, new BlockLocation(x, layout.buttonY(), layout.signZ()));
	}

	private static void placeSign(ServerLevel level, BlockLocation sign, String fromName, String toName) {
		BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState()
				.setValue(WallSignBlock.FACING, Direction.NORTH);
		BlockPos pos = RailWorld.pos(sign);
		level.setBlock(pos, signState, 2);
		BlockEntity entity = level.getBlockEntity(pos);
		if (!(entity instanceof SignBlockEntity signEntity)) {
			throw new FixtureException("wall sign block entity was not created at " + sign.shortString());
		}
		SignText text = new SignText()
				.setMessage(0, Component.literal(SignFormat.MARKER))
				.setMessage(1, Component.literal(fromName))
				.setMessage(2, Component.literal(toName))
				.setMessage(3, Component.literal(""));
		signEntity.setText(text, true);
		signEntity.setChanged();
	}

	private static void placeButton(ServerLevel level, BlockLocation button) {
		BlockState state = Blocks.STONE_BUTTON.defaultBlockState()
				.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
				.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
		set(level, button, state);
	}

	private static void verifyBlocks(ServerLevel level, LongRouteLayout layout, List<String> failures) {
		RailWorld world = new RailWorld(level);
		for (BlockLocation rail : layout.rails()) {
			if (!(world.graphProbe(rail) instanceof GraphRailView.Probe.Rail probe)) {
				failures.add("rail missing or unloaded at " + rail.shortString());
				continue;
			}
			if (probe.shape() != RailShapeType.EAST_WEST) {
				failures.add("rail at " + rail.shortString() + " has shape " + probe.shape());
			}
		}
		verifyButton(world, layout.homeButton(), failures);
		verifyButton(world, layout.destinationButton(), failures);
		verifySign(level, layout.homeSign(), HOME_NAME, MINE_NAME, failures);
		verifySign(level, layout.destinationSign(), MINE_NAME, HOME_NAME, failures);
		if (!world.isSafeStand(layout.homeExit())) {
			failures.add("home exit is not a safe stand at " + layout.homeExit().shortString());
		}
		if (!world.isSafeStand(layout.destinationExit())) {
			failures.add("destination exit is not a safe stand at "
					+ layout.destinationExit().shortString());
		}
	}

	private static void verifyButton(RailWorld world, BlockLocation button, List<String> failures) {
		if (!world.isStoneButton(button)) {
			failures.add("stone button missing at " + button.shortString());
		}
	}

	private static void verifySign(ServerLevel level, BlockLocation sign, String fromName,
			String toName, List<String> failures) {
		BlockState state = level.getBlockState(RailWorld.pos(sign));
		if (!(state.getBlock() instanceof WallSignBlock)) {
			failures.add("wall sign missing at " + sign.shortString());
			return;
		}
		if (state.getValue(WallSignBlock.FACING) != Direction.NORTH) {
			failures.add("wall sign at " + sign.shortString() + " does not face north");
		}
		BlockEntity entity = level.getBlockEntity(RailWorld.pos(sign));
		if (!(entity instanceof SignBlockEntity signEntity)) {
			failures.add("sign text missing at " + sign.shortString());
			return;
		}
		String[] expected = {SignFormat.MARKER, fromName, toName, ""};
		for (int line = 0; line < expected.length; line++) {
			String actual = signEntity.getText(true).getMessage(line, false).getString();
			if (!expected[line].equals(actual)) {
				failures.add("sign " + sign.shortString() + " line " + (line + 1)
						+ " is \"" + actual + "\", expected \"" + expected[line] + "\"");
			}
		}
	}

	private static void set(ServerLevel level, BlockLocation location, BlockState state) {
		level.setBlock(RailWorld.pos(location), state, 2);
	}

	/** Returns the fixture station ids so callers can invalidate their scans. */
	public static List<StationId> stationIds() {
		return List.of(new StationId(HOME_ID), new StationId(MINE_ID));
	}
}
