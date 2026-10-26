package dev.nikhcevov.railway.gametest;

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
import dev.nikhcevov.railway.minecraft.SignRegistrationService;
import dev.nikhcevov.railway.minecraft.TripService;
import dev.nikhcevov.railway.persistence.WorldState;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.lang.reflect.Method;
import java.util.List;

/**
 * End-to-end service-sign tests. Every test drives the real sign-save mixin through
 * {@link SignBlockEntity#updateSignText} and uses a real straight rail plus a stone
 * pillar in the test plot.
 */
public final class SignGameTests implements CustomTestMethodInvoker {
	private static final int LINE_END = 7;
	private static final int HOME_X = 1;
	private static final int MINE_X = 6;
	private static final int DEST_SIGN_X = 3;
	private static final int DEST_SUPPORT_X = 4;
	private static final int Z_RAIL = 0;
	private static final int Z_SIGN = 1;
	private static final int Z_PILLAR = 2;
	private static final int Z_EXIT = 3;
	private static final int DOCK_Y = 1;
	private static final int PILLAR_TOP = 5;

	@GameTest(maxTicks = 200)
	public void homeSignAnchorsHomeAndCreatesProvisionalMine(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 4, Z_SIGN, Direction.NORTH);
		check(helper, fixture.level().getBlockState(RailWorld.pos(button)).is(Blocks.STONE_BUTTON),
				"home button was not placed: " + fixture.level().getBlockState(RailWorld.pos(button)));
		clearRegistration(fixture, sign, button);

		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		check(helper, home.isAnchored() && home.enabled(), "Home was not anchored and enabled");
		check(helper, home.dock().equals(fixture.homeDock()), "Home dock mismatch: " + home.dock());
		check(helper, home.exit().equals(fixture.homeExit()), "Home exit mismatch: " + home.exit());
		Station mine = station(helper, fixture, fixture.mineName());
		check(helper, mine.isProvisional(), "Mine should be provisional");

		SignMarker marker = state(fixture).signMarker(fixture.dimension(), sign).orElse(null);
		check(helper, marker != null, "Home sign marker missing");
		check(helper, marker.isAnchor() && marker.hasButton(), "Home marker must anchor and carry the button");
		check(helper, home.id().equals(marker.anchorStationId()), "Home marker anchor mismatch");
		check(helper, button.equals(marker.button()), "Home marker button mismatch");

		Binding binding = state(fixture).binding(fixture.dimension(), button).orElse(null);
		check(helper, binding != null, "Home -> Mine binding missing");
		check(helper, binding.fromStationId().equals(home.id()), "Binding source mismatch");
		check(helper, binding.toStationId().equals(mine.id()), "Binding destination mismatch");
		check(helper, "minecraft:stone_button".equals(binding.expectedButtonBlockId()),
				"Binding button block id mismatch: " + binding.expectedButtonBlockId());

