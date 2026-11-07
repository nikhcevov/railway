package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.minecraft.ScanTrigger;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.lang.reflect.Method;

/** Shared scaffolding for the Railway GameTest suites. */
abstract class GameTestSupport implements CustomTestMethodInvoker {
	private static final int SCAN_TICK_LIMIT = 64;

	static BlockLocation absolute(GameTestHelper helper, int x, int y, int z) {
		return RailWorld.loc(helper.absolutePos(new BlockPos(x, y, z)));
	}

	static String unique() {
		return Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
	}

	/** Builds a straight east-west line with cleared station pads behind it. */
	static void buildLine(GameTestHelper helper, int endX, int z, int firstDockX, int secondDockX) {
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = 0; x <= endX; x++) {
			helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
			helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 1, z)), rail, 2);
		}
		for (int x : new int[]{firstDockX, secondDockX}) {
			helper.setBlock(new BlockPos(x, 0, z + 2), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, z + 2), Blocks.AIR);
			helper.setBlock(new BlockPos(x, 2, z + 2), Blocks.AIR);
		}
	}

	static void scan(GameTestHelper helper, ScanService scans, Station station, ServerLevel level,
			MinecraftServer server) {
		ScanService.StartResult result = scans.start(station, level, Railway.get().config(),
				server.getTickCount(), Railway.get().state().revision(), ScanTrigger.COMMAND);
		if (!(result instanceof ScanService.StartResult.Started)) {
			helper.fail("scan did not start for " + station.id() + ": " + result);
		}
		for (int i = 0; i < SCAN_TICK_LIMIT && scans.isRunning(station.id()); i++) {
			scans.tick(server);
		}
		if (scans.graph(station.id()) == null) {
			helper.fail("scan failed for " + station.id() + ": " + scans.failure(station.id()));
		}
	}

	static boolean tripActive(Railway railway, TripId trip) {
		for (var active : railway.trips().activeTrips()) {
			if (active.id().equals(trip)) {
				return true;
			}
		}
		return false;
	}

	static void requireNoManagedCart(GameTestHelper helper, ServerLevel level) {
		EntityTypeTest<Entity, Minecart> carts = EntityTypeTest.forClass(Minecart.class);
		if (!level.getEntities(carts, helper.absoluteAABB(helper.getBounds().inflate(2.0)),
				cart -> cart.entityTags().contains(PlayerActions.MANAGED_TAG)).isEmpty()) {
			throw helper.assertionException("service cart was not removed");
		}
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
