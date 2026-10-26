package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.minecraft.TripService;
import dev.nikhcevov.railway.network.RailShapeType;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Stage-2 end-to-end tests: a three-station T network, a curve+slope trip and
 * an external turnout change while a cart is approaching.
 */
public final class TurnoutGameTests implements CustomTestMethodInvoker {
	private static final String BUTTON_ID = "minecraft:stone_button";

	@GameTest(maxTicks = 3000)
	public void threeStationNetworkCoversAllPairs(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		Network network = buildNetwork(helper);

		ScanService scans = railway.scans();
		scan(helper, scans, network.home(), level, server);
		scan(helper, scans, network.east(), level, server);
		scan(helper, scans, network.south(), level, server);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(network.homeExit().x() + 0.5, network.homeExit().y(), network.homeExit().z() + 0.5);

		List<Pair> pairs = List.of(
				new Pair(network.home(), network.south(), network.southExit()),
				new Pair(network.south(), network.east(), network.eastExit()),
				new Pair(network.east(), network.home(), network.homeExit()),
				new Pair(network.home(), network.east(), network.eastExit()),
				new Pair(network.east(), network.south(), network.southExit()),
				new Pair(network.south(), network.home(), network.homeExit())
		);
		int[] completed = {0};
		TripId[] current = {null};
		BlockLocation[] expectedExit = {null};
		helper.onEachTick(() -> {
			if (current[0] != null) {
				if (tripActive(railway, current[0])) {
					return;
				}
				if (!player.blockPosition().equals(RailWorld.pos(expectedExit[0]))) {
					throw helper.assertionException("trip " + completed[0] + " ended at " + player.blockPosition()
							+ ", expected " + expectedExit[0].shortString());
				}
				requireNoManagedCart(helper, level);
				completed[0]++;
				current[0] = null;
				if (completed[0] >= pairs.size()) {
					helper.succeed();
					return;
				}
			}
			if (railway.trips().activeCount() >= railway.config().maxActiveTrips() - 1) {
				return;
			}
			Pair pair = pairs.get(completed[0]);
			Binding binding = new Binding(railway.state().station(pair.from().id()).orElseThrow().dimension(),
					network.button(), BUTTON_ID, pair.from().id(), pair.to().id());
			expectedExit[0] = pair.exit();
			TripService.StartResult start = railway.trips().start(player, binding);
			if (start instanceof TripService.StartResult.Rejected rejected) {
				if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				throw helper.assertionException("trip " + completed[0] + " rejected: " + rejected.failure().code()
						+ " " + rejected.failure().detail());
			}
			current[0] = ((TripService.StartResult.Started) start).id();
		});
	}

	@GameTest(maxTicks = 500)
	public void curveAndSlopeTripArrives(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		CurveNetwork network = buildCurveNetwork(helper);
		scan(helper, railway.scans(), network.home(), level, server);
		scan(helper, railway.scans(), network.south(), level, server);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(network.homeExit().x() + 0.5, network.homeExit().y(), network.homeExit().z() + 0.5);
		String dimension = RailWorld.dimension(level);
		Binding binding = new Binding(dimension, network.button(), BUTTON_ID,
				network.home().id(), network.south().id());
		TripId[] trip = {null};
		helper.onEachTick(() -> {
			if (trip[0] != null) {
				return;
			}
			if (railway.trips().activeCount() >= railway.config().maxActiveTrips() - 1) {
				return;
			}
			TripService.StartResult start = railway.trips().start(player, binding);
			if (start instanceof TripService.StartResult.Rejected rejected) {
				if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				throw helper.assertionException("trip rejected: " + rejected.failure().code() + " "
						+ rejected.failure().detail());
			}
			trip[0] = ((TripService.StartResult.Started) start).id();
		});
		helper.succeedWhen(() -> {
			if (trip[0] == null) {
				throw helper.assertionException("curve/slope trip not started");
			}
			if (tripActive(railway, trip[0])) {
				throw helper.assertionException("curve/slope trip still active");
			}
			if (!player.blockPosition().equals(RailWorld.pos(network.southExit()))) {
				throw helper.assertionException("player at " + player.blockPosition() + ", expected "
						+ network.southExit().shortString());
			}
			requireNoManagedCart(helper, level);
		});
	}

