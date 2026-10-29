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
import dev.nikhcevov.railway.network.RailGraph;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * End-to-end server game tests. Each test builds its own straight line inside the
 * empty 8x8 test plot, registers stations and launches a real server player.
 */
public final class RailwayGameTests implements CustomTestMethodInvoker {
	private static final int LINE_END = 7;
	private static final int HOME_DOCK = 1;
	private static final int MINE_DOCK = 6;

	@GameTest(maxTicks = 600)
	public void passengerTravelsHomeToMine(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip);
		assertArrival(helper, fixture, player, fixture.mineExit, trip);
	}

	@GameTest(maxTicks = 100)
	public void poweredRailDoesNotBoostManagedCartDirectly(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockState powered = Blocks.POWERED_RAIL.defaultBlockState()
				.setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST)
				.setValue(PoweredRailBlock.POWERED, true);
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.STONE);
		BlockPos railPos = helper.absolutePos(new BlockPos(0, 1, 0));
		level.setBlock(railPos, powered, 2);

		Minecart cart = EntityType.MINECART.create(level, EntitySpawnReason.COMMAND);
		if (cart == null) {
			throw helper.assertionException("could not create test cart");
		}
		cart.addTag(PlayerActions.MANAGED_TAG);
		cart.setPos(railPos.getX() + 0.5, railPos.getY(), railPos.getZ() + 0.5);
		cart.setDeltaMovement(0.2, 0.0, 0.0);
		if (!level.addFreshEntity(cart)) {
			throw helper.assertionException("could not add test cart");
		}

		int[] ticks = {0};
		helper.onEachTick(() -> {
			if (++ticks[0] < 2) {
				return;
			}
			double speed = cart.getDeltaMovement().horizontalDistance();
			if (speed > 0.22) {
				throw helper.assertionException("managed cart was boosted by powered rail: " + speed);
			}
			cart.discard();
			helper.succeed();
		});
	}


	@GameTest(maxTicks = 200)
	public void disabledStationsRejectTripsWithNames(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "disabled");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		Railway railway = fixture.railway();
		long revision = railway.state().nextRevision();
		railway.state().updateStation(fixture.home().withEnabled(false, revision));

		TripService.StartResult fromDisabled = railway.trips().start(player, fixture.homeToMine);
		if (!(fromDisabled instanceof TripService.StartResult.Rejected rejected)
				|| rejected.failure().code() != FailureCode.STATION_DISABLED) {
			throw helper.assertionException("disabled source station did not reject the trip: " + fromDisabled);
		}
		if (!fixture.home().displayName().equals(rejected.failure().detail())) {
			throw helper.assertionException("disabled source failure did not use the station name: "
					+ rejected.failure().detail());
		}

		revision = railway.state().nextRevision();
		railway.state().updateStation(fixture.home().withEnabled(true, revision));
		railway.state().updateStation(fixture.mine().withEnabled(false, revision));
		TripService.StartResult toDisabled = railway.trips().start(player, fixture.homeToMine);
		if (!(toDisabled instanceof TripService.StartResult.Rejected destinationRejected)
				|| destinationRejected.failure().code() != FailureCode.STATION_DISABLED) {
			throw helper.assertionException("disabled destination station did not reject the trip: " + toDisabled);
		}
		if (!fixture.mine().displayName().equals(destinationRejected.failure().detail())) {
			throw helper.assertionException("disabled destination failure did not use the station name: "
					+ destinationRejected.failure().detail());
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void passengerTravelsMineToHome(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "r");
		ServerPlayer player = fixture.newPlayer(helper, fixture.mineExit);
		TripId[] trip = {null};
		startWhenFree(helper, fixture, player, fixture.mineToHome, trip);
		assertArrival(helper, fixture, player, fixture.homeExit, trip);
	}

	/**
	 * A destination does not need its own scan job when the source station's graph
	 * already covers the destination dock, as happens after a restart.
	 */
	@GameTest(maxTicks = 600)
	public void sourceGraphAlonePlansWhenDestinationHasNoScan(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "g");
		ScanService scans = fixture.railway().scans();
		scans.invalidate(fixture.mine().id());
		if (scans.graph(fixture.home().id()) == null
				|| scans.graph(fixture.home().id()).rail(fixture.mine().dock()) == null) {
			throw helper.assertionException("source graph does not contain the destination dock");
		}
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip);
		assertArrival(helper, fixture, player, fixture.mineExit, trip);
	}

	/**
	 * A missing graph is rebuilt without any manual command: the first launch
	 * starts the scans and is rejected with {@code SCAN_RUNNING}, and the next
	 * launch succeeds once the background scans finish.
	 */
	@GameTest(maxTicks = 600)
	public void missingGraphAutoStartsScanAndRetrySucceeds(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "auto");
		ScanService scans = fixture.railway().scans();
		scans.invalidate(fixture.home().id());
		scans.invalidate(fixture.mine().id());
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		int[] phase = {0};
		helper.onEachTick(() -> {
			if (trip[0] != null) {
				return;
			}
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			if (phase[0] == 0) {
				TripService.StartResult first = fixture.railway().trips().start(player, fixture.homeToMine);
				if (!(first instanceof TripService.StartResult.Rejected rejected)) {
					throw helper.assertionException("trip started without a scanned graph");
				}
				switch (rejected.failure().code()) {
					case TRIP_LIMIT, RECOVERY_PENDING -> {
						return;
					}
					case SCAN_RUNNING -> {
						if (!scans.isRunning(fixture.home().id())) {
							throw helper.assertionException("auto-start did not launch a source scan");
						}
						phase[0] = 1;
						return;
					}
					default -> throw helper.assertionException(
							"unexpected failure before auto-scan: " + rejected.failure().code());
				}
			}
			if (scans.isRunning(fixture.home().id()) || scans.isRunning(fixture.mine().id())) {
				return;
			}
			if (scans.graph(fixture.home().id()) == null || scans.graph(fixture.mine().id()) == null) {
				throw helper.assertionException("auto-started scan did not complete: "
						+ scans.failure(fixture.home().id()) + " / " + scans.failure(fixture.mine().id()));
			}
			TripService.StartResult second = fixture.railway().trips().start(player, fixture.homeToMine);
			if (second instanceof TripService.StartResult.Rejected rejected) {
				if (rejected.failure().code() == FailureCode.TRIP_LIMIT
						|| rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				throw helper.assertionException("retry after auto-scan was rejected: "
						+ rejected.failure().code());
			}
			trip[0] = ((TripService.StartResult.Started) second).id();
		});
		assertArrival(helper, fixture, player, fixture.mineExit, trip);
	}

	/**
	 * The startup hook queues anchored, loadable stations, starts them one at a
	 * time, skips provisional stations, and shares the first component graph with
	 * the other station instead of scanning twice. The real server-start
	 * invocation cannot be replayed inside a shared GameTest server, so this
	 * exercises the same production method with an explicit station list.
	 */
	@GameTest(maxTicks = 600)
	public void startupScansAnchoredStationsOnly(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "boot");
		ScanService scans = fixture.railway().scans();
		scans.invalidate(fixture.home().id());
		scans.invalidate(fixture.mine().id());
		Station provisional = Station.provisional(new StationId("gtprov" + (System.nanoTime() & 0xFFFFFFL)),
				"Game Test Provisional", fixture.home().dimension(),
				fixture.railway().state().nextRevision());
		fixture.railway().startScansFor(List.of(fixture.home(), fixture.mine(), provisional));
		if (!scans.isRunning(fixture.home().id()) && scans.graph(fixture.home().id()) == null) {
			throw helper.assertionException("startup did not start a scan for anchored stations");
		}
		if (scans.status(provisional.id()).isPresent()) {
			throw helper.assertionException("startup scanned a provisional station");
		}
		helper.succeedWhen(() -> {
			ScanService.Status mineStatus = scans.status(fixture.mine().id()).orElse(null);
			if (scans.graph(fixture.home().id()) == null || mineStatus == null
					|| mineStatus.graph() == null) {
				throw helper.assertionException("startup scans did not complete");
			}
			if (!fixture.home().id().equals(mineStatus.coveredBy())) {
				throw helper.assertionException("startup did not share the component graph");
			}
		});
	}

	/**
	 * Two stations on one component share a single actual scan during startup,
	 * while an isolated station still gets its own scan. Invalidating a block on
	 * the shared graph removes it for both of its users.
	 */
	@GameTest(maxTicks = 600)
	public void startupScansOneComponentOnce(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "share");
		Railway railway = fixture.railway();
		ScanService scans = railway.scans();
		Station isolated = buildIsolatedStation(helper, railway, "share");
		scans.invalidate(fixture.home().id());
		scans.invalidate(fixture.mine().id());
		scans.invalidate(isolated.id());

		railway.startScansFor(List.of(fixture.home(), fixture.mine(), isolated));

		helper.succeedWhen(() -> {
			RailGraph homeGraph = scans.graph(fixture.home().id());
			RailGraph mineGraph = scans.graph(fixture.mine().id());
			RailGraph isolatedGraph = scans.graph(isolated.id());
			ScanService.Status homeStatus = scans.status(fixture.home().id()).orElse(null);
			ScanService.Status mineStatus = scans.status(fixture.mine().id()).orElse(null);
			ScanService.Status isolatedStatus = scans.status(isolated.id()).orElse(null);
			if (homeGraph == null || mineGraph == null || isolatedGraph == null
					|| homeStatus == null || mineStatus == null || isolatedStatus == null) {
				throw helper.assertionException("component startup scans did not complete");
			}
			if (homeGraph != mineGraph) {
				throw helper.assertionException("stations on one component received different graphs");
			}
			if (!fixture.home().id().equals(mineStatus.coveredBy())
					|| !homeStatus.jobId().equals(mineStatus.jobId())) {
				throw helper.assertionException("covered station does not report the shared scan");
			}
			if (!homeStatus.covered().contains(fixture.mine().id())) {
				throw helper.assertionException("owner scan does not list the covered station");
			}
			if (scans.all().stream().anyMatch(status -> status.station().equals(fixture.mine().id()))) {
				throw helper.assertionException("covered station started its own scan job");
			}
			if (isolatedStatus.coveredBy() != null
					|| homeStatus.jobId().equals(isolatedStatus.jobId())
					|| isolatedGraph.componentKey().equals(homeGraph.componentKey())) {
				throw helper.assertionException("isolated station did not scan independently");
			}
			scans.invalidate(fixture.mine().dock());
			if (scans.graph(fixture.home().id()) != null || scans.graph(fixture.mine().id()) != null) {
				throw helper.assertionException("invalidating a shared graph left it usable");
			}
			if (scans.graph(isolated.id()) == null) {
				throw helper.assertionException("unrelated component was invalidated");
			}
		});
	}

	/** Invalidating a covered station must drop the graph for every station sharing it. */
	@GameTest(maxTicks = 600)
	public void invalidatingCoveredStationDropsSharedGraph(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "aliasinv");
		ScanService scans = fixture.railway().scans();
		scans.invalidate(fixture.home().id());
		scans.invalidate(fixture.mine().id());
		fixture.railway().startScansFor(List.of(fixture.home(), fixture.mine()));

		helper.succeedWhen(() -> {
			ScanService.Status mineStatus = scans.status(fixture.mine().id()).orElse(null);
			if (mineStatus == null || !fixture.home().id().equals(mineStatus.coveredBy())) {
				throw helper.assertionException("startup did not share the component graph");
			}
			scans.invalidate(fixture.mine().id());
			if (scans.graph(fixture.home().id()) != null || scans.graph(fixture.mine().id()) != null) {
				throw helper.assertionException("alias invalidation left a usable stale graph");
			}
			if (scans.status(fixture.home().id()).isPresent()) {
				throw helper.assertionException("shared owner job survived an alias invalidation");
			}
		});
	}

	/** A manual scan of a covered station clears the alias and starts a real job. */
	@GameTest(maxTicks = 600)
	public void manualScanOfCoveredStationClearsAlias(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "aliasmanual");
		ScanService scans = fixture.railway().scans();
		scans.invalidate(fixture.home().id());
		scans.invalidate(fixture.mine().id());
		fixture.railway().startScansFor(List.of(fixture.home(), fixture.mine()));

		helper.succeedWhen(() -> {
			ScanService.Status mineStatus = scans.status(fixture.mine().id()).orElse(null);
			if (mineStatus == null || !fixture.home().id().equals(mineStatus.coveredBy())) {
				throw helper.assertionException("startup did not share the component graph");
			}
			String ownerJob = mineStatus.jobId();
			ScanService.StartResult result = scans.start(fixture.mine(), fixture.level(),
					fixture.railway().config(), fixture.railway().server().getTickCount(),
					fixture.railway().state().revision());
			if (!(result instanceof ScanService.StartResult.Started)) {
				throw helper.assertionException("manual scan of a covered station was rejected: " + result);
			}
			ScanService.Status ownStatus = scans.status(fixture.mine().id()).orElse(null);
			if (ownStatus == null || ownStatus.coveredBy() != null || !ownStatus.running()
					|| ownStatus.jobId().equals(ownerJob)) {
				throw helper.assertionException("manual scan did not replace the shared alias");
			}
			if (scans.graph(fixture.home().id()) == null) {
				throw helper.assertionException("manual scan of a covered station dropped the owner graph");
			}
		});
	}

	@GameTest(maxTicks = 600)
	public void dismountDuringTripCleansUp(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "s");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		int[] startedTick = {-1};
		boolean[] dismounted = {false};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip, startedTick);
		helper.onEachTick(() -> {
			if (trip[0] == null || dismounted[0]) {
				return;
			}
			if (fixture.railway().server().getTickCount() - startedTick[0] < 25) {
				return;
			}
			if (player.getVehicle() == null) {
				throw helper.assertionException("passenger was not mounted before dismount");
			}
			player.stopRiding();
			dismounted[0] = true;
		});
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway, trip[0]);
			requireNoManagedCart(helper, fixture.level);
		});
	}

	@GameTest(maxTicks = 600)
	public void secondPlayerCannotStartSameLine(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "c");
		ServerPlayer first = fixture.newPlayer(helper, fixture.homeExit);
		ServerPlayer second = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		helper.onEachTick(() -> {
			if (trip[0] != null) {
				return;
			}
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			trip[0] = startIfReady(helper, fixture, first, fixture.homeToMine);
			if (trip[0] == null) {
				return;
			}
			TripService.StartResult secondStart = fixture.railway.trips().start(second, fixture.homeToMine);
			if (!(secondStart instanceof TripService.StartResult.Rejected rejected)) {
				throw helper.assertionException("second launch unexpectedly succeeded");
			}
			Set<FailureCode> expected = Set.of(FailureCode.DOCK_OCCUPIED, FailureCode.PATH_OCCUPIED,
					FailureCode.RESERVATION_CONFLICT);
			if (!expected.contains(rejected.failure().code())) {
				throw helper.assertionException("unexpected rejection: " + rejected.failure().code());
			}
		});
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway, trip[0]);
			requireNoManagedCart(helper, fixture.level);
		});
	}

	@GameTest(maxTicks = 600)
	public void brokenRailAbortsSafely(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "b");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		int[] startedTick = {-1};
		boolean[] broken = {false};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip, startedTick);
		helper.onEachTick(() -> {
			if (trip[0] == null || broken[0]) {
				return;
			}
			if (fixture.railway().server().getTickCount() - startedTick[0] < 25) {
				return;
			}
			BlockPos brokenPos = helper.absolutePos(new BlockPos(MINE_DOCK - 2, 1, 0));
			helper.getLevel().setBlock(brokenPos, Blocks.AIR.defaultBlockState(), 3);
			broken[0] = true;
		});
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway, trip[0]);
			requireNoManagedCart(helper, fixture.level);
		});
	}

	@GameTest(maxTicks = 600)
	public void disconnectCleansUpTrip(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "d");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		int[] startedTick = {-1};
		boolean[] disconnected = {false};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip, startedTick);
		helper.onEachTick(() -> {
			if (trip[0] == null || disconnected[0]) {
				return;
			}
			if (fixture.railway().server().getTickCount() - startedTick[0] < 20) {
				return;
			}
			fixture.railway().trips().playerLeft(player.getUUID());
			disconnected[0] = true;
		});
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway(), trip[0]);
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
				if (rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				throw helper.assertionException("trip " + completed[0] + " rejected: " + rejected.failure().code());
			}
			current[0] = ((TripService.StartResult.Started) start).id();
		});
	}

	@GameTest(maxTicks = 600)
	public void unsafeDestinationExitRejectsLaunch(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "e");
		BlockPos support = helper.absolutePos(new BlockPos(MINE_DOCK, 0, 2));
		helper.getLevel().setBlock(support, Blocks.AIR.defaultBlockState(), 3);
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		helper.onEachTick(() -> {
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			TripService.StartResult result = fixture.railway.trips().start(player, fixture.homeToMine);
			if (result instanceof TripService.StartResult.Rejected rejected) {
				if (rejected.failure().code() == FailureCode.TRIP_LIMIT
						|| rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
					return;
				}
				if (rejected.failure().code() != FailureCode.EXIT_UNSAFE) {
					throw helper.assertionException("expected EXIT_UNSAFE, got " + rejected.failure().code());
				}
				requireNoManagedCart(helper, fixture.level);
				helper.succeed();
				return;
			}
			throw helper.assertionException("expected EXIT_UNSAFE, got " + result);
		});
	}

	@GameTest(maxTicks = 600)
	public void ticketWindowIsHeldWhileRunningAndReleasedOnArrival(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "k");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		boolean[] ticketObserved = {false};
		boolean[] readinessObserved = {false};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip);
		helper.onEachTick(() -> {
			if (trip[0] == null || !tripActive(fixture.railway(), trip[0])) {
				return;
			}
			if (fixture.railway().tickets().tripTicketCount(trip[0]) < 1) {
				throw helper.assertionException("active trip holds no chunk tickets");
			}
			ticketObserved[0] = true;
			var stats = fixture.railway().tickets().stats(trip[0]).orElseThrow();
			if (stats.readinessChecks() > 0) {
				readinessObserved[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway(), trip[0]);
			if (!ticketObserved[0]) {
				throw helper.assertionException("ticket window was never observed");
			}
			if (!readinessObserved[0]) {
				throw helper.assertionException("entity-ticking readiness was never consulted");
			}
			if (fixture.railway().tickets().tripTicketCount(trip[0]) != 0) {
				throw helper.assertionException("arrival leaked chunk tickets");
			}
			if (fixture.railway().tickets().stats(trip[0]).isPresent()) {
				throw helper.assertionException("ticket state survived arrival");
			}
			requireNoManagedCart(helper, fixture.level());
		});
	}

	@GameTest(maxTicks = 600)
	public void ticketsAreReleasedWhenTheTripIsAborted(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "u");
		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		int[] startedTick = {-1};
		boolean[] left = {false};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip, startedTick);
		helper.onEachTick(() -> {
			if (trip[0] == null || left[0]) {
				return;
			}
			if (fixture.railway().server().getTickCount() - startedTick[0] < 15) {
				return;
			}
			if (fixture.railway().tickets().tripTicketCount(trip[0]) < 1) {
				throw helper.assertionException("trip lost its chunk tickets before abort");
			}
			fixture.railway().trips().playerLeft(player.getUUID());
			left[0] = true;
		});
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway(), trip[0]);
			if (fixture.railway().tickets().tripTicketCount(trip[0]) != 0) {
				throw helper.assertionException("abort leaked chunk tickets");
			}
			requireNoManagedCart(helper, fixture.level());
		});
	}

	@GameTest(maxTicks = 600)
	public void unloadedDestinationExitIsDeferredUntilArrival(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "x");
		BlockLocation farExit = new BlockLocation(fixture.mineExit().x() + 256 * 16,
				fixture.mineExit().y(), fixture.mineExit().z());
		Station mine = fixture.mine();
		fixture.railway().state().updateStation(
				mine.withExit(farExit, 0.0F, fixture.railway().state().nextRevision()));
		if (new RailWorld(fixture.level()).isLoaded(farExit)) {
			throw helper.assertionException("far exit chunk unexpectedly loaded");
		}

		ServerPlayer player = fixture.newPlayer(helper, fixture.homeExit);
		TripId[] trip = {null};
		startWhenFree(helper, fixture, player, fixture.homeToMine, trip);
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway(), trip[0]);
			if (fixture.railway().tickets().tripTicketCount(trip[0]) != 0) {
				throw helper.assertionException("deferred arrival leaked chunk tickets");
			}
			requireNoManagedCart(helper, fixture.level());
		});
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

		Station home = new Station(homeId, "Game Test Home " + unique, dimension, homeDock, homeExit, 0.0F, true,
				railway.state().nextRevision());
		Station mine = new Station(mineId, "Game Test Mine " + unique, dimension, mineDock, mineExit, 0.0F, true,
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

	/**
	 * A new branch starts outside the old graph. Placing its first rail must
	 * invalidate the stale component graph, otherwise sign registration rejects the
	 * new local dock with SIGN_GEOMETRY before a rescan can run.
	 */
	@GameTest(maxTicks = 200)
	public void placingAdjacentRailInvalidatesScannedGraph(GameTestHelper helper) {
		Fixture fixture = buildFixture(helper, "inval");
		ScanService scans = fixture.railway().scans();
		if (scans.graph(fixture.home().id()) == null || scans.graph(fixture.mine().id()) == null) {
			throw helper.assertionException("fixture graphs were not scanned");
		}

		helper.setBlock(new BlockPos(3, 0, 1), Blocks.STONE);
		BlockPos branchRail = helper.absolutePos(new BlockPos(3, 1, 1));
		helper.getLevel().setBlock(branchRail, Blocks.RAIL.defaultBlockState()
				.setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH), 2);

		if (scans.graph(fixture.home().id()) != null || scans.graph(fixture.mine().id()) != null) {
			throw helper.assertionException("placing a rail branch did not invalidate the old graph");
		}
		helper.succeed();
	}

	/**
	 * Starts a trip on the next tick that has a free active-trip slot. The shared
	 * GameTest server runs many tests at once, so a direct start may see TRIP_LIMIT.
	 */
	private static void startWhenFree(GameTestHelper helper, Fixture fixture, ServerPlayer player,
			Binding binding, TripId[] trip) {
		startWhenFree(helper, fixture, player, binding, trip, new int[]{-1});
	}

	private static void startWhenFree(GameTestHelper helper, Fixture fixture, ServerPlayer player,
			Binding binding, TripId[] trip, int[] startedTick) {
		helper.onEachTick(() -> {
			if (trip[0] != null) {
				return;
			}
			if (fixture.railway().trips().activeCount()
					>= fixture.railway().config().maxActiveTrips() - 1) {
				return;
			}
			TripId started = startIfReady(helper, fixture, player, binding);
			if (started == null) {
				return;
			}
			trip[0] = started;
			startedTick[0] = fixture.railway().server().getTickCount();
			if (player.getVehicle() == null) {
				throw helper.assertionException("passenger was not mounted");
			}
		});
	}

	/** Returns null when the only obstacle is the shared active-trip limit. */
	private static TripId startIfReady(GameTestHelper helper, Fixture fixture, ServerPlayer player,
			Binding binding) {
		TripService.StartResult start = fixture.railway().trips().start(player, binding);
		if (start instanceof TripService.StartResult.Rejected rejected) {
			if (rejected.failure().code() == FailureCode.TRIP_LIMIT
					|| rejected.failure().code() == FailureCode.RECOVERY_PENDING) {
				return null;
			}
			throw helper.assertionException("trip start rejected: " + rejected.failure().code() + " "
					+ (rejected.failure().detail() == null ? "" : rejected.failure().detail())
					+ (rejected.failure().location() == null ? ""
							: " at " + rejected.failure().location().shortString()));
		}
		return ((TripService.StartResult.Started) start).id();
	}

	private static void requireStarted(GameTestHelper helper, TripId[] trip) {
		if (trip[0] == null) {
			throw helper.assertionException("trip did not start");
		}
	}

	private static void assertArrival(GameTestHelper helper, Fixture fixture, ServerPlayer player, BlockLocation exit,
			TripId[] trip) {
		helper.succeedWhen(() -> {
			requireStarted(helper, trip);
			requireTripFinished(helper, fixture.railway(), trip[0]);
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

	/** Builds a disconnected rail line and registers one anchored station on it. */
	private static Station buildIsolatedStation(GameTestHelper helper, Railway railway, String suffix) {
		int z = 4;
		BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
		for (int x = 0; x <= LINE_END; x++) {
			helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
			helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 1, z)), rail, 2);
		}
		helper.setBlock(new BlockPos(HOME_DOCK, 0, z + 2), Blocks.STONE);
		helper.setBlock(new BlockPos(HOME_DOCK, 1, z + 2), Blocks.AIR);
		helper.setBlock(new BlockPos(HOME_DOCK, 2, z + 2), Blocks.AIR);
		String unique = Long.toString(System.nanoTime() & 0xFFFFFFL, 36) + suffix;
		Station station = new Station(new StationId("gtiso" + unique),
				"Game Test Isolated " + unique, RailWorld.dimension(helper.getLevel()),
				absolute(helper, HOME_DOCK, 1, z), absolute(helper, HOME_DOCK, 1, z + 2),
				0.0F, true, railway.state().nextRevision());
		railway.state().addStation(station);
		return station;
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