		finishScan(fixture, helper, home);
		helper.onEachTick(() -> {
			if (fixture.railway().trips().activeCount() >= fixture.railway().config().maxActiveTrips()) {
				return;
			}
			TripService.StartResult rejected = fixture.railway().trips().start(fixture.player(), binding);
			check(helper, rejected instanceof TripService.StartResult.Rejected,
					"launch should be rejected while Mine is provisional");
			FailureCode code = ((TripService.StartResult.Rejected) rejected).failure().code();
			check(helper, code == FailureCode.DESTINATION_NOT_ANCHORED,
					"expected DESTINATION_NOT_ANCHORED, got " + code);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 300)
	public void mineSignAnchorsProvisionalAndBothRoutesStart(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation homeButton = placeWallButton(helper, HOME_X, 4, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, homeButton);
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		finishScan(fixture, helper, home);
		StationId homeId = home.id();
		BlockLocation homeDock = home.dock();
		BlockLocation homeExit = home.exit();

		ServerPlayer mineBuilder = opPlayer(helper, fixture, fixture.mineExit());
		BlockLocation mineSign = placeWallSign(helper, MINE_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mineButton = placeWallButton(helper, MINE_X, 4, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mineSign, mineButton);
		saveSign(fixture, mineSign, mineBuilder, fixture.mineName(), fixture.homeName());

		Station mine = station(helper, fixture, fixture.mineName());
		check(helper, mine.isAnchored() && mine.enabled(), "Mine was not anchored and enabled");
		check(helper, mine.dock().equals(fixture.mineDock()), "Mine dock mismatch: " + mine.dock());
		check(helper, mine.exit().equals(fixture.mineExit()), "Mine exit mismatch: " + mine.exit());
		Station homeAfter = station(helper, fixture, fixture.homeName());
		check(helper, homeAfter.id().equals(homeId), "Home identity changed");
		check(helper, homeAfter.dock().equals(homeDock), "Home dock changed");
		check(helper, homeAfter.exit().equals(homeExit), "Home exit changed");

		SignMarker homeMarker = state(fixture).signMarker(fixture.dimension(), homeSign).orElse(null);
		SignMarker mineMarker = state(fixture).signMarker(fixture.dimension(), mineSign).orElse(null);
		check(helper, homeMarker != null && homeMarker.anchorStationId().equals(homeId),
				"Home anchor marker lost");
		check(helper, mineMarker != null && mineMarker.anchorStationId().equals(mine.id()),
				"Mine anchor marker missing");

		Binding homeToMine = state(fixture).binding(fixture.dimension(), homeButton).orElse(null);
		Binding mineToHome = state(fixture).binding(fixture.dimension(), mineButton).orElse(null);
		check(helper, homeToMine != null && mineToHome != null, "bindings missing after Mine anchored");

		finishScan(fixture, helper, mine);
		int[] route = {0};
		helper.onEachTick(() -> {
			if (route[0] >= 2) {
				helper.succeed();
				return;
			}
			if (fixture.railway().trips().activeCount() >= fixture.railway().config().maxActiveTrips()) {
				return;
			}
			if (route[0] == 0) {
				requireRouteStarts(helper, fixture, fixture.homeExit(), homeToMine);
			} else {
				requireRouteStarts(helper, fixture, fixture.mineExit(), mineToHome);
			}
			route[0]++;
		});
	}

	@GameTest(maxTicks = 200)
	public void highSignAndButtonAreAccepted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, DOCK_Y + 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, DOCK_Y + 3, Z_EXIT, Direction.SOUTH);
		clearRegistration(fixture, sign, button);

		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		check(helper, home.isAnchored(), "high sign unit was not registered");
		check(helper, home.dock().equals(fixture.homeDock()), "high sign dock mismatch: " + home.dock());
		check(helper, state(fixture).binding(fixture.dimension(), button).isPresent(),
				"high button binding missing");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void signWithoutButtonIsRejectedThenButtonAndResaveAccepted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = absolute(helper, HOME_X, 3, Z_EXIT);
		clearRegistration(fixture, sign, button);

		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());
		check(helper, state(fixture).stationsNamed(fixture.dimension(), fixture.homeName()).isEmpty(),
				"station was created without a button");
		check(helper, state(fixture).signMarker(fixture.dimension(), sign).isEmpty(),
				"marker was created without a button");
		check(helper, fixture.homeName().equals(rawLine(fixture, sign, true, 1)),
				"new sign text should be left in place after a rejection");

		BlockLocation placed = placeWallButton(helper, HOME_X, 3, Z_EXIT, Direction.SOUTH);
		clearRegistration(fixture, sign, placed);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		check(helper, home.isAnchored(), "sign was not accepted after the button was added");
		SignMarker marker = state(fixture).signMarker(fixture.dimension(), sign).orElse(null);
		check(helper, marker != null && placed.equals(marker.button()), "button was not recorded on the marker");
		check(helper, state(fixture).binding(fixture.dimension(), placed).isPresent(),
				"binding was not created on re-save");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void destinationUnitForAnchoredStationIsAccepted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 4, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");

		Station home = station(helper, fixture, fixture.homeName());
		BlockLocation homeDock = home.dock();
		BlockLocation homeExit = home.exit();
		check(helper, state(fixture).bindingsFor(home.id()).isEmpty(), "station-only anchor created a binding");

