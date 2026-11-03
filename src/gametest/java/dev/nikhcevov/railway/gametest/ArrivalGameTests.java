package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.ArrivalPlatform;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.minecraft.TripService;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Per-origin arrival platforms. A destination can register a distinct arrival
 * dock and exit for each source station; a source without one keeps using the
 * destination main dock and exit.
 *
 * <p>The GameTest plot is 8x8 blocks, so the line is compact: home dock x=0,
 * destination main dock x=4, east dock x=7. The home platform dock is x=2 and
 * the east platform dock x=5, leaving the two straight horizontal rails the
 * planner requires before each arrival dock.
 */
public final class ArrivalGameTests extends GameTestSupport {
	private static final int LINE_END = 7;
	private static final int HOME_DOCK_X = 0;
	private static final int DEST_DOCK_X = 4;
	private static final int EAST_DOCK_X = 7;
	private static final int HOME_PLATFORM_DOCK_X = 2;
	private static final int EAST_PLATFORM_DOCK_X = 5;

	@GameTest(maxTicks = 900)
	public void twoOriginsUseTheirOwnArrivalPlatforms(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, 0, true, "own");
		ServerPlayer fromHome = fixture.newPlayer(helper, fixture.homeExit());
		ServerPlayer fromEast = fixture.newPlayer(helper, fixture.eastExit());
		TripId[] trip = {null};
		int[] phase = {0};
		helper.onEachTick(() -> {
			Railway railway = fixture.railway();
			switch (phase[0]) {
				case 0 -> {
					trip[0] = startWhenReady(helper, fixture, fromHome, fixture.homeToDest());
					if (trip[0] != null) {
						phase[0] = 1;
					}
				}
				case 1 -> {
					if (tripActive(railway, trip[0])) {
						return;
					}
					assertAt(helper, fromHome, fixture.homeArrivalExit());
					requireNoManagedCart(helper, fixture.level());
					phase[0] = 2;
				}
				case 2 -> {
					trip[0] = startWhenReady(helper, fixture, fromEast, fixture.eastToDest());
					if (trip[0] != null) {
						phase[0] = 3;
					}
				}
				default -> {
					if (tripActive(railway, trip[0])) {
						return;
					}
					assertAt(helper, fromEast, fixture.eastArrivalExit());
					requireNoManagedCart(helper, fixture.level());
					phase[0] = 4;
					helper.succeed();
				}
			}
		});
	}

	@GameTest(maxTicks = 900)
	public void originWithoutPlatformFallsBackToMainExit(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, 4, false, "fallback");
		ServerPlayer fromHome = fixture.newPlayer(helper, fixture.homeExit());
		ServerPlayer fromEast = fixture.newPlayer(helper, fixture.eastExit());
		TripId[] trip = {null};
		int[] phase = {0};
		helper.onEachTick(() -> {
			Railway railway = fixture.railway();
			switch (phase[0]) {
				case 0 -> {
					trip[0] = startWhenReady(helper, fixture, fromHome, fixture.homeToDest());
					if (trip[0] != null) {
						phase[0] = 1;
					}
				}
				case 1 -> {
					if (tripActive(railway, trip[0])) {
						return;
					}
					assertAt(helper, fromHome, fixture.homeArrivalExit());
					requireNoManagedCart(helper, fixture.level());
					phase[0] = 2;
				}
				case 2 -> {
					trip[0] = startWhenReady(helper, fixture, fromEast, fixture.eastToDest());
					if (trip[0] != null) {
						phase[0] = 3;
					}
				}
				default -> {
					if (tripActive(railway, trip[0])) {
						return;
					}
					assertAt(helper, fromEast, fixture.mainExit());
					requireNoManagedCart(helper, fixture.level());
					phase[0] = 4;
					helper.succeed();
				}
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void commandRemovesArrivalPlatform(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, 0, true, "cmd");
		String command = "rail station arrival remove " + fixture.dest().id().value()
				+ " " + fixture.home().id().value();
		int result;
		try {
			result = fixture.level().getServer().getCommands().getDispatcher()
					.execute(command, fixture.level().getServer().createCommandSourceStack());
		} catch (Exception e) {
			throw helper.assertionException("arrival remove command failed: " + e);
		}
		if (result != 1) {
			throw helper.assertionException("arrival remove command returned " + result);
		}
		Station dest = fixture.railway().state().station(fixture.dest().id()).orElseThrow();
		if (dest.arrivalFrom(fixture.home().id()).isPresent()) {
			throw helper.assertionException("home arrival platform was not removed");
		}
		if (dest.arrivalFrom(fixture.east().id()).isEmpty()) {
			throw helper.assertionException("unrelated east arrival platform was removed");
		}
		helper.succeed();
	}

	// ----------------------------------------------------------------- fixture

	private record Fixture(Railway railway, ServerLevel level, Station home, Station east, Station dest,
			Binding homeToDest, Binding eastToDest, BlockLocation homeExit, BlockLocation eastExit,
			BlockLocation mainExit, BlockLocation homeArrivalExit, BlockLocation eastArrivalExit) {
		ServerPlayer newPlayer(GameTestHelper helper, BlockLocation position) {
			ServerPlayer player = helper.makeMockServerPlayerInLevel();
			player.teleportTo(position.x() + 0.5, position.y(), position.z() + 0.5);
			return player;
		}
	}

	private static Fixture buildFixture(GameTestHelper helper, int z, boolean eastPlatform, String suffix) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = 0; x <= LINE_END; x++) {
			helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
			level.setBlock(helper.absolutePos(new BlockPos(x, 1, z)), rail, 2);
		}
		int exitZ = z + 2;
		for (int x : new int[]{HOME_DOCK_X, HOME_PLATFORM_DOCK_X, DEST_DOCK_X,
				EAST_PLATFORM_DOCK_X, EAST_DOCK_X}) {
			helper.setBlock(new BlockPos(x, 0, exitZ), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, exitZ), Blocks.AIR);
			helper.setBlock(new BlockPos(x, 2, exitZ), Blocks.AIR);
		}

		String unique = unique() + suffix;
		StationId homeId = new StationId("gtarrhome" + unique);
		StationId eastId = new StationId("gtarreast" + unique);
		StationId destId = new StationId("gtarrdest" + unique);
		String dimension = RailWorld.dimension(level);

		BlockLocation homeDock = absolute(helper, HOME_DOCK_X, 1, z);
		BlockLocation eastDock = absolute(helper, EAST_DOCK_X, 1, z);
		BlockLocation destDock = absolute(helper, DEST_DOCK_X, 1, z);
		BlockLocation homeExit = absolute(helper, HOME_DOCK_X, 1, exitZ);
		BlockLocation eastExit = absolute(helper, EAST_DOCK_X, 1, exitZ);
		BlockLocation mainExit = absolute(helper, DEST_DOCK_X, 1, exitZ);
		BlockLocation homePlatformDock = absolute(helper, HOME_PLATFORM_DOCK_X, 1, z);
		BlockLocation homePlatformExit = absolute(helper, HOME_PLATFORM_DOCK_X, 1, exitZ);
		BlockLocation eastPlatformDock = absolute(helper, EAST_PLATFORM_DOCK_X, 1, z);
		BlockLocation eastPlatformExit = absolute(helper, EAST_PLATFORM_DOCK_X, 1, exitZ);

		Station home = new Station(homeId, "Game Test Arrival Home " + unique, dimension,
				homeDock, homeExit, 0.0F, true, railway.state().nextRevision());
		Station east = new Station(eastId, "Game Test Arrival East " + unique, dimension,
				eastDock, eastExit, 0.0F, true, railway.state().nextRevision());
		Station dest = new Station(destId, "Game Test Arrival Dest " + unique, dimension,
				destDock, mainExit, 0.0F, true, railway.state().nextRevision());
		railway.state().addStation(home);
		railway.state().addStation(east);
		railway.state().addStation(dest);

		// Register the per-origin platforms through the station aggregate; the
		// command that normally does this is intentionally not involved.
		dest = dest.withArrivalPlatform(new ArrivalPlatform(homeId, homePlatformDock,
				homePlatformExit, 0.0F), railway.state().nextRevision());
		railway.state().updateStation(dest);
		if (eastPlatform) {
			dest = dest.withArrivalPlatform(new ArrivalPlatform(eastId, eastPlatformDock,
					eastPlatformExit, 0.0F), railway.state().nextRevision());
			railway.state().updateStation(dest);
		}
		// Bindings are command-style (no source dock); the button block itself only
		// has to exist and keep the expected id. Keep it off the passenger pads.
		BlockLocation homeButton = absolute(helper, HOME_DOCK_X, 1, z + 1);
		BlockLocation eastButton = absolute(helper, EAST_DOCK_X, 1, z + 1);
		for (int x : new int[]{HOME_DOCK_X, EAST_DOCK_X}) {
			helper.setBlock(new BlockPos(x, 0, z + 1), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, z + 1), Blocks.AIR);
			level.setBlock(helper.absolutePos(new BlockPos(x, 1, z + 1)),
					Blocks.STONE_BUTTON.defaultBlockState(), 2);
		}
		Binding homeToDest = new Binding(dimension, homeButton, "minecraft:stone_button", homeId, destId);
		Binding eastToDest = new Binding(dimension, eastButton, "minecraft:stone_button", eastId, destId);
		railway.state().addBinding(homeToDest);
		railway.state().addBinding(eastToDest);

		ScanService scans = railway.scans();
		scan(helper, scans, home, level, server);
		scan(helper, scans, east, level, server);
		scan(helper, scans, dest, level, server);

		RailWorld world = new RailWorld(level);
		for (BlockLocation exit : new BlockLocation[]{homeExit, eastExit, mainExit,
				homePlatformExit, eastPlatformExit}) {
			if (!world.isSafeStand(exit)) {
				helper.fail("arrival test exit is not safe: " + exit.shortString());
			}
		}
		return new Fixture(railway, level, home, east, dest, homeToDest, eastToDest,
				homeExit, eastExit, mainExit, homePlatformExit, eastPlatformExit);
	}

	/** Returns null while the shared server has no free active-trip slot. */
	private static TripId startWhenReady(GameTestHelper helper, Fixture fixture, ServerPlayer player,
			Binding binding) {
		Railway railway = fixture.railway();
		if (railway.trips().activeCount() >= railway.config().maxActiveTrips() - 1) {
			return null;
		}
		TripService.StartResult result = railway.trips().start(player, binding);
		if (result instanceof TripService.StartResult.Rejected rejected) {
			if (rejected.failure().code() == FailureCode.TRIP_LIMIT
					|| rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
				return null;
			}
			throw helper.assertionException("trip start rejected: " + rejected.failure().code()
					+ (rejected.failure().detail() == null ? "" : " " + rejected.failure().detail()));
		}
		return ((TripService.StartResult.Started) result).id();
	}

	private static void assertAt(GameTestHelper helper, ServerPlayer player, BlockLocation exit) {
		if (!player.blockPosition().equals(RailWorld.pos(exit))) {
			throw helper.assertionException("player at " + player.blockPosition()
					+ ", expected arrival exit " + exit.shortString());
		}
		if (player.getVehicle() != null) {
			throw helper.assertionException("passenger was not dismounted at " + exit.shortString());
		}
	}
}
