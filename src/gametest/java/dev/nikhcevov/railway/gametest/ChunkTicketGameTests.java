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
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.minecraft.ScanTicketSelection;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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

	@GameTest(maxTicks = 40)
	public void scanTicketsAreBoundedAndReleased(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		ChunkWindow.Key origin = ChunkWindow.Key.of(
				RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0))));
		ChunkWindow.Key neighbour = new ChunkWindow.Key(origin.x() + 1, origin.z());
		// Automatic startup scans may hold unrelated tickets; every assertion is
		// scoped to this test's owner or measured as a delta.
		ChunkTicketManager.ScanStats before = tickets.scanStats();

		// Two frontier chunks cannot fit into a one-ticket scan budget: the update
		// is atomic, so no partial ticket is taken.
		ChunkTicketManager.Update exceeded = tickets.updateScan(level, "gt-scan-budget",
				Set.of(origin, neighbour), 1);
		if (!(exceeded instanceof ChunkTicketManager.Update.BudgetExceeded)) {
			throw helper.assertionException("expected a bounded scan update, got " + exceeded);
		}
		if (tickets.scanTicketCount("gt-scan-budget") != 0) {
			throw helper.assertionException("scan budget rejection acquired a partial ticket");
		}

		// The loaded plot chunk can be held and released without generating terrain.
		ChunkTicketManager.Update applied = tickets.updateScan(level, "gt-scan", Set.of(origin), 128);
		if (!(applied instanceof ChunkTicketManager.Update.Applied ok) || !ok.enabled()
				|| ok.tickets() != 1) {
			throw helper.assertionException("scan ticket was not acquired: " + applied);
		}
		ChunkTicketManager.ScanStats stats = tickets.scanStats();
		if (stats.jobs() != before.jobs() + 1 || stats.tickets() != before.tickets() + 1
				|| stats.acquisitions() < before.acquisitions() + 1) {
			throw helper.assertionException("scan ticket stats are wrong: " + stats
					+ " (before " + before + ")");
		}
		tickets.releaseScan("gt-scan");
		if (tickets.scanTicketCount("gt-scan") != 0 || tickets.scanStats().jobs() != before.jobs()) {
			throw helper.assertionException("released scan ticket leaked");
		}
		tickets.releaseScan("gt-scan");
		helper.succeed();
	}

	/**
	 * A frontier wider than the per-scan cap is processed in bounded subsets: the
	 * selector never returns more than {@link ScanService#MAX_SCAN_TICKETS}, is
	 * deterministic, keeps real (loaded) frontier chunks, and successive subsets
	 * cover every pending chunk. The selected real chunks can then be ticketed.
	 */
	@GameTest(maxTicks = 40)
	public void scanFrontierOverTheCapIsProcessedInBoundedSubsets(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		List<ChunkWindow.Key> real = loadedChunks(helper, level, 2);
		Set<ChunkWindow.Key> loaded = new LinkedHashSet<>(real);
		Set<ChunkWindow.Key> pending = new LinkedHashSet<>(real);
		ChunkWindow.Key base = real.getFirst();
		for (int i = 0; i < 38; i++) {
			// Synthetic stored frontier chunks: they only exercise the selector and
			// are never passed to the ticket manager.
			pending.add(new ChunkWindow.Key(base.x() + 1_000 + i, base.z() + i % 3));
		}
		if (pending.size() != 40) {
			throw helper.assertionException("test frontier is not 40 chunks: " + pending.size());
		}

		ScanTicketSelection.Selection selection = ScanTicketSelection.select(
				Set.of(), pending, loaded, ScanService.MAX_SCAN_TICKETS);
		if (selection.overflow()) {
			throw helper.assertionException("selector reported overflow for a wide frontier");
		}
		if (selection.keys().size() != ScanService.MAX_SCAN_TICKETS) {
			throw helper.assertionException("selector did not fill the scan cap: "
					+ selection.keys().size());
		}
		if (!selection.keys().containsAll(real)) {
			throw helper.assertionException("selector dropped a real loaded frontier chunk");
		}
		if (!selection.keys().equals(ScanTicketSelection.select(
				Set.of(), pending, loaded, ScanService.MAX_SCAN_TICKETS).keys())) {
			throw helper.assertionException("selector is not deterministic");
		}

		// Draining simulation: chunks leave the frontier once the scanner re-probes
		// them, and every pending chunk is reached without exceeding the cap.
		Set<ChunkWindow.Key> remaining = new LinkedHashSet<>(pending);
		Set<ChunkWindow.Key> seen = new LinkedHashSet<>();
		int rounds = 0;
		while (!remaining.isEmpty()) {
			ScanTicketSelection.Selection next = ScanTicketSelection.select(
					Set.of(), remaining, loaded, ScanService.MAX_SCAN_TICKETS);
			if (next.keys().isEmpty() || next.keys().size() > ScanService.MAX_SCAN_TICKETS) {
				throw helper.assertionException("bounded subset is empty or oversized: "
						+ next.keys().size());
			}
			seen.addAll(next.keys());
			remaining.removeAll(next.keys());
			if (++rounds > 5) {
				throw helper.assertionException("frontier subsets made no progress");
			}
		}
		if (!seen.equals(pending)) {
			throw helper.assertionException("not every pending chunk was selected");
		}

		// The bounded subset that contains only existing chunks is accepted, so a
		// wide frontier no longer turns into a BudgetExceeded rejection.
		Set<ChunkWindow.Key> desired = new LinkedHashSet<>(selection.keys());
		desired.retainAll(loaded);
		int budget = Math.max(ScanService.MAX_SCAN_TICKETS, tickets.managedChunkCount() + desired.size());
		ChunkTicketManager.Update update = tickets.updateScan(level, "gt-scan-wide", desired, budget);
		if (!(update instanceof ChunkTicketManager.Update.Applied applied)
				|| applied.tickets() != desired.size()) {
			throw helper.assertionException("bounded scan subset was not applied: " + update);
		}
		tickets.releaseScan("gt-scan-wide");
		if (tickets.scanTicketCount("gt-scan-wide") != 0) {
			throw helper.assertionException("wide-frontier scan ticket leaked");
		}
		helper.succeed();
	}

	/**
	 * Two bounded subsets are acquired in sequence: the second update releases the
	 * first subset and takes the next one, so the job never exceeds its cap and
	 * leaves no stale ticket behind.
	 */
	@GameTest(maxTicks = 40)
	public void scanTicketSubsetsReplaceThePreviousFrontier(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		List<ChunkWindow.Key> real = loadedChunks(helper, level, 2);
		Set<ChunkWindow.Key> loaded = new LinkedHashSet<>(real);
		Set<ChunkWindow.Key> pending = new LinkedHashSet<>(real);
		String owner = "gt-scan-subsets";
		int budget = Math.max(2, tickets.managedChunkCount() + 2);
		ChunkTicketManager.ScanStats before = tickets.scanStats();

		ScanTicketSelection.Selection first = ScanTicketSelection.select(
				tickets.scanTicketKeys(level, owner), pending, loaded, 1);
		if (first.keys().size() != 1) {
			throw helper.assertionException("first subset was not exactly one chunk: " + first);
		}
		ChunkWindow.Key firstKey = first.keys().getFirst();
		ChunkTicketManager.Update acquired = tickets.updateScan(level, owner, Set.of(firstKey), budget);
		if (!(acquired instanceof ChunkTicketManager.Update.Applied applied) || applied.tickets() != 1) {
			throw helper.assertionException("first scan subset was not acquired: " + acquired);
		}

		// The scanner re-probed the first chunk, so it left the frontier and the
		// next subset must ticket the remaining chunk instead.
		pending.remove(firstKey);
		ScanTicketSelection.Selection second = ScanTicketSelection.select(
				tickets.scanTicketKeys(level, owner), pending, loaded, 1);
		if (second.keys().size() != 1 || second.keys().contains(firstKey)) {
			throw helper.assertionException("second subset was not the next chunk: " + second);
		}
		ChunkWindow.Key secondKey = second.keys().getFirst();
		ChunkTicketManager.Update replaced = tickets.updateScan(level, owner, Set.of(secondKey), budget);
		if (!(replaced instanceof ChunkTicketManager.Update.Applied replacedApplied)
				|| replacedApplied.tickets() != 1) {
			throw helper.assertionException("second scan subset was not acquired: " + replaced);
		}
		if (tickets.scanTicketCount(owner) != 1
				|| !tickets.scanTicketKeys(level, owner).equals(Set.of(secondKey))) {
			throw helper.assertionException("previous scan subset was not released: "
					+ tickets.scanTicketKeys(level, owner));
		}
		ChunkTicketManager.ScanStats after = tickets.scanStats();
		if (after.acquisitions() < before.acquisitions() + 2
				|| after.releases() < before.releases() + 1) {
			throw helper.assertionException("subset acquire/release was not counted: " + after
					+ " (before " + before + ")");
		}
		if (tickets.managedChunkCount() > budget) {
			throw helper.assertionException("scan subset exceeded the shared budget");
		}

		tickets.releaseScan(owner);
		if (tickets.scanTicketCount(owner) != 0) {
			throw helper.assertionException("released scan subset leaked");
		}
		helper.succeed();
	}

	/**
	 * A scan update that only refreshes chunks the job already holds does not grow
	 * the shared count, so it is applied even when other owners have filled the
	 * budget. Any set that would acquire a new chunk is still rejected atomically.
	 */
	@GameTest(maxTicks = 40)
	public void refreshOnlyScanUpdateDoesNotConsumeSharedBudget(GameTestHelper helper) {
		ChunkTicketManager tickets = Railway.get().tickets();
		ServerLevel level = helper.getLevel();
		List<ChunkWindow.Key> real = loadedChunks(helper, level, 2);
		ChunkWindow.Key first = real.getFirst();
		ChunkWindow.Key second = real.get(1);
		String owner = "gt-scan-refresh";
		String other = "gt-scan-refresh-b";
		int budget = Math.max(2, tickets.managedChunkCount() + 2);
		ChunkTicketManager.Update acquired = tickets.updateScan(level, owner, Set.of(first), budget);
		if (!(acquired instanceof ChunkTicketManager.Update.Applied applied) || applied.tickets() != 1) {
			throw helper.assertionException("scan chunk was not acquired: " + acquired);
		}
		ChunkTicketManager.Update acquiredOther = tickets.updateScan(level, other, Set.of(second), budget);
		if (!(acquiredOther instanceof ChunkTicketManager.Update.Applied otherApplied)
				|| otherApplied.tickets() != 1) {
			throw helper.assertionException("second scan chunk was not acquired: " + acquiredOther);
		}

		int managed = tickets.managedChunkCount();
		int tight = Math.max(1, managed - 1);
		ChunkTicketManager.Update refresh = tickets.updateScan(level, owner, Set.of(first), tight);
		if (!(refresh instanceof ChunkTicketManager.Update.Applied refreshed)
				|| refreshed.tickets() != 1) {
			throw helper.assertionException("refresh-only scan update was rejected: " + refresh);
		}
		if (tickets.managedChunkCount() != managed) {
			throw helper.assertionException("refresh-only scan update changed the managed count");
		}
		ChunkTicketManager.Update grew = tickets.updateScan(level, owner, Set.of(first, second), tight);
		if (!(grew instanceof ChunkTicketManager.Update.BudgetExceeded)) {
			throw helper.assertionException("oversized scan desired set was not rejected: " + grew);
		}
		if (!tickets.scanTicketKeys(level, owner).equals(Set.of(first))) {
			throw helper.assertionException("rejected update changed the held scan set: "
					+ tickets.scanTicketKeys(level, owner));
		}

		tickets.releaseScan(owner);
		tickets.releaseScan(other);
		helper.succeed();
	}

	/** Deterministic list of already loaded chunks around the test plot. */
	private static List<ChunkWindow.Key> loadedChunks(GameTestHelper helper, ServerLevel level,
			int count) {
		ChunkWindow.Key origin = ChunkWindow.Key.of(
				RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0))));
		Set<ChunkWindow.Key> found = new LinkedHashSet<>();
		for (int radius = 0; radius <= 3 && found.size() < count; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					ChunkWindow.Key key = new ChunkWindow.Key(origin.x() + dx, origin.z() + dz);
					if (level.getChunkSource().hasChunk(key.x(), key.z())) {
						found.add(key);
					}
				}
			}
		}
		List<ChunkWindow.Key> keys = new ArrayList<>(found);
		keys.sort(Comparator.comparingInt(ChunkWindow.Key::x).thenComparingInt(ChunkWindow.Key::z));
		if (keys.size() < count) {
			throw helper.assertionException("only " + keys.size()
					+ " loaded chunk(s) near the test plot; " + count + " needed");
		}
		return keys.subList(0, count);
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}