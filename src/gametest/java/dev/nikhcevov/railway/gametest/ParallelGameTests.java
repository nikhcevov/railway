package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.TripService;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Two disconnected lines must launch and complete in parallel. */
public final class ParallelGameTests extends GameTestSupport {
	@GameTest(maxTicks = 600)
	public void independentComponentsRunInParallel(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		String unique = Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
		String dimension = RailWorld.dimension(level);

		buildLine(helper, 5, 0, 0, 5);
		buildLine(helper, 5, 4, 0, 5);
		BlockLocation button = absolute(helper, 0, 1, 3);
		level.setBlock(RailWorld.pos(button), Blocks.STONE_BUTTON.defaultBlockState(), 2);

		Station a = station(railway, "pa" + unique, "A " + unique, dimension,
				absolute(helper, 0, 1, 0), absolute(helper, 0, 1, 2));
		Station b = station(railway, "pb" + unique, "B " + unique, dimension,
				absolute(helper, 5, 1, 0), absolute(helper, 5, 1, 2));
		Station c = station(railway, "pc" + unique, "C " + unique, dimension,
				absolute(helper, 0, 1, 4), absolute(helper, 0, 1, 6));
		Station d = station(railway, "pd" + unique, "D " + unique, dimension,
				absolute(helper, 5, 1, 4), absolute(helper, 5, 1, 6));
		for (Station station : new Station[]{a, b, c, d}) {
			scan(helper, railway.scans(), station, level, server);
		}

		ServerPlayer first = helper.makeMockServerPlayerInLevel();
		first.teleportTo(a.exit().x() + 0.5, a.exit().y(), a.exit().z() + 0.5);
		ServerPlayer second = helper.makeMockServerPlayerInLevel();
		second.teleportTo(c.exit().x() + 0.5, c.exit().y(), c.exit().z() + 0.5);
		Binding firstBinding = new Binding(dimension, button, "minecraft:stone_button", a.id(), b.id());
		Binding secondBinding = new Binding(dimension, button, "minecraft:stone_button", c.id(), d.id());

		TripId[] trips = {null, null};
		helper.onEachTick(() -> {
			if (trips[0] == null) {
				if (railway.trips().activeCount() > railway.config().maxActiveTrips() - 2) {
					return;
				}
				TripService.StartResult one = railway.trips().start(first, firstBinding);
				if (one instanceof TripService.StartResult.Rejected rejected) {
					if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
						return;
					}
					throw helper.assertionException("first parallel trip rejected: " + rejected.failure().code());
				}
				TripService.StartResult two = railway.trips().start(second, secondBinding);
				if (two instanceof TripService.StartResult.Rejected rejected) {
					throw helper.assertionException("second parallel trip rejected: " + rejected.failure().code());
				}
				trips[0] = ((TripService.StartResult.Started) one).id();
				trips[1] = ((TripService.StartResult.Started) two).id();
				return;
			}
			if (tripActive(railway, trips[0]) || tripActive(railway, trips[1])) {
				return;
			}
			if (!first.blockPosition().equals(RailWorld.pos(b.exit()))) {
				throw helper.assertionException("first player at " + first.blockPosition());
			}
			if (!second.blockPosition().equals(RailWorld.pos(d.exit()))) {
				throw helper.assertionException("second player at " + second.blockPosition());
			}
			requireNoManagedCart(helper, level);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 800)
	public void secondPlayerBoardsOnceDockClearsAndFollowsSameLine(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		String unique = Long.toString(System.nanoTime() & 0xFFFFFFL, 36) + "f";
		String dimension = RailWorld.dimension(level);

		buildLine(helper, 7, 0, 0, 7);
		BlockLocation button = absolute(helper, 0, 1, 3);
		level.setBlock(RailWorld.pos(button), Blocks.STONE_BUTTON.defaultBlockState(), 2);

		Station a = station(railway, "fa" + unique, "Follow A " + unique, dimension,
				absolute(helper, 0, 1, 0), absolute(helper, 0, 1, 2));
		Station b = station(railway, "fb" + unique, "Follow B " + unique, dimension,
				absolute(helper, 7, 1, 0), absolute(helper, 7, 1, 2));
		scan(helper, railway.scans(), a, level, server);
		scan(helper, railway.scans(), b, level, server);

		ServerPlayer first = helper.makeMockServerPlayerInLevel();
		first.teleportTo(a.exit().x() + 0.5, a.exit().y(), a.exit().z() + 0.5);
		ServerPlayer second = helper.makeMockServerPlayerInLevel();
		second.teleportTo(a.exit().x() + 0.5, a.exit().y(), a.exit().z() + 0.5);
		Binding binding = new Binding(dimension, button, "minecraft:stone_button", a.id(), b.id());

		TripId[] trips = {null, null};
		boolean[] overlapped = {false};
		helper.onEachTick(() -> {
			if (trips[0] == null) {
				if (railway.trips().activeCount() > railway.config().maxActiveTrips() - 2) {
					return;
				}
				TripService.StartResult one = railway.trips().start(first, binding);
				if (one instanceof TripService.StartResult.Rejected rejected) {
					if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
						return;
					}
					throw helper.assertionException("leader trip rejected: " + rejected.failure().code());
				}
				trips[0] = ((TripService.StartResult.Started) one).id();
				return;
			}
			if (trips[1] == null) {
				TripService.StartResult two = railway.trips().start(second, binding);
				if (two instanceof TripService.StartResult.Started started) {
					trips[1] = started.id();
					if (tripActive(railway, trips[0])) {
						overlapped[0] = true;
					}
				}
				return;
			}
			if (tripActive(railway, trips[0]) || tripActive(railway, trips[1])) {
				return;
			}
			if (!overlapped[0]) {
				throw helper.assertionException("follower did not board while leader trip was still active");
			}
			if (!first.blockPosition().equals(RailWorld.pos(b.exit()))) {
				throw helper.assertionException("leader ended at " + first.blockPosition());
			}
			if (!second.blockPosition().equals(RailWorld.pos(b.exit()))) {
				throw helper.assertionException("follower ended at " + second.blockPosition());
			}
			requireNoManagedCart(helper, level);
			helper.succeed();
		});
	}

	private static Station station(Railway railway, String id, String name, String dimension,
			BlockLocation dock, BlockLocation exit) {
		Station station = new Station(new StationId(id), name, dimension, dock, exit, 0.0F, true,
				railway.state().nextRevision());
		railway.state().addStation(station);
		return station;
	}

}
