package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.ChunkTicketManager;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.RailPort;
import dev.nikhcevov.railway.network.RailShapeType;
import dev.nikhcevov.railway.network.RouteStep;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Lifecycle, budget and readiness contract of the managed chunk tickets. */
public final class ChunkTicketGameTests implements CustomTestMethodInvoker {
	@GameTest(maxTicks = 100)
	public void scannedChunkTicketIsAcquiredAndReleased(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0)));
		ChunkWindow.Key key = ChunkWindow.Key.of(dock);
		TripId tripId = TripId.random();

		ChunkTicketManager.Update update = tickets.update(level, tripId, Set.of(key), List.of(key), 128);
		if (!(update instanceof ChunkTicketManager.Update.Applied applied) || !applied.enabled()) {
			throw helper.assertionException("ticket update was not applied: " + update);
		}
		if (tickets.tripTicketCount(tripId) != 1) {
			throw helper.assertionException("ticket was not acquired: " + tickets.tripTicketCount(tripId));
		}
		if (!tickets.window(tripId).equals(List.of(key))) {
			throw helper.assertionException("unexpected ticket window: " + tickets.window(tripId));
		}

		helper.succeedWhen(() -> {
			if (!ChunkTicketManager.isChunkReady(level, key)) {
				throw helper.assertionException("ticketed plot chunk never became entity-ticking");
			}
			if (!tickets.isPositionReady(tripId, level, dock)) {
				throw helper.assertionException("ticketed plot position is not motion-ready");
			}
			if (tickets.readinessChecks(tripId) == 0) {
				throw helper.assertionException("readiness check counter was not exposed");
			}
			tickets.release(tripId);
			if (tickets.tripTicketCount(tripId) != 0 || tickets.stats(tripId).isPresent()) {
				throw helper.assertionException("release left ticket state behind");
			}
			if (!tickets.window(tripId).isEmpty()) {
				throw helper.assertionException("release left the ticket window behind");
			}
		});
	}

	@GameTest(maxTicks = 40)
	public void budgetExceededDoesNotAcquirePartOfTheWindow(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		ChunkWindow.Key origin = ChunkWindow.Key.of(
				RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0))));
		List<ChunkWindow.Key> window = List.of(
				origin,
				new ChunkWindow.Key(origin.x() + 1, origin.z()),
				new ChunkWindow.Key(origin.x() + 2, origin.z()));
		TripId tripId = TripId.random();

		ChunkTicketManager.Update update = tickets.update(level, tripId, Set.copyOf(window), window, 2);
		if (!(update instanceof ChunkTicketManager.Update.BudgetExceeded exceeded)) {
			throw helper.assertionException("expected BudgetExceeded, got " + update);
		}
		if (exceeded.requested() < 3 || exceeded.budget() != 2) {
			throw helper.assertionException("unexpected budget details: " + exceeded);
		}
		if (tickets.tripTicketCount(tripId) != 0) {
			throw helper.assertionException("partial window was acquired");
		}
		tickets.release(tripId);
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void chunksOutsideTheScannedRouteAreNeverTicketed(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		ChunkWindow.Key origin = ChunkWindow.Key.of(
				RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0))));
		ChunkWindow.Key far = new ChunkWindow.Key(origin.x() + 256, origin.z() + 256);
		TripId tripId = TripId.random();

		if (level.getChunkSource().hasChunk(far.x(), far.z())) {
			throw helper.assertionException("test chunk unexpectedly loaded");
		}
		ChunkTicketManager.Update update = tickets.update(level, tripId, Set.of(origin), List.of(far), 128);
		if (!(update instanceof ChunkTicketManager.Update.Applied applied) || !applied.enabled()) {
			throw helper.assertionException("ticket update was not applied: " + update);
		}
		if (tickets.tripTicketCount(tripId) != 0) {
			throw helper.assertionException("unscanned chunk was ticketed");
		}
		if (ChunkTicketManager.isChunkReady(level, far)) {
			throw helper.assertionException("unloaded far chunk reported ready");
		}
		BlockLocation farBlock = new BlockLocation(far.x() * 16 + 8, 64, far.z() * 16 + 8);
		if (tickets.isPositionReady(tripId, level, farBlock)) {
			throw helper.assertionException("unloaded far position reported motion-ready");
		}
		tickets.release(tripId);

		int[] remaining = {5};
		helper.onEachTick(() -> {
			if (level.getChunkSource().hasChunk(far.x(), far.z())) {
				throw helper.assertionException("unscanned chunk was loaded/generated");
			}
			if (--remaining[0] <= 0) {
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = 40)
	public void launchValidationDefersUnloadedRouteTail(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.STONE);
		BlockPos railPos = helper.absolutePos(new BlockPos(0, 1, 0));
		level.setBlock(railPos, Blocks.RAIL.defaultBlockState()
				.setValue(RailBlock.SHAPE, RailShape.EAST_WEST), 2);
		BlockLocation loaded = RailWorld.loc(railPos);
		ChunkWindow.Key origin = ChunkWindow.Key.of(loaded);
		BlockLocation far = new BlockLocation((origin.x() + 256) * 16 + 8, 64,
				(origin.z() + 256) * 16 + 8);
		if (new RailWorld(level).isLoaded(far)) {
			throw helper.assertionException("test tail chunk unexpectedly loaded");
		}

		GraphRoutePlan plan = new GraphRoutePlan(
				new StationId("gtlauncha"), new StationId("gtlaunchb"),
				loaded, far, RailWorld.dimension(level),
				List.of(
						new RouteStep(loaded, RailShapeType.EAST_WEST, RailPort.WEST, RailPort.EAST),
						new RouteStep(far, RailShapeType.NORTH_SOUTH, RailPort.NORTH, RailPort.SOUTH)),
				List.of(), 2, "launch-test");
		Failure deferred = new RailWorld(level).validateGraphRoute(plan);
		if (deferred != null) {
			throw helper.assertionException("unloaded tail rejected launch: " + deferred.code());
		}

		GraphRoutePlan wrongShape = new GraphRoutePlan(
				new StationId("gtlauncha"), new StationId("gtlaunchb"),
				loaded, far, RailWorld.dimension(level),
				List.of(
						new RouteStep(loaded, RailShapeType.NORTH_SOUTH, RailPort.NORTH, RailPort.SOUTH),
						new RouteStep(far, RailShapeType.NORTH_SOUTH, RailPort.NORTH, RailPort.SOUTH)),
				List.of(), 2, "launch-test-bad");
		Failure loadedFailure = new RailWorld(level).validateGraphRoute(wrongShape);
		if (loadedFailure == null || loadedFailure.code() != FailureCode.SCAN_INCOMPLETE) {
			throw helper.assertionException("loaded rail shape was not validated");
		}
		helper.succeed();
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