	@GameTest(maxTicks = 600)
	public void externalTurnoutChangeAbortsBeforeEntry(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		Network network = buildNetwork(helper);
		scan(helper, railway.scans(), network.home(), level, server);
		scan(helper, railway.scans(), network.south(), level, server);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(network.homeExit().x() + 0.5, network.homeExit().y(), network.homeExit().z() + 0.5);
		String dimension = RailWorld.dimension(level);
		Binding binding = new Binding(dimension, network.button(), BUTTON_ID,
				network.home().id(), network.south().id());
		TripId[] trip = {null};
		helper.onEachTick(() -> {
			if (trip[0] != null) {
				return;
			}
			if (railway.trips().activeCount() >= railway.config().maxActiveTrips() - 1) {
				return;
			}
			TripService.StartResult start = railway.trips().start(player, binding);
			if (start instanceof TripService.StartResult.Rejected rejected) {
				if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				throw helper.assertionException("trip rejected: " + rejected.failure().code() + " "
						+ rejected.failure().detail());
			}
			trip[0] = ((TripService.StartResult.Started) start).id();
			helper.runAfterDelay(12, () -> {
				if (!new RailWorld(level).setRailShape(network.junction(), RailShapeType.SOUTH_EAST)) {
					helper.fail("could not change turnout externally");
				}
			});
		});
		helper.succeedWhen(() -> {
			if (trip[0] == null) {
				throw helper.assertionException("turnout trip not started");
			}
			if (tripActive(railway, trip[0])) {
				throw helper.assertionException("trip continued after external turnout change");
			}
			if (player.blockPosition().equals(RailWorld.pos(network.southExit()))) {
				throw helper.assertionException("trip completed despite turnout change");
			}
			if (new RailWorld(level).shapeAt(network.junction()) != RailShapeType.SOUTH_EAST) {
				throw helper.assertionException("turnout was silently rewritten");
			}
			requireNoManagedCart(helper, level);
		});
	}


