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
	private static final int DEST_SUPPORT_X = 3;
	private static final int DEST_SUPPORT_Z = 2;
	private static final int Z_RAIL = 0;
	private static final int Z_SIGN = 1;
	private static final int Z_PILLAR = 2;
	private static final int Z_EXIT = 3;
	private static final int DOCK_Y = 1;
	private static final int PILLAR_TOP = 5;

	@GameTest(maxTicks = 200)
	public void homeSignAnchorsHomeAndCreatesProvisionalMine(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 1, Z_SIGN, Direction.NORTH);
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

	@GameTest(maxTicks = 600)
	public void mineSignAnchorsProvisionalAndBothRoutesStart(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation homeButton = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, homeButton);
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), fixture.mineName());

		Station home = station(helper, fixture, fixture.homeName());
		finishScan(fixture, helper, home);
		StationId homeId = home.id();
		BlockLocation homeDock = home.dock();
		BlockLocation homeExit = home.exit();

		ServerPlayer mineBuilder = opPlayer(helper, fixture, fixture.mineExit());
		BlockLocation mineSign = placeWallSign(helper, MINE_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mineButton = placeWallButton(helper, MINE_X, 2, Z_SIGN, Direction.NORTH);
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
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			boolean started = route[0] == 0
					? requireRouteStarts(helper, fixture, fixture.homeExit(), homeToMine)
					: requireRouteStarts(helper, fixture, fixture.mineExit(), mineToHome);
			if (started) {
				route[0]++;
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void highSignAndButtonAreAccepted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, DOCK_Y + 2, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, DOCK_Y + 2, Z_EXIT, Direction.SOUTH);
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
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");

		Station home = station(helper, fixture, fixture.homeName());
		BlockLocation homeDock = home.dock();
		BlockLocation homeExit = home.exit();
		check(helper, state(fixture).bindingsFor(home.id()).isEmpty(), "station-only anchor created a binding");

		for (int y = 0; y <= PILLAR_TOP; y++) {
			helper.setBlock(new BlockPos(DEST_SUPPORT_X, y, DEST_SUPPORT_Z), Blocks.STONE);
		}
		BlockLocation sign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
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
		BlockLocation support = RailWorld.loc(helper.absolutePos(
				new BlockPos(DEST_SUPPORT_X, 3, DEST_SUPPORT_Z)));
		check(helper, support.equals(marker.support()), "destination support mismatch: " + marker.support());

		Binding binding = state(fixture).binding(fixture.dimension(), button).orElse(null);
		check(helper, binding != null, "destination binding missing");
		check(helper, binding.fromStationId().equals(home.id()), "destination binding source mismatch");
		check(helper, binding.toStationId().equals(mine.id()), "destination binding target mismatch");
		check(helper, binding.sourceDock().equals(fixture.homeDock().offset(2, 0, 0)),
				"destination source dock mismatch: " + binding.sourceDock());
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void breakingAnchorSignDisablesStationAndKeepsStationData(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
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
		BlockLocation button = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
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
		BlockLocation button = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
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
		BlockLocation button = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
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
		BlockLocation button = absolute(helper, HOME_X, 1, Z_SIGN);
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

	@GameTest(maxTicks = 600)
	public void newDestinationBindingRefreshesAnchoredScans(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);

		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());
		finishScan(fixture, helper, home);

		ServerPlayer mineBuilder = opPlayer(helper, fixture, fixture.mineExit());
		BlockLocation mineSign = placeWallSign(helper, MINE_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mineSign, absolute(helper, MINE_X, 2, Z_SIGN));
		saveSign(fixture, mineSign, mineBuilder, fixture.mineName(), "");
		Station mine = station(helper, fixture, fixture.mineName());
		finishScan(fixture, helper, mine);

		for (int y = 0; y <= PILLAR_TOP; y++) {
			helper.setBlock(new BlockPos(DEST_SUPPORT_X, y, DEST_SUPPORT_Z), Blocks.STONE);
		}
		BlockLocation sign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
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
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			if (requireRouteStarts(helper, fixture, fixture.homeExit(), binding)) {
				started[0]++;
			}
		});
	}

	@GameTest(maxTicks = 600)
	public void chainedFarUnitDepartsFromItsLocalDock(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());
		check(helper, home.isAnchored() && home.enabled(), "Home was not anchored");

		buildPillar(helper, 0, DEST_SUPPORT_Z);
		buildExitPlatform(helper, 0, Z_EXIT);
		BlockLocation edgeSign = placeWallSign(helper, 0, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, edgeSign, absolute(helper, 0, 2, Z_SIGN));
		saveSign(fixture, edgeSign, fixture.player(), fixture.edgeName(), "");
		Station edge = station(helper, fixture, fixture.edgeName());
		check(helper, edge.isAnchored() && edge.enabled(), "Edge was not anchored");

		// The x=3 unit is 2 blocks from the anchor, the x=7 unit is 4 blocks from
		// it and 6 blocks from the main dock.
		buildPillar(helper, DEST_SIGN_X, DEST_SUPPORT_Z);
		BlockLocation midSign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation midButton = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, midSign, midButton);
		saveSign(fixture, midSign, fixture.player(), fixture.homeName(), edge.displayName());

		buildPillar(helper, 7, DEST_SUPPORT_Z);
		buildExitPlatform(helper, 7, Z_EXIT);
		BlockLocation farSign = placeWallSign(helper, 7, 3, Z_SIGN, Direction.NORTH);
		BlockLocation farButton = placeWallButton(helper, 7, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, farSign, farButton);
		saveSign(fixture, farSign, fixture.player(), fixture.homeName(), edge.displayName());

		Binding far = state(fixture).binding(fixture.dimension(), farButton).orElse(null);
		check(helper, far != null, "far chained binding missing");
		BlockLocation farDock = absolute(helper, 7, DOCK_Y, Z_RAIL);
		check(helper, farDock.equals(far.sourceDock()), "far source dock mismatch: " + far.sourceDock());
		check(helper, Math.abs(farDock.x() - home.dock().x()) > 4,
				"fixture error: far pillar must be beyond the 4-block main reach");

		finishScan(fixture, helper, home);
		finishScan(fixture, helper, edge);
		int[] started = {0};
		helper.onEachTick(() -> {
			if (started[0] > 0) {
				helper.succeed();
				return;
			}
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			if (requireRouteStarts(helper, fixture, absolute(helper, 7, DOCK_Y, Z_EXIT), far)) {
				started[0]++;
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void nonChainedFarUnitIsRejected(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		station(helper, fixture, fixture.homeName());

		buildPillar(helper, 7, DEST_SUPPORT_Z);
		BlockLocation farSign = placeWallSign(helper, 7, 3, Z_SIGN, Direction.NORTH);
		BlockLocation farButton = placeWallButton(helper, 7, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, farSign, farButton);
		saveSign(fixture, farSign, fixture.player(), fixture.homeName(), fixture.mineName());

		SignBlockEntity entity = signEntity(fixture, farSign);
		SignRegistrationService.Result result = fixture.railway().signs()
				.register(fixture.player(), entity, true, entity.getText(true));
		check(helper, result instanceof SignRegistrationService.Result.Rejected,
				"non-chained far unit should be rejected, got " + result);
		FailureCode code = ((SignRegistrationService.Result.Rejected) result).failure().code();
		check(helper, code == FailureCode.SIGN_CHAIN, "expected SIGN_CHAIN, got " + code);
		check(helper, state(fixture).signMarker(fixture.dimension(), farSign).isEmpty(),
				"rejected far unit created a marker");
		check(helper, state(fixture).binding(fixture.dimension(), farButton).isEmpty(),
				"rejected far unit created a binding");
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void mainFlagMovesStationArrival(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());

		buildPillar(helper, DEST_SIGN_X, DEST_SUPPORT_Z);
		buildExitPlatform(helper, DEST_SIGN_X, Z_EXIT);
		ServerPlayer mover = opPlayer(helper, fixture, absolute(helper, DEST_SIGN_X, DOCK_Y, Z_EXIT));
		BlockLocation mainSign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mainButton = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mainSign, mainButton);
		saveSignWithFlags(fixture, mainSign, mover, fixture.homeName(), fixture.mineName(), "m");

		Station moved = station(helper, fixture, fixture.homeName());
		BlockLocation movedDock = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_RAIL);
		BlockLocation movedExit = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_EXIT);
		check(helper, moved.id().equals(home.id()), "main move changed the station identity");
		check(helper, movedDock.equals(moved.dock()), "main dock did not move: " + moved.dock());
		check(helper, movedExit.equals(moved.exit()), "main exit did not move: " + moved.exit());
		SignMarker mainMarker = state(fixture).mainMarkerForStation(home.id()).orElse(null);
		check(helper, mainMarker != null && mainMarker.sign().equals(mainSign),
				"main marker not recorded on the m sign");
		Binding mainBinding = state(fixture).binding(fixture.dimension(), mainButton).orElse(null);
		check(helper, mainBinding != null && movedDock.equals(mainBinding.sourceDock()),
				"m unit local source dock mismatch");

		ServerPlayer mineBuilder = opPlayer(helper, fixture, absolute(helper, MINE_X, DOCK_Y, Z_EXIT));
		BlockLocation mineSign = placeWallSign(helper, MINE_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mineButton = placeWallButton(helper, MINE_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mineSign, mineButton);
		saveSign(fixture, mineSign, mineBuilder, fixture.mineName(), fixture.homeName());
		Station mine = station(helper, fixture, fixture.mineName());
		Binding mineToHome = state(fixture).binding(fixture.dimension(), mineButton).orElse(null);
		check(helper, mineToHome != null, "Mine -> Home binding missing");

		finishScan(fixture, helper, moved);
		finishScan(fixture, helper, mine);
		ServerPlayer traveller = opPlayer(helper, fixture, mine.exit());
		TripId[] tripId = {null};
		helper.onEachTick(() -> {
			if (tripId[0] == null) {
				if (fixture.railway().trips().activeCount()
						>= fixture.railway().config().maxActiveTrips() - 1) {
					return;
				}
				TripService.StartResult start = fixture.railway().trips().start(traveller, mineToHome);
				if (start instanceof TripService.StartResult.Rejected rejected) {
					if (rejected.failure().code() == FailureCode.TRIP_LIMIT) {
						return;
					}
					throw helper.assertionException("trip to the moved main was rejected: "
							+ rejected.failure().code() + " " + rejected.failure().detail());
				}
				tripId[0] = ((TripService.StartResult.Started) start).id();
				return;
			}
			for (var active : fixture.railway().trips().activeTrips()) {
				if (active.id().equals(tripId[0])) {
					return;
				}
			}
			if (!traveller.blockPosition().equals(RailWorld.pos(moved.exit()))) {
				throw helper.assertionException("passenger at " + traveller.blockPosition()
						+ ", expected moved main exit " + moved.exit().shortString());
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 400)
	public void secondMainFlagRequiresClearingTheFirst(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());

		buildPillar(helper, DEST_SIGN_X, DEST_SUPPORT_Z);
		BlockLocation firstSign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation firstButton = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, firstSign, firstButton);
		saveSignWithFlags(fixture, firstSign, fixture.player(), fixture.homeName(), fixture.mineName(), "m");
		BlockLocation firstDock = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_RAIL);
		Station afterFirst = station(helper, fixture, fixture.homeName());
		check(helper, firstDock.equals(afterFirst.dock()), "first m did not move main");

		buildPillar(helper, 5, DEST_SUPPORT_Z);
		buildExitPlatform(helper, 5, Z_EXIT);
		BlockLocation secondSign = placeWallSign(helper, 5, 3, Z_SIGN, Direction.NORTH);
		BlockLocation secondButton = placeWallButton(helper, 5, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, secondSign, secondButton);
		saveSignWithFlags(fixture, secondSign, fixture.player(), fixture.homeName(), fixture.mineName(), "m");

		SignBlockEntity secondEntity = signEntity(fixture, secondSign);
		SignRegistrationService.Result conflict = fixture.railway().signs()
				.register(fixture.player(), secondEntity, true, secondEntity.getText(true));
		check(helper, conflict instanceof SignRegistrationService.Result.Rejected,
				"a second m should be rejected, got " + conflict);
		FailureCode conflictCode = ((SignRegistrationService.Result.Rejected) conflict).failure().code();
		check(helper, conflictCode == FailureCode.SIGN_MAIN_CONFLICT,
				"expected SIGN_MAIN_CONFLICT, got " + conflictCode);
		check(helper, state(fixture).signMarker(fixture.dimension(), secondSign).isEmpty(),
				"rejected second m created a marker");
		check(helper, firstSign.equals(state(fixture).mainMarkerForStation(home.id())
				.orElseThrow().sign()), "rejected second m changed the main marker");
		check(helper, firstDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"rejected second m moved the station main");

		saveSignWithFlags(fixture, firstSign, fixture.player(), fixture.homeName(), fixture.mineName(), "");
		check(helper, state(fixture).mainMarkerForStation(home.id()).isEmpty(),
				"clearing m did not clear the main flag");
		check(helper, firstDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"clearing m reverted the sticky main dock");

		saveSignWithFlags(fixture, secondSign, fixture.player(), fixture.homeName(), fixture.mineName(), "m");
		Station afterSecond = station(helper, fixture, fixture.homeName());
		BlockLocation secondDock = absolute(helper, 5, DOCK_Y, Z_RAIL);
		check(helper, secondDock.equals(afterSecond.dock()), "second m did not move main: " + afterSecond.dock());
		SignMarker newMain = state(fixture).mainMarkerForStation(home.id()).orElse(null);
		check(helper, newMain != null && newMain.sign().equals(secondSign),
				"second m did not become the main marker");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void breakingMainSignKeepsStationAndOtherUnits(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());

		buildPillar(helper, DEST_SIGN_X, DEST_SUPPORT_Z);
		BlockLocation mainSign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mainButton = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mainSign, mainButton);
		saveSignWithFlags(fixture, mainSign, fixture.player(), fixture.homeName(), fixture.mineName(), "m");
		BlockLocation mainDock = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_RAIL);
		BlockLocation mainExit = state(fixture).station(home.id()).orElseThrow().exit();
		check(helper, mainDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"m did not move main before the break");

		breakBlockThroughService(fixture, mainSign);

		Station afterMainBreak = state(fixture).station(home.id()).orElse(null);
		check(helper, afterMainBreak != null, "station disappeared with the main sign");
		check(helper, afterMainBreak.enabled(), "station was disabled by breaking a non-anchor main sign");
		check(helper, mainDock.equals(afterMainBreak.dock()), "station main dock was lost");
		check(helper, mainExit.equals(afterMainBreak.exit()), "station main exit was lost");
		check(helper, state(fixture).mainMarkerForStation(home.id()).isEmpty(),
				"main flag survived the sign break");
		check(helper, state(fixture).signMarker(fixture.dimension(), homeSign).isPresent(),
				"anchor unit was removed with the main sign");
		check(helper, state(fixture).signUnitCount(home.id()) == 1, "unexpected unit count after main break");

		breakBlockThroughService(fixture, homeSign);

		Station afterAnchorBreak = state(fixture).station(home.id()).orElse(null);
		check(helper, afterAnchorBreak != null, "station was removed with its last sign");
		check(helper, !afterAnchorBreak.enabled(), "station was not disabled with its last unit");
		check(helper, mainDock.equals(afterAnchorBreak.dock()), "station main dock was lost with the last unit");
		check(helper, mainExit.equals(afterAnchorBreak.exit()), "station main exit was lost with the last unit");
		check(helper, state(fixture).signUnitCount(home.id()) == 0, "last unit left a marker");
		helper.succeed();
	}

	@GameTest(maxTicks = 400)
	public void centralMainOnlyUnitMovesArrivalAndClears(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());

		buildPillar(helper, DEST_SIGN_X, DEST_SUPPORT_Z);
		BlockLocation sideOneSign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation sideOneButton = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sideOneSign, sideOneButton);
		saveSign(fixture, sideOneSign, fixture.player(), fixture.homeName(), fixture.mineName());

		buildPillar(helper, 5, DEST_SUPPORT_Z);
		BlockLocation sideTwoSign = placeWallSign(helper, 5, 3, Z_SIGN, Direction.NORTH);
		BlockLocation sideTwoButton = placeWallButton(helper, 5, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sideTwoSign, sideTwoButton);
		saveSign(fixture, sideTwoSign, fixture.player(), fixture.homeName(), fixture.mineName());

		// The central pillar has no route and no button, only the m flag.
		buildPillar(helper, 4, DEST_SUPPORT_Z);
		buildExitPlatform(helper, 4, Z_EXIT);
		ServerPlayer mover = opPlayer(helper, fixture, absolute(helper, 4, DOCK_Y, Z_EXIT));
		BlockLocation centralSign = placeWallSign(helper, 4, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, centralSign, absolute(helper, 4, 2, Z_SIGN));
		saveSignWithFlags(fixture, centralSign, mover, fixture.homeName(), "", "m");

		BlockLocation centralDock = absolute(helper, 4, DOCK_Y, Z_RAIL);
		BlockLocation centralExit = absolute(helper, 4, DOCK_Y, Z_EXIT);
		check(helper, centralDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"central m did not move the main dock");
		check(helper, centralExit.equals(state(fixture).station(home.id()).orElseThrow().exit()),
				"central m did not move the main exit");
		SignMarker centralMarker = state(fixture).signMarker(fixture.dimension(), centralSign).orElse(null);
		check(helper, centralMarker != null, "central main-only marker missing");
		check(helper, !centralMarker.isAnchor() && !centralMarker.hasButton(),
				"central main-only unit must not anchor or carry a button");
		check(helper, home.id().equals(centralMarker.stationId()), "central marker owner mismatch");
		check(helper, centralMarker.main(), "central marker is not main");
		check(helper, state(fixture).signUnitCount(home.id()) == 4,
				"unexpected unit count: " + state(fixture).signUnitCount(home.id()));
		check(helper, state(fixture).bindings().stream()
				.noneMatch(binding -> centralDock.equals(binding.sourceDock())),
				"central main-only unit created a binding");

		// A main-only unit is owned by its station: renaming line 2 is rejected and
		// the previous text is restored.
		String intruder = fixture.homeName().replace("Home", "Nope");
		saveSignWithFlags(fixture, centralSign, mover, intruder, "", "m");
		check(helper, fixture.homeName().equals(rawLine(fixture, centralSign, true, 1)),
				"renaming a main-only unit was not reverted: " + rawLine(fixture, centralSign, true, 1));
		check(helper, home.id().equals(state(fixture).signMarker(fixture.dimension(), centralSign)
				.orElseThrow().stationId()), "renaming a main-only unit changed its owner");
		check(helper, centralDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"renaming a main-only unit moved the station main");

		// Clearing m on the main-only unit leaves main sticky and the unit registered.
		saveSignWithFlags(fixture, centralSign, mover, fixture.homeName(), "", "");
		check(helper, state(fixture).mainMarkerForStation(home.id()).isEmpty(),
				"clearing m did not clear the main flag");
		check(helper, centralDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"clearing m reverted the sticky main dock");
		check(helper, state(fixture).signMarker(fixture.dimension(), centralSign).isPresent(),
				"clearing m removed the station-only unit");

		// With no main marker, m can move to a side unit and later back to central.
		saveSignWithFlags(fixture, sideOneSign, mover, fixture.homeName(), fixture.mineName(), "m");
		BlockLocation sideOneDock = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_RAIL);
		check(helper, sideOneDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"m did not move to the side unit");
		saveSignWithFlags(fixture, sideOneSign, mover, fixture.homeName(), fixture.mineName(), "");
		saveSignWithFlags(fixture, centralSign, mover, fixture.homeName(), "", "m");
		check(helper, centralDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"central m did not move main back");

		ServerPlayer mineBuilder = opPlayer(helper, fixture, absolute(helper, MINE_X, DOCK_Y, Z_EXIT));
		BlockLocation mineSign = placeWallSign(helper, MINE_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mineButton = placeWallButton(helper, MINE_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mineSign, mineButton);
		saveSign(fixture, mineSign, mineBuilder, fixture.mineName(), fixture.homeName());
		Station mine = station(helper, fixture, fixture.mineName());
		Binding mineToHome = state(fixture).binding(fixture.dimension(), mineButton).orElse(null);
		check(helper, mineToHome != null, "Mine -> Home binding missing");

		finishScan(fixture, helper, home);
		finishScan(fixture, helper, mine);
		ServerPlayer traveller = opPlayer(helper, fixture, mine.exit());
		TripId[] tripId = {null};
		helper.onEachTick(() -> {
			if (tripId[0] == null) {
				if (fixture.railway().trips().activeCount()
						>= fixture.railway().config().maxActiveTrips() - 1) {
					return;
				}
				TripService.StartResult start = fixture.railway().trips().start(traveller, mineToHome);
				if (start instanceof TripService.StartResult.Rejected rejected) {
					throw helper.assertionException("trip to the central main was rejected: "
							+ rejected.failure().code() + " " + rejected.failure().detail());
				}
				tripId[0] = ((TripService.StartResult.Started) start).id();
				return;
			}
			for (var active : fixture.railway().trips().activeTrips()) {
				if (active.id().equals(tripId[0])) {
					return;
				}
			}
			if (!traveller.blockPosition().equals(RailWorld.pos(centralExit))) {
				throw helper.assertionException("passenger at " + traveller.blockPosition()
						+ ", expected central main exit " + centralExit.shortString());
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 200)
	public void verifyMarkersRemovesDirectlyBrokenSign(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());
		Station home = station(helper, fixture, fixture.homeName());
		BlockLocation dock = home.dock();
		BlockLocation exit = home.exit();
		check(helper, state(fixture).binding(fixture.dimension(), button).isPresent(),
				"binding missing before direct sign removal");

		// Remove the block directly: the player-break event never runs.
		fixture.level().setBlock(RailWorld.pos(sign), Blocks.AIR.defaultBlockState(), 3);
		fixture.railway().signs().verifyMarkers(fixture.server());

		check(helper, state(fixture).signMarker(fixture.dimension(), sign).isEmpty(),
				"marker survived a directly removed sign");
		check(helper, state(fixture).binding(fixture.dimension(), button).isEmpty(),
				"binding survived a directly removed sign");
		Station after = state(fixture).station(home.id()).orElse(null);
		check(helper, after != null && after.isAnchored(), "station data was lost");
		check(helper, dock.equals(after.dock()) && exit.equals(after.exit()), "station dock/exit changed");
		check(helper, !after.enabled(), "station was not disabled when its last unit disappeared");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void verifyMarkersRemovesDirectlyBrokenButton(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation sign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation button = placeWallButton(helper, HOME_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, sign, button);
		saveSign(fixture, sign, fixture.player(), fixture.homeName(), fixture.mineName());
		Station home = station(helper, fixture, fixture.homeName());

		fixture.level().setBlock(RailWorld.pos(button), Blocks.AIR.defaultBlockState(), 3);
		fixture.railway().signs().verifyMarkers(fixture.server());

		check(helper, state(fixture).binding(fixture.dimension(), button).isEmpty(),
				"binding survived a directly removed button");
		SignMarker marker = state(fixture).signMarker(fixture.dimension(), sign).orElse(null);
		check(helper, marker != null, "anchor marker was removed with a directly removed button");
		check(helper, marker.isAnchor() && !marker.hasButton(),
				"anchor marker should keep only the anchor: " + marker);
		Station after = state(fixture).station(home.id()).orElse(null);
		check(helper, after != null && after.enabled(), "station should stay enabled after button removal");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void verifyMarkersKeepsMainAndDisablesOnlyWithTheLastUnit(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper);
		BlockLocation homeSign = placeWallSign(helper, HOME_X, 3, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, homeSign, absolute(helper, HOME_X, 2, Z_SIGN));
		saveSign(fixture, homeSign, fixture.player(), fixture.homeName(), "");
		Station home = station(helper, fixture, fixture.homeName());

		buildPillar(helper, DEST_SIGN_X, DEST_SUPPORT_Z);
		buildExitPlatform(helper, DEST_SIGN_X, Z_EXIT);
		ServerPlayer mover = opPlayer(helper, fixture, absolute(helper, DEST_SIGN_X, DOCK_Y, Z_EXIT));
		BlockLocation mainSign = placeWallSign(helper, DEST_SIGN_X, 3, Z_SIGN, Direction.NORTH);
		BlockLocation mainButton = placeWallButton(helper, DEST_SIGN_X, 2, Z_SIGN, Direction.NORTH);
		clearRegistration(fixture, mainSign, mainButton);
		saveSignWithFlags(fixture, mainSign, mover, fixture.homeName(), fixture.mineName(), "m");
		BlockLocation mainDock = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_RAIL);
		BlockLocation mainExit = absolute(helper, DEST_SIGN_X, DOCK_Y, Z_EXIT);
		check(helper, mainDock.equals(state(fixture).station(home.id()).orElseThrow().dock()),
				"m did not move main before verification");
		check(helper, state(fixture).signUnitCount(home.id()) == 2, "unexpected unit count");

		fixture.level().setBlock(RailWorld.pos(mainSign), Blocks.AIR.defaultBlockState(), 3);
		fixture.railway().signs().verifyMarkers(fixture.server());

		SignMarker anchor = state(fixture).signMarker(fixture.dimension(), homeSign).orElse(null);
		check(helper, anchor != null, "anchor unit was removed with the main sign");
		check(helper, state(fixture).mainMarkerForStation(home.id()).isEmpty(),
				"main flag survived the removed main sign");
		Station afterMain = state(fixture).station(home.id()).orElseThrow();
		check(helper, afterMain.enabled(), "station was disabled while another unit remained");
		check(helper, mainDock.equals(afterMain.dock()) && mainExit.equals(afterMain.exit()),
				"stored main dock/exit changed when the main unit was removed");

		fixture.level().setBlock(RailWorld.pos(homeSign), Blocks.AIR.defaultBlockState(), 3);
		fixture.railway().signs().verifyMarkers(fixture.server());

		Station afterLast = state(fixture).station(home.id()).orElse(null);
		check(helper, afterLast != null, "station data was removed with its last sign");
		check(helper, state(fixture).signUnitCount(home.id()) == 0, "marker left after last unit removal");
		check(helper, !afterLast.enabled(), "station was not disabled after its last unit disappeared");
		check(helper, mainDock.equals(afterLast.dock()) && mainExit.equals(afterLast.exit()),
				"station main dock/exit was lost with its last unit");
		helper.succeed();
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
			String edgeName,
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
				"Sign Home " + unique, "Sign Mine " + unique, "Sign Edge " + unique,
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
		saveSignWithFlags(fixture, sign, player, from, to, "");
	}

	/** Saves all four lines so clearing the optional flag line is observable. */
	private static void saveSignWithFlags(
			Fixture fixture,
			BlockLocation sign,
			ServerPlayer player,
			String from,
			String to,
			String flags
	) {
		saveSignLines(fixture, sign, player, true, "[Railway]", from, to, flags);
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
		saveSignLines(fixture, sign, player, front, line1, line2, line3, "");
	}

	private static void saveSignLines(
			Fixture fixture,
			BlockLocation sign,
			ServerPlayer player,
			boolean front,
			String line1,
			String line2,
			String line3,
			String line4
	) {
		SignBlockEntity entity = signEntity(fixture, sign);
		entity.setAllowedPlayerEditor(player.getUUID());
		entity.updateSignText(player, front, List.of(
				FilteredText.passThrough(line1),
				FilteredText.passThrough(line2),
				FilteredText.passThrough(line3),
				FilteredText.passThrough(line4)));
	}

	private static void buildPillar(GameTestHelper helper, int x, int z) {
		for (int y = 0; y <= PILLAR_TOP; y++) {
			helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
			helper.setBlock(new BlockPos(x, y, z - 1), Blocks.AIR);
		}
	}

	private static void buildExitPlatform(GameTestHelper helper, int x, int z) {
		helper.setBlock(new BlockPos(x, DOCK_Y - 1, z), Blocks.STONE);
		for (int y = DOCK_Y; y <= PILLAR_TOP; y++) {
			helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
		}
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

	/**
	 * Starts and aborts one route. Returns false when the shared server has no free
	 * active-trip slot yet so the caller can retry on the next tick.
	 */
	private static boolean requireRouteStarts(
			GameTestHelper helper,
			Fixture fixture,
			BlockLocation exit,
			Binding binding
	) {
		ServerPlayer player = opPlayer(helper, fixture, exit);
		TripService.StartResult start = fixture.railway().trips().start(player, binding);
		if (start instanceof TripService.StartResult.Rejected rejected) {
			if (rejected.failure().code() == FailureCode.TRIP_LIMIT) {
				return false;
			}
			throw helper.assertionException("route " + binding.fromStationId() + " -> " + binding.toStationId()
					+ " rejected: " + rejected.failure().code() + " " + rejected.failure().detail());
		}
		TripId id = ((TripService.StartResult.Started) start).id();
		TripService.AbortResult abort = fixture.railway().trips().abort(id);
		if (!(abort instanceof TripService.AbortResult.Aborted)) {
			throw helper.assertionException("route trip did not abort cleanly: " + abort);
		}
		player.teleportTo(exit.x() + 0.5, exit.y(), exit.z() + 0.5);
		return true;
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
