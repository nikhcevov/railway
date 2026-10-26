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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * End-to-end server game tests. Each test builds its own straight line inside the
 * empty 8x8 test plot, registers stations and launches a real server player.
 */
public final class RailwayGameTests implements CustomTestMethodInvoker {
	private static final int LINE_END = 7;
	private static final int HOME_DOCK = 1;
	private static final int MINE_DOCK = 6;

	@GameTest(maxTicks = 400)
	public void passengerTravelsHomeToMine(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId trip = startOrFail(helper, fixture, player, fixture.homeToMine);
		assertArrival(helper, fixture, player, fixture.mineExit, trip);
	}

	@GameTest(maxTicks = 400)
	public void passengerTravelsMineToHome(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "r");
		ServerPlayer player = fixture.newPlayer(helper, fixture.mineExit);
		TripId trip = startOrFail(helper, fixture, player, fixture.mineToHome);
		assertArrival(helper, fixture, player, fixture.homeExit, trip);
	}

	@GameTest(maxTicks = 400)
	public void dismountDuringTripCleansUp(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "s");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId trip = startOrFail(helper, fixture, player, fixture.homeToMine);
		helper.runAtTickTime(25, () -> {
			if (player.getVehicle() == null) {
				helper.fail("passenger was not mounted before dismount");
			}
			player.stopRiding();
		});
		helper.succeedWhen(() -> {
			requireTripFinished(helper, fixture.railway, trip);
			requireNoManagedCart(helper, fixture.level);
		});
	}

	@GameTest(maxTicks = 400)
	public void secondPlayerCannotStartSameLine(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "c");
		ServerPlayer first = fixture.newPlayer(helper, fixture.homeExit);
		ServerPlayer second = fixture.newPlayer(helper, fixture.homeExit);
		TripId trip = startOrFail(helper, fixture, first, fixture.homeToMine);
		TripService.StartResult secondStart = fixture.railway.trips().start(second, fixture.homeToMine);
		if (!(secondStart instanceof TripService.StartResult.Rejected rejected)) {
			helper.fail("second launch unexpectedly succeeded");
			return;
		}
		Set<FailureCode> expected = Set.of(FailureCode.DOCK_OCCUPIED, FailureCode.PATH_OCCUPIED,
				FailureCode.RESERVATION_CONFLICT);
		if (!expected.contains(rejected.failure().code())) {
			helper.fail("unexpected rejection: " + rejected.failure().code());
		}
		helper.succeedWhen(() -> {
			requireTripFinished(helper, fixture.railway, trip);
			requireNoManagedCart(helper, fixture.level);
		});
	}

	@GameTest(maxTicks = 400)
	public void brokenRailAbortsSafely(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "b");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId trip = startOrFail(helper, fixture, player, fixture.homeToMine);
		helper.runAtTickTime(25, () -> {
			BlockPos broken = helper.absolutePos(new BlockPos(MINE_DOCK - 2, 1, 0));
			helper.getLevel().setBlock(broken, Blocks.AIR.defaultBlockState(), 3);
		});
		helper.succeedWhen(() -> {
			requireTripFinished(helper, fixture.railway, trip);
			requireNoManagedCart(helper, fixture.level);
		});
	}

	@GameTest(maxTicks = 400)
	public void disconnectCleansUpTrip(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "d");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId trip = startOrFail(helper, fixture, player, fixture.homeToMine);
		helper.runAtTickTime(20, () -> fixture.railway().trips().playerLeft(player.getUUID()));
		helper.succeedWhen(() -> {
			requireTripFinished(helper, fixture.railway(), trip);
			requireNoManagedCart(helper, fixture.level());
		});
	}

	@GameTest(maxTicks = 3000)
	public void twentyTripsLeaveNoCartOrReservation(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "t");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		int[] completed = {0};
		TripId[] current = {null};
		BlockLocation[] expectedExit = {null};
		helper.onEachTick(() -> {
			Railway railway = fixture.railway();
			if (current[0] != null) {
				if (tripActive(railway, current[0])) {
					return;
				}
				if (!player.blockPosition().equals(RailWorld.pos(expectedExit[0]))) {
					throw helper.assertionException("trip " + completed[0] + " ended at " + player.blockPosition()
							+ ", expected " + expectedExit[0].shortString());
				}
				requireNoManagedCart(helper, fixture.level());
				completed[0]++;
				current[0] = null;
				if (completed[0] >= 20) {
					helper.succeed();
					return;
				}
			}
			boolean forward = completed[0] % 2 == 0;
			Binding binding = forward ? fixture.homeToMine() : fixture.mineToHome();
			expectedExit[0] = forward ? fixture.mineExit() : fixture.homeExit();
			TripService.StartResult start = railway.trips().start(player, binding);
			if (start instanceof TripService.StartResult.Rejected rejected) {
				throw helper.assertionException("trip " + completed[0] + " rejected: " + rejected.failure().code());
			}
			current[0] = ((TripService.StartResult.Started) start).id();
		});
	}

	@GameTest(maxTicks = 100)
	public void unsafeDestinationExitRejectsLaunch(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "e");
		BlockPos support = helper.absolutePos(new BlockPos(MINE_DOCK, 0, 2));
		helper.getLevel().setBlock(support, Blocks.AIR.defaultBlockState(), 3);
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripService.StartResult result = fixture.railway.trips().start(player, fixture.homeToMine);
		if (!(result instanceof TripService.StartResult.Rejected rejected)
				|| rejected.failure().code() != FailureCode.EXIT_UNSAFE) {
			helper.fail("expected EXIT_UNSAFE, got " + result);
		}
		requireNoManagedCart(helper, fixture.level);
		helper.succeed();
	}

	// ----------------------------------------------------------------- fixture

	private record Fixture(Railway railway, ServerLevel level, Station home, Station mine,
			Binding homeToMine, Binding mineToHome, BlockLocation homeExit, BlockLocation mineExit) {
		ServerPlayer newPlayer(GameTestHelper helper, BlockLocation position) {
			ServerPlayer player = helper.makeMockServerPlayerInLevel();
			player.teleportTo(position.x() + 0.5, position.y(), position.z() + 0.5);
			return player;
		}
	}

	private static Fixture buildFixture(GameTestHelper helper, String suffix) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		buildLine(helper);

		String unique = Long.toString(System.nanoTime() & 0xFFFFFFL, 36) + suffix;
		StationId homeId = new StationId("gthome" + unique);
		StationId mineId = new StationId("gtmine" + unique);
		BlockLocation homeDock = absolute(helper, HOME_DOCK, 1, 0);
		BlockLocation mineDock = absolute(helper, MINE_DOCK, 1, 0);
		BlockLocation homeExit = absolute(helper, HOME_DOCK, 1, 2);
		BlockLocation mineExit = absolute(helper, MINE_DOCK, 1, 2);
		String dimension = RailWorld.dimension(level);

		Station home = new Station(homeId, "Game Test Home", dimension, homeDock, homeExit, 0.0F, true,
				railway.state().nextRevision());
		Station mine = new Station(mineId, "Game Test Mine", dimension, mineDock, mineExit, 0.0F, true,
				railway.state().nextRevision());
		railway.state().addStation(home);
		railway.state().addStation(mine);

		BlockLocation homeButton = absolute(helper, 0, 1, 2);
		BlockLocation mineButton = absolute(helper, LINE_END, 1, 2);
		level.setBlock(RailWorld.pos(homeButton), Blocks.STONE_BUTTON.defaultBlockState(), 2);
		level.setBlock(RailWorld.pos(mineButton), Blocks.STONE_BUTTON.defaultBlockState(), 2);
		Binding homeToMine = new Binding(dimension, homeButton, "minecraft:stone_button", homeId, mineId);
		Binding mineToHome = new Binding(dimension, mineButton, "minecraft:stone_button", mineId, homeId);
		railway.state().addBinding(homeToMine);
		railway.state().addBinding(mineToHome);

		ScanService scans = railway.scans();
		scan(helper, scans, home, level, server);
		scan(helper, scans, mine, level, server);
		if (!new RailWorld(level).isSafeStand(mineExit) || !new RailWorld(level).isSafeStand(homeExit)) {
			helper.fail("test exits are not safe");
		}
		return new Fixture(railway, level, home, mine, homeToMine, mineToHome, homeExit, mineExit);
	}

	private static TripId startOrFail(GameTestHelper helper, Fixture fixture, ServerPlayer player, Binding binding) {
		TripService.StartResult start = fixture.railway().trips().start(player, binding);
		if (start instanceof TripService.StartResult.Rejected rejected) {
			helper.fail("trip start rejected: " + rejected.failure().code() + " "
					+ (rejected.failure().detail() == null ? "" : rejected.failure().detail())
					+ (rejected.failure().location() == null ? "" : " at " + rejected.failure().location().shortString()));
			throw new IllegalStateException("unreachable");
		}
		return ((TripService.StartResult.Started) start).id();
	}

	private static void assertArrival(GameTestHelper helper, Fixture fixture, ServerPlayer player, BlockLocation exit,
			TripId trip) {
		helper.runAtTickTime(5, () -> {
			if (player.getVehicle() == null) {
				helper.fail("passenger was not mounted");
			}
		});
		helper.succeedWhen(() -> {
			requireTripFinished(helper, fixture.railway(), trip);
			if (!player.blockPosition().equals(RailWorld.pos(exit))) {
				throw helper.assertionException("player at " + player.blockPosition() + ", expected " + exit.shortString());
			}
			requireNoManagedCart(helper, fixture.level());
		});
	}

	private static boolean tripActive(Railway railway, TripId trip) {
		for (var active : railway.trips().activeTrips()) {
			if (active.id().equals(trip)) {
				return true;
			}
		}
		return false;
	}

	private static void requireTripFinished(GameTestHelper helper, Railway railway, TripId trip) {
		for (var active : railway.trips().activeTrips()) {
			if (active.id().equals(trip)) {
				throw helper.assertionException("trip still active: " + active.phase());
			}
		}
	}

	private static void requireNoManagedCart(GameTestHelper helper, ServerLevel level) {
		EntityTypeTest<Entity, Minecart> carts = EntityTypeTest.forClass(Minecart.class);
		if (!level.getEntities(carts, helper.absoluteAABB(helper.getBounds().inflate(2.0)),
				cart -> cart.entityTags().contains(PlayerActions.MANAGED_TAG)).isEmpty()) {
			throw helper.assertionException("service cart was not removed");
		}
	}

	private static void scan(GameTestHelper helper, ScanService scans, Station station, ServerLevel level,
			MinecraftServer server) {
		ScanService.StartResult result = scans.start(station, level, Railway.get().config(), server.getTickCount(),
				Railway.get().state().revision());
		if (!(result instanceof ScanService.StartResult.Started)) {
			helper.fail("scan did not start: " + result);
		}
		for (int i = 0; i < 32 && scans.isRunning(station.id()); i++) {
			scans.tick(server);
		}
		if (scans.graph(station.id()) == null) {
			helper.fail("scan failed for " + station.id() + ": " + scans.failure(station.id()));
		}
	}

	private static void buildLine(GameTestHelper helper) {
		for (int x = 0; x <= LINE_END; x++) {
			helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, 0), Blocks.AIR);
		}
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = 0; x <= LINE_END; x++) {
			helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 1, 0)), rail, 2);
		}
		for (int x : new int[]{HOME_DOCK, MINE_DOCK}) {
			helper.setBlock(new BlockPos(x, 0, 2), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, 2), Blocks.AIR);
			helper.setBlock(new BlockPos(x, 2, 2), Blocks.AIR);
		}
	}

	private static BlockLocation absolute(GameTestHelper helper, int x, int y, int z) {
		return RailWorld.loc(helper.absolutePos(new BlockPos(x, y, z)));
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