	@GameTest(maxTicks = 1200)
	public void conflictingDestinationsDoNotOverwriteTurnout(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		Network network = buildNetwork(helper);
		scan(helper, railway.scans(), network.home(), level, server);
		scan(helper, railway.scans(), network.east(), level, server);
		scan(helper, railway.scans(), network.south(), level, server);

		ServerPlayer firstPlayer = helper.makeMockServerPlayerInLevel();
		firstPlayer.teleportTo(network.homeExit().x() + 0.5, network.homeExit().y(),
				network.homeExit().z() + 0.5);
		ServerPlayer secondPlayer = helper.makeMockServerPlayerInLevel();
		secondPlayer.teleportTo(network.homeExit().x() + 0.5, network.homeExit().y(),
				network.homeExit().z() + 0.5);

		String dimension = RailWorld.dimension(level);
		Binding toSouth = new Binding(dimension, network.button(), BUTTON_ID,
				network.home().id(), network.south().id());
		Binding toEast = new Binding(dimension, network.button(), BUTTON_ID,
				network.home().id(), network.east().id());
		TripId[] first = {null};
		helper.onEachTick(() -> {
			if (first[0] == null) {
				if (railway.trips().activeCount() >= railway.config().maxActiveTrips() - 1) {
					return;
				}
				TripService.StartResult started = railway.trips().start(firstPlayer, toSouth);
				if (started instanceof TripService.StartResult.Rejected rejected) {
					if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
						return;
					}
					throw helper.assertionException("first trip rejected: " + rejected.failure().code());
				}
				first[0] = ((TripService.StartResult.Started) started).id();

				TripService.StartResult second = railway.trips().start(secondPlayer, toEast);
				if (!(second instanceof TripService.StartResult.Rejected rejected)) {
					throw helper.assertionException("second trip unexpectedly started");
				}
				Set<FailureCode> expected = Set.of(FailureCode.DOCK_OCCUPIED, FailureCode.PATH_OCCUPIED,
						FailureCode.RESERVATION_CONFLICT);
				if (!expected.contains(rejected.failure().code())) {
					throw helper.assertionException("unexpected second rejection: " + rejected.failure().code());
				}
				if (new RailWorld(level).shapeAt(network.junction()) != RailShapeType.SOUTH_WEST) {
					throw helper.assertionException("second request overwrote the selected turnout");
				}
				return;
			}
			if (tripActive(railway, first[0])) {
				return;
			}
			requireNoManagedCart(helper, level);
			helper.succeed();
		});
	}

	// --------------------------------------------------------------- fixtures

	private record Pair(Station from, Station to, BlockLocation exit) {
	}

	private record Network(Railway railway, ServerLevel level, Station home, Station east, Station south,
			BlockLocation homeExit, BlockLocation eastExit, BlockLocation southExit, BlockLocation junction,
			BlockLocation button) {
	}

	private record CurveNetwork(Railway railway, ServerLevel level, Station home, Station south,
			BlockLocation homeExit, BlockLocation southExit, BlockLocation button) {
	}

	private static Network buildNetwork(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		String unique = unique();
		int y = 1;
		for (int x = 0; x <= 7; x++) {
			support(helper, x, y - 1, 0);
			rail(helper, x, y, 0, RailShapeType.EAST_WEST);
		}
		for (int z = 1; z <= 4; z++) {
			support(helper, 3, y - 1, z);
			rail(helper, 3, y, z, RailShapeType.NORTH_SOUTH);
		}
		// Turnout starts as a straight rail; the scanner derives the two curves.
		rail(helper, 3, y, 0, RailShapeType.EAST_WEST);
		exitPlatform(helper, 0, y, 2);
		exitPlatform(helper, 7, y, 2);
		exitPlatform(helper, 5, y, 4);

		String dimension = RailWorld.dimension(level);
		BlockLocation junction = absolute(helper, 3, y, 0);
		BlockLocation button = absolute(helper, 0, y, 3);
		level.setBlock(RailWorld.pos(button), Blocks.STONE_BUTTON.defaultBlockState(), 2);

		Station home = new Station(new StationId("th" + unique), "Turnout Home " + unique, dimension,
				absolute(helper, 0, y, 0), absolute(helper, 0, y, 2), 0.0F, true,
				railway.state().nextRevision());
		Station east = new Station(new StationId("te" + unique), "Turnout East " + unique, dimension,
				absolute(helper, 7, y, 0), absolute(helper, 7, y, 2), 0.0F, true,
				railway.state().nextRevision());
		Station south = new Station(new StationId("ts" + unique), "Turnout South " + unique, dimension,
				absolute(helper, 3, y, 4), absolute(helper, 5, y, 4), 0.0F, true,
				railway.state().nextRevision());
		railway.state().addStation(home);
		railway.state().addStation(east);
		railway.state().addStation(south);
		return new Network(railway, level, home, east, south,
				home.exit(), east.exit(), south.exit(), junction, button);
	}

	private static CurveNetwork buildCurveNetwork(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		String unique = unique();
		support(helper, 0, 0, 0);
		support(helper, 1, 0, 0);
		support(helper, 2, 0, 0);
		rail(helper, 0, 1, 0, RailShapeType.EAST_WEST);
		rail(helper, 1, 1, 0, RailShapeType.EAST_WEST);
		rail(helper, 2, 1, 0, RailShapeType.SOUTH_WEST);
		for (int z = 1; z <= 3; z++) {
			support(helper, 2, 0, z);
		}
		rail(helper, 2, 1, 1, RailShapeType.NORTH_SOUTH);
		rail(helper, 2, 1, 2, RailShapeType.NORTH_SOUTH);
		rail(helper, 2, 1, 3, RailShapeType.ASCENDING_SOUTH);
		for (int z = 4; z <= 6; z++) {
			support(helper, 2, 1, z);
		}
		rail(helper, 2, 2, 4, RailShapeType.NORTH_SOUTH);
		rail(helper, 2, 2, 5, RailShapeType.NORTH_SOUTH);
		rail(helper, 2, 2, 6, RailShapeType.NORTH_SOUTH);
		exitPlatform(helper, 0, 1, 2);
		exitPlatform(helper, 4, 2, 6);

		String dimension = RailWorld.dimension(level);
		BlockLocation button = absolute(helper, 0, 1, 3);
		level.setBlock(RailWorld.pos(button), Blocks.STONE_BUTTON.defaultBlockState(), 2);
		Station home = new Station(new StationId("ch" + unique), "Curve Home " + unique, dimension,
				absolute(helper, 0, 1, 0), absolute(helper, 0, 1, 2), 0.0F, true,
				railway.state().nextRevision());
		Station south = new Station(new StationId("cs" + unique), "Curve South " + unique, dimension,
				absolute(helper, 2, 2, 6), absolute(helper, 4, 2, 6), 0.0F, true,
				railway.state().nextRevision());
		railway.state().addStation(home);
		railway.state().addStation(south);
		return new CurveNetwork(railway, level, home, south, home.exit(), south.exit(), button);
	}

	private static void scan(GameTestHelper helper, ScanService scans, Station station, ServerLevel level,
			MinecraftServer server) {
		ScanService.StartResult result = scans.start(station, level, Railway.get().config(), server.getTickCount(),
				Railway.get().state().revision());
		if (!(result instanceof ScanService.StartResult.Started)) {
			helper.fail("scan did not start: " + result);
		}
		for (int i = 0; i < 64 && scans.isRunning(station.id()); i++) {
			scans.tick(server);
		}
		if (scans.graph(station.id()) == null) {
			helper.fail("scan failed for " + station.id() + ": " + scans.failure(station.id()));
		}
	}

	private static void rail(GameTestHelper helper, int x, int y, int z, RailShapeType shape) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		helper.getLevel().setBlock(pos, Blocks.RAIL.defaultBlockState()
				.setValue(RailBlock.SHAPE, RailWorld.vanillaShape(shape)), 2);
	}

	private static void support(GameTestHelper helper, int x, int y, int z) {
		helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
	}

	private static void exitPlatform(GameTestHelper helper, int x, int y, int z) {
		support(helper, x, y - 1, z);
		helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
		helper.setBlock(new BlockPos(x, y + 1, z), Blocks.AIR);
	}

	private static BlockLocation absolute(GameTestHelper helper, int x, int y, int z) {
		return RailWorld.loc(helper.absolutePos(new BlockPos(x, y, z)));
	}

	private static boolean tripActive(Railway railway, TripId trip) {
		for (var active : railway.trips().activeTrips()) {
			if (active.id().equals(trip)) {
				return true;
			}
		}
		return false;
	}

	private static void requireNoManagedCart(GameTestHelper helper, ServerLevel level) {
		EntityTypeTest<Entity, Minecart> carts = EntityTypeTest.forClass(Minecart.class);
		if (!level.getEntities(carts, helper.absoluteAABB(helper.getBounds().inflate(2.0)),
				cart -> cart.entityTags().contains(PlayerActions.MANAGED_TAG)).isEmpty()) {
			throw helper.assertionException("service cart was not removed");
		}
	}

	private static String unique() {
		return Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
