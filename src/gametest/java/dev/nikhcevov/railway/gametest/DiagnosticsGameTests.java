package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.minecraft.TripService;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Diagnostics stay read-only and expose station, trip, ticket and recovery state. */
public final class DiagnosticsGameTests implements CustomTestMethodInvoker {
	private static final int LINE_END = 5;
	private static final int FROM_DOCK = 1;
	private static final int TO_DOCK = 4;

	@GameTest(maxTicks = 200)
	public void diagnosticsExposeIdleProvisionalMainAndTicketStats(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		String unique = unique();
		String dimension = RailWorld.dimension(level);
		BlockLocation dock = RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0)));
		BlockLocation exit = RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 2)));
		BlockLocation sign = RailWorld.loc(helper.absolutePos(new BlockPos(0, 2, 2)));
		BlockLocation button = RailWorld.loc(helper.absolutePos(new BlockPos(1, 1, 2)));

		StationId alphaId = new StationId("diag" + unique);
		StationId betaId = new StationId("diagb" + unique);
		Station alpha = new Station(alphaId, "Diagnostics Alpha " + unique, dimension, dock, exit,
				0.0F, true, railway.state().nextRevision());
		Station beta = Station.provisional(betaId, "Diagnostics Beta " + unique, dimension,
				railway.state().nextRevision());
		railway.state().addStation(alpha);
		railway.state().addStation(beta);
		railway.state().addSignMarker(new SignMarker(dimension, sign, sign.offset(0, -1, 0), true,
				null, alphaId, alphaId, true));
		railway.state().addBinding(new Binding(dimension, button, "minecraft:stone_button",
				alphaId, betaId, dock));

		TripId ticketed = TripId.random();
		ChunkWindow.Key key = ChunkWindow.Key.of(dock);
		railway.tickets().update(level, ticketed, Set.of(key), List.of(key),
				railway.config().maxManagedChunkTickets());

		try {
			List<String> lines = railway.diagnostics().render(Messages.Lang.EN, alphaId);
			String text = String.join("\n", lines);
			require(helper, text, "Railway diagnostics");
			require(helper, text, "Recovery: pending ");
			require(helper, text, "Chunk tickets: held ");
			require(helper, text, "Stations (");
			require(helper, text, alphaId.value() + " (Diagnostics Alpha " + unique + ")");
			require(helper, text, betaId.value() + " (Diagnostics Beta " + unique + ")");
			require(helper, text, "provisional (not anchored)");
			require(helper, text, "Station detail " + alphaId.value());
			require(helper, text, "(m flag)");
			require(helper, text, "dock " + dock.shortString());
			if (railway.tickets().tripTicketCount(ticketed) != 1) {
				throw helper.assertionException("diagnostics fixture did not acquire its ticket");
			}
		} finally {
			railway.tickets().release(ticketed);
		}

		List<String> commandLines = new ArrayList<>();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				commandLines.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}

			@Override
			public boolean alwaysAccepts() {
				return true;
			}
		};
		CommandSourceStack source = railway.server().createCommandSourceStack().withSource(capture);
		int result;
		try {
			result = railway.server().getCommands().getDispatcher()
					.execute("rail diagnostics " + alphaId.value(), source);
		} catch (Exception e) {
			throw helper.assertionException("diagnostics command failed: " + e);
		}
		if (result != 1) {
			throw helper.assertionException("diagnostics command returned " + result);
		}
		if (!commandLines.contains("Station detail " + alphaId.value() + " (Diagnostics Alpha "
				+ unique + ") | " + dimension + " | " + dock.shortString())) {
			throw helper.assertionException("command did not focus the station: " + commandLines);
		}

		railway.state().removeStation(alphaId);
		railway.state().removeStation(betaId);
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void diagnosticsExposeActiveTripAndTicketWindow(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		buildLine(helper);

		String unique = unique();
		String dimension = RailWorld.dimension(level);
		StationId fromId = new StationId("diagt" + unique);
		StationId toId = new StationId("diagtt" + unique);
		BlockLocation fromDock = absolute(helper, FROM_DOCK, 1, 0);
		BlockLocation toDock = absolute(helper, TO_DOCK, 1, 0);
		BlockLocation fromExit = absolute(helper, FROM_DOCK, 1, 2);
		BlockLocation toExit = absolute(helper, TO_DOCK, 1, 2);
		Station from = new Station(fromId, "Diagnostics From " + unique, dimension, fromDock,
				fromExit, 0.0F, true, railway.state().nextRevision());
		Station to = new Station(toId, "Diagnostics To " + unique, dimension, toDock, toExit,
				0.0F, true, railway.state().nextRevision());
		railway.state().addStation(from);
		railway.state().addStation(to);

		BlockLocation button = absolute(helper, 0, 1, 2);
		level.setBlock(RailWorld.pos(button), Blocks.STONE_BUTTON.defaultBlockState(), 2);
		Binding binding = new Binding(dimension, button, "minecraft:stone_button", fromId, toId);
		railway.state().addBinding(binding);
		scan(helper, from, level, server);
		scan(helper, to, level, server);

		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(fromExit.x() + 0.5, fromExit.y(), fromExit.z() + 0.5);
		boolean[] checked = {false};
		TripId[] trip = {null};

		helper.onEachTick(() -> {
			if (checked[0]) {
				return;
			}
			if (railway.trips().activeCount() >= railway.config().maxActiveTrips() - 1) {
				return;
			}
			TripService.StartResult start = railway.trips().start(player, binding);
			if (start instanceof TripService.StartResult.Rejected rejected) {
				if (rejected.failure().code() == FailureCode.TRIP_LIMIT
						|| rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				throw helper.assertionException("trip start rejected: "
						+ rejected.failure().code());
			}
			trip[0] = ((TripService.StartResult.Started) start).id();
			if (railway.tickets().tripTicketCount(trip[0]) < 1) {
				throw helper.assertionException("active trip holds no chunk ticket");
			}
			String text = String.join("\n",
					railway.diagnostics().render(Messages.Lang.EN, fromId));
			require(helper, text, "Active trips (");
			require(helper, text, "Trip " + trip[0] + " | ");
			require(helper, text, "tickets ");
			if (text.contains("tickets none")) {
				throw helper.assertionException("active trip ticket window was not reported");
			}
			require(helper, text, "Station detail " + fromId.value());
			require(helper, text, "  graph: revision ");
			checked[0] = true;
			railway.trips().playerLeft(player.getUUID());
		});

		helper.succeedWhen(() -> {
			if (!checked[0]) {
				throw helper.assertionException("active diagnostics fixture never started");
			}
			if (trip[0] != null && railway.trips().isActive(trip[0])) {
				throw helper.assertionException("diagnostics fixture trip was not cleaned up");
			}
			railway.scans().invalidate(fromId);
			railway.scans().invalidate(toId);
			railway.state().removeStation(fromId);
			railway.state().removeStation(toId);
		});
	}

	// ---------------------------------------------------------------- helpers

	private static void require(GameTestHelper helper, String text, String expected) {
		if (!text.contains(expected)) {
			throw helper.assertionException("diagnostics output is missing \"" + expected
					+ "\":\n" + text);
		}
	}

	private static String unique() {
		return Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
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
		for (int x : new int[]{FROM_DOCK, TO_DOCK}) {
			helper.setBlock(new BlockPos(x, 0, 2), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, 2), Blocks.AIR);
			helper.setBlock(new BlockPos(x, 2, 2), Blocks.AIR);
		}
	}

	private static BlockLocation absolute(GameTestHelper helper, int x, int y, int z) {
		return RailWorld.loc(helper.absolutePos(new BlockPos(x, y, z)));
	}

	private static void scan(GameTestHelper helper, Station station, ServerLevel level,
			MinecraftServer server) {
		ScanService scans = Railway.get().scans();
		ScanService.StartResult result = scans.start(station, level, Railway.get().config(),
				server.getTickCount(), Railway.get().state().revision());
		if (!(result instanceof ScanService.StartResult.Started)) {
			helper.fail("scan did not start for " + station.id() + ": " + result);
		}
		for (int i = 0; i < 32 && scans.isRunning(station.id()); i++) {
			scans.tick(server);
		}
		if (scans.graph(station.id()) == null) {
			helper.fail("scan failed for " + station.id() + ": " + scans.failure(station.id()));
		}
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