		for (int y = 0; y <= PILLAR_TOP; y++) {
			helper.setBlock(new BlockPos(DEST_SUPPORT_X, y, Z_SIGN), Blocks.STONE);
		}
		BlockLocation sign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.WEST);
		BlockLocation button = placeWallButton(helper, DEST_SUPPORT_X, 2, Z_PILLAR, Direction.SOUTH);
		clearRegistration(fixture, sign, button);

		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station homeAfter = station(helper, fixture, fixture.homeName());
		check(helper, homeAfter.id().equals(home.id()), "destination unit changed the Home station identity");
		check(helper, homeAfter.dock().equals(homeDock), "destination unit changed the Home dock");
		check(helper, homeAfter.exit().equals(homeExit), "destination unit changed the Home exit");
		check(helper, state(fixture).stationsNamed(fixture.dimension(), fixture.homeName()).size() == 1,
				"destination unit created a second Home station");

		Station mine = station(helper, fixture, fixture.mineName());
		check(helper, mine.isProvisional(), "destination should create a provisional station");

		SignMarker marker = state(fixture).signMarker(fixture.dimension(), sign).orElse(null);
		check(helper, marker != null, "destination marker missing");
		check(helper, !marker.isAnchor(), "destination unit must not anchor a station");
		check(helper, button.equals(marker.button()), "destination marker button mismatch");
		BlockLocation support = RailWorld.loc(helper.absolutePos(new BlockPos(DEST_SUPPORT_X, 3, Z_SIGN)));
		check(helper, support.equals(marker.support()), "destination support mismatch: " + marker.support());

		Binding binding = state(fixture).binding(fixture.dimension(), button).orElse(null);
		check(helper, binding != null, "destination binding missing");
		check(helper, binding.fromStationId().equals(home.id()), "destination binding source mismatch");
		check(helper, binding.toStationId().equals(mine.id()), "destination binding target mismatch");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void breakingAnchorSignDisablesStationAndKeepsStationData(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 4, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		BlockLocation dock = home.dock();
		BlockLocation exit = home.exit();

		breakBlockThroughService(fixture, sign);

		check(helper, state(fixture).signMarker(fixture.dimension(), sign).isEmpty(),
				"sign marker survived sign break");
		check(helper, state(fixture).binding(fixture.dimension(), button).isEmpty(),
				"button binding survived anchor sign break");
		Station after = state(fixture).station(home.id()).orElse(null);
		check(helper, after != null, "station was removed with its sign");
		check(helper, after.isAnchored(), "station lost its dock");
		check(helper, after.dock().equals(dock) && after.exit().equals(exit), "station dock/exit changed");
		check(helper, !after.enabled(), "station was not disabled after anchor sign break");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void breakingButtonKeepsAnchorMarkerAndDropsBinding(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 4, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		breakBlockThroughService(fixture, button);

		check(helper, state(fixture).binding(fixture.dimension(), button).isEmpty(),
				"button binding survived button break");
		SignMarker marker = state(fixture).signMarker(fixture.dimension(), sign).orElse(null);
		check(helper, marker != null, "anchor marker was removed with a destination button");
		check(helper, marker.isAnchor() && !marker.hasButton(), "marker should keep only the anchor");
		check(helper, home.id().equals(marker.anchorStationId()), "anchor identity changed");
		Station after = state(fixture).station(home.id()).orElse(null);
		check(helper, after != null && after.enabled(), "station should stay enabled after button break");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void nonBuilderEditIsReverted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 4, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		ServerPlayer nonBuilder = helper.makeMockServerPlayerInLevel();
		fixture.server().getPlayerList().deop(nonBuilder.nameAndId());
		check(helper, !nonBuilder.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
				"non-builder mock player unexpectedly has OP 2");

		SignBlockEntity signEntity = signEntity(fixture, sign);
		signEntity.setAllowedPlayerEditor(nonBuilder.getUUID());
		signEntity.updateSignText(nonBuilder, true, List.of(
				FilteredText.passThrough("[Railway]"),
				FilteredText.passThrough("Intruder Home"),
				FilteredText.passThrough("Intruder Mine")));

		check(helper, fixture.homeName().equals(rawLine(fixture, sign, true, 1)),
				"non-builder edit was not reverted");
		check(helper, fixture.mineName().equals(rawLine(fixture, sign, true, 2)),
				"non-builder destination edit was not reverted");
		check(helper, state(fixture).stationsNamed(fixture.dimension(), "Intruder Home").isEmpty(),
				"non-builder created a station");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void editFromTheUnregisteredSideIsReverted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 4, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		SignBlockEntity signEntity = signEntity(fixture, sign);
		signEntity.setAllowedPlayerEditor(fixture.player().getUUID());
		signEntity.updateSignText(fixture.player(), false, List.of(
				FilteredText.passThrough("[Railway]"),
				FilteredText.passThrough("Back Home"),
				FilteredText.passThrough("Back Mine")));

		check(helper, !"Back Home".equals(rawLine(fixture, sign, false, 1)),
				"edit from the unregistered side was not reverted");
		check(helper, state(fixture).stationsNamed(fixture.dimension(), "Back Home").isEmpty(),
				"unregistered side created a station");
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void ordinaryWallSignIsIgnored(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = absolute(helper, HOME_X, 4, Z_SIGN);
		clearRegistration(fixture, sign, button);

		saveSignLines(fixture, sign, fixture.player(), true,
				"Welcome", fixture.homeName(), fixture.mineName());

		check(helper, state(fixture).stationsNamed(fixture.dimension(), fixture.homeName()).isEmpty(),
				"ordinary sign created a source station");
		check(helper, state(fixture).stationsNamed(fixture.dimension(), fixture.mineName()).isEmpty(),
				"ordinary sign created a destination station");
		check(helper, state(fixture).signMarker(fixture.dimension(), sign).isEmpty(),
				"ordinary sign created a marker");
		check(helper, state(fixture).binding(fixture.dimension(), button).isEmpty(),
				"ordinary sign created a binding");
		check(helper, "Welcome".equals(rawLine(fixture, sign, true, 0)),
				"ordinary sign text was changed");
		check(helper, fixture.homeName().equals(rawLine(fixture, sign, true, 1)),
				"ordinary sign source line was changed");
		check(helper, fixture.mineName().equals(rawLine(fixture, sign, true, 2)),
				"ordinary sign destination line was changed");

		SignBlockEntity entity = signEntity(fixture, sign);
		SignRegistrationService.Result result = fixture.railway().signs()
				.register(fixture.player(), entity, true, entity.getText(true));
		check(helper, result instanceof SignRegistrationService.Result.Ignored,
				"ordinary sign should be ignored without a Railway error, got " + result);
		helper.succeed();
	}

	@GameTest(maxTicks = 400)
	public void newDestinationBindingRefreshesAnchoredScans(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);

		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 4, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());
		finishScan(fixture, helper, home);

		ServerPlayer mineBuilder = opPlayer(helper, fixture, fixture.mineExit());
		BlockLocation mineSign = placeWallSign(helper, MINE_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mineSign, absolute(helper, MINE_X, 4, Z_SIGN));
		saveSign(fixture, mineSign, mineBuilder, fixture.mineName(), "");
		Station mine = station(helper, fixture, fixture.mineName());
		finishScan(fixture, helper, mine);

		for (int y = 0; y <= PILLAR_TOP; y++) {
			helper.setBlock(new BlockPos(DEST_SUPPORT_X, y, Z_SIGN), Blocks.STONE);
		}
		BlockLocation sign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.WEST);
		BlockLocation button = placeWallButton(helper, DEST_SUPPORT_X, 2, Z_PILLAR, Direction.SOUTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());

		Binding binding = state(fixture).binding(fixture.dimension(), button).orElse(null);
		check(helper, binding != null, "destination binding missing");
		check(helper, binding.fromStationId().equals(home.id()), "destination binding source mismatch");
		check(helper, binding.toStationId().equals(mine.id()), "destination binding target mismatch");

		finishScan(fixture, helper, home);
		finishScan(fixture, helper, mine);
		var homeGraph = fixture.railway().scans().graph(home.id());
		var mineGraph = fixture.railway().scans().graph(mine.id());
		check(helper, homeGraph != null && mineGraph != null, "graphs missing after binding registration");
		check(helper, homeGraph.revision() == mineGraph.revision(),
				"home/mine graph revisions differ: " + homeGraph.revision() + " vs " + mineGraph.revision());

		int[] started = {0};
		helper.onEachTick(() -> {
			if (started[0] > 0) {
				helper.succeed();
				return;
			}
			if (fixture.railway().trips().activeCount() >= fixture.railway().config().maxActiveTrips()) {
				return;
			}
			requireRouteStarts(helper, fixture, fixture.homeExit(), binding);
			started[0]++;
		});
	}

	// --------------------------------------------------------------- fixtures

	private record Fixture(
			Railway railway,
			ServerLevel level,
			MinecraftServer server,
			ServerPlayer player,
			String dimension,
			String homeName,
			String mineName,
			BlockLocation homeDock,
			BlockLocation mineDock,
			BlockLocation homeExit,
			BlockLocation mineExit
	) {
	}

	private static Fixture buildFixture(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		String unique = Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
		String dimension = RailWorld.dimension(level);

		for (int x = 0; x <= LINE_END; x++) {
			helper.setBlock(new BlockPos(x, DOCK_Y - 1, Z_RAIL), Blocks.STONE);
			helper.setBlock(new BlockPos(x, DOCK_Y, Z_RAIL), Blocks.AIR);
			helper.setBlock(new BlockPos(x, DOCK_Y + 1, Z_RAIL), Blocks.AIR);
		}
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = 0; x <= LINE_END; x++) {
			level.setBlock(helper.absolutePos(new BlockPos(x, DOCK_Y, Z_RAIL)), rail, 2);
		}

		for (int x : new int[]{HOME_X, MINE_X}) {
			for (int y = 0; y <= PILLAR_TOP; y++) {
				helper.setBlock(new BlockPos(x, y, Z_PILLAR), Blocks.STONE);
				helper.setBlock(new BlockPos(x, y, Z_SIGN), Blocks.AIR);
			}
			helper.setBlock(new BlockPos(x, DOCK_Y - 1, Z_EXIT), Blocks.STONE);
			for (int y = DOCK_Y; y <= PILLAR_TOP; y++) {
				helper.setBlock(new BlockPos(x, y, Z_EXIT), Blocks.AIR);
			}
		}

		ServerPlayer player = makeOpPlayer(helper, server, absolute(helper, HOME_X, DOCK_Y, Z_EXIT));
		BlockLocation homeDock = absolute(helper, HOME_X, DOCK_Y, Z_RAIL);
		BlockLocation mineDock = absolute(helper, MINE_X, DOCK_Y, Z_RAIL);
		BlockLocation homeExit = absolute(helper, HOME_X, DOCK_Y, Z_EXIT);
		BlockLocation mineExit = absolute(helper, MINE_X, DOCK_Y, Z_EXIT);
		return new Fixture(railway, level, server, player, dimension,
				"Sign Home " + unique, "Sign Mine " + unique,
				homeDock, mineDock, homeExit, mineExit);
	}

	private static ServerPlayer makeOpPlayer(
			GameTestHelper helper,
			MinecraftServer server,
			BlockLocation position
	) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		// GameTestServer#operatorUserPermissions is ALL, so op(name) alone never reaches
		// OP level 2. Grant the gamemaster set explicitly for the test fixture.
		server.getPlayerList().op(player.nameAndId());
		if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			server.getPlayerList().op(player.nameAndId(),
					java.util.Optional.of(LevelBasedPermissionSet.GAMEMASTER),
					java.util.Optional.of(true));
		}
		player.teleportTo(position.x() + 0.5, position.y(), position.z() + 0.5);
		check(helper, player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
				"mock player did not receive OP 2");
		return player;
	}

	private static ServerPlayer opPlayer(GameTestHelper helper, Fixture fixture, BlockLocation position) {
		return makeOpPlayer(helper, fixture.server(), position);
	}

	private static BlockLocation placeWallSign(
			GameTestHelper helper,
			int x,
			int y,
			int z,
			Direction facing
	) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		BlockState state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
		helper.getLevel().setBlock(pos, state, 3);
		return RailWorld.loc(pos);
	}

	private static BlockLocation placeWallButton(
			GameTestHelper helper,
			int x,
			int y,
			int z,
			Direction facing
	) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		BlockState state = Blocks.STONE_BUTTON.defaultBlockState()
				.setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
				.setValue(HorizontalDirectionalBlock.FACING, facing);
		helper.getLevel().setBlock(pos, state, 3);
		return RailWorld.loc(pos);
	}

	private static void clearRegistration(Fixture fixture, BlockLocation sign, BlockLocation button) {
		WorldState state = state(fixture);
		state.removeSignMarker(fixture.dimension(), sign);
		state.removeBinding(fixture.dimension(), button);
	}

	private static void saveSign(
			Fixture fixture,
			BlockLocation sign,
			ServerPlayer player,
			String from,
			String to
	) {
		saveSignLines(fixture, sign, player, true, "[Railway]", from, to);
	}

	private static void saveSignLines(
			Fixture fixture,
			BlockLocation sign,
			ServerPlayer player,
			boolean front,
			String line1,
			String line2,
			String line3
	) {
		SignBlockEntity entity = signEntity(fixture, sign);
		entity.setAllowedPlayerEditor(player.getUUID());
		entity.updateSignText(player, front, List.of(
				FilteredText.passThrough(line1),
				FilteredText.passThrough(line2),
				FilteredText.passThrough(line3)));
	}

	private static SignBlockEntity signEntity(Fixture fixture, BlockLocation sign) {
		BlockEntity entity = fixture.level().getBlockEntity(RailWorld.pos(sign));
		if (!(entity instanceof SignBlockEntity signEntity)) {
			throw new IllegalStateException("no sign block entity at " + sign.shortString());
		}
		return signEntity;
	}

	private static String rawLine(Fixture fixture, BlockLocation sign, boolean front, int line) {
		return signEntity(fixture, sign).getText(front).getMessage(line, false).getString();
	}

	private static void breakBlockThroughService(Fixture fixture, BlockLocation location) {
		BlockState state = fixture.level().getBlockState(RailWorld.pos(location));
		BlockEntity entity = fixture.level().getBlockEntity(RailWorld.pos(location));
		fixture.level().destroyBlock(RailWorld.pos(location), false);
		fixture.railway().signs().onBlockBreak(fixture.level(), RailWorld.pos(location), state, entity);
	}

	private static void finishScan(Fixture fixture, GameTestHelper helper, Station station) {
		ScanService scans = fixture.railway().scans();
		for (int i = 0; i < 128 && scans.isRunning(station.id()); i++) {
			scans.tick(fixture.server());
		}
		if (scans.graph(station.id()) == null) {
			throw helper.assertionException("scan failed for " + station.id() + ": " + scans.failure(station.id()));
		}
	}

	private static void requireRouteStarts(
			GameTestHelper helper,
			Fixture fixture,
			BlockLocation exit,
			Binding binding
	) {
		ServerPlayer player = opPlayer(helper, fixture, exit);
		TripService.StartResult start = fixture.railway().trips().start(player, binding);
		if (start instanceof TripService.StartResult.Rejected rejected) {
			throw helper.assertionException("route " + binding.fromStationId() + " -> " + binding.toStationId()
					+ " rejected: " + rejected.failure().code() + " " + rejected.failure().detail());
		}
		TripId id = ((TripService.StartResult.Started) start).id();
		TripService.AbortResult abort = fixture.railway().trips().abort(id);
		if (!(abort instanceof TripService.AbortResult.Aborted)) {
			throw helper.assertionException("route trip did not abort cleanly: " + abort);
		}
		player.teleportTo(exit.x() + 0.5, exit.y(), exit.z() + 0.5);
	}

	private static Station station(GameTestHelper helper, Fixture fixture, String name) {
		List<Station> matches = state(fixture).stationsNamed(fixture.dimension(), name);
		if (matches.size() != 1) {
			throw helper.assertionException("expected exactly one station named " + name + ", got " + matches.size());
		}
		return matches.getFirst();
	}

	private static WorldState state(Fixture fixture) {
		return fixture.railway().state();
	}

	private static BlockLocation absolute(GameTestHelper helper, int x, int y, int z) {
		return RailWorld.loc(helper.absolutePos(new BlockPos(x, y, z)));
	}

	private static void check(GameTestHelper helper, boolean condition, String message) {
		if (!condition) {
			throw helper.assertionException(message);
		}
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
