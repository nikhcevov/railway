package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.RailAxis;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StraightLineScannerTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final long NO_DEADLINE = Long.MAX_VALUE;
	private static final int GENEROUS_LIMIT = 64;

	@Test
	void scansStraightLineWithDockInTheMiddle() {
		FakeRailView view = line(RailAxis.X, 0, 4);
		StraightLineScanner scanner = new StraightLineScanner(DIMENSION, x(2), view, GENEROUS_LIMIT);

		assertTrue(scanner.advance(100, NO_DEADLINE));
		assertTrue(scanner.isFinished());
		assertFalse(scanner.isFailed());
		assertNull(scanner.failure());

		LineScan scan = scanner.result();
		assertEquals(DIMENSION, scan.dimension());
		assertEquals(RailAxis.X, scan.axis());
		assertEquals(List.of(x(0), x(1), x(2), x(3), x(4)), scan.rails(), "rails must be ordered by ascending coordinate");
		assertEquals(2, scan.dockIndex());
		assertEquals(x(2), scan.dock());
		assertEquals(x(0), scan.first());
		assertEquals(x(4), scan.last());
		assertEquals(5, scan.size());
		assertEquals(4.0, scan.length(), 0.0);
		assertEquals(0, scan.poweredCount());
		assertEquals(3, scan.indexOf(x(3)));
		assertEquals(-1, scan.indexOf(x(9)));

		assertEquals(5, scanner.railCount());
		assertEquals(RailAxis.X, scanner.axis());
	}

	@Test
	void componentKeyIsStableForTheSameLineAndDiffersForAnotherLine() {
		LineScan first = completeScan(line(RailAxis.X, 0, 4), x(2), GENEROUS_LIMIT);
		LineScan second = completeScan(line(RailAxis.X, 0, 4), x(2), GENEROUS_LIMIT);
		assertEquals(first.componentKey(), second.componentKey());

		LineScan shifted = completeScan(line(RailAxis.X, 1, 5), x(3), GENEROUS_LIMIT);
		assertNotEquals(first.componentKey(), shifted.componentKey());

		FakeRailView zView = line(RailAxis.Z, 0, 4);
		LineScan zScan = completeScan(zView, z(2), GENEROUS_LIMIT);
		assertNotEquals(first.componentKey(), zScan.componentKey());
	}

	@Test
	void probeBudgetOneKeepsScanningAcrossTicksAndYieldsSameResult() {
		FakeRailView batchedView = line(RailAxis.X, 0, 4);
		StraightLineScanner batched = new StraightLineScanner(DIMENSION, x(2), batchedView, GENEROUS_LIMIT);

		assertFalse(batched.advance(1, NO_DEADLINE), "a single probe cannot finish a five-rail scan");
		assertFalse(batched.isFinished());
		assertEquals(1, batched.railCount(), "only the dock has been probed");
		assertEquals(1, batchedView.probeCount());

		int batches = 1;
		while (!batched.advance(1, NO_DEADLINE)) {
			assertFalse(batched.isFinished(), "advance returning false must mean the scan is still running");
			assertEquals(1, batchedView.probeCount() - batches, "budget of one must probe exactly one block");
			batches++;
			assertTrue(batches < 100, "scanner failed to terminate");
		}

		assertTrue(batched.isFinished());
		assertFalse(batched.isFailed());

		LineScan expected = lineScan(RailAxis.X, 0, 4, 2, 0);
		assertEquals(expected, batched.result(), "batching must not change the resulting scan");

		LineScan atOnce = completeScan(line(RailAxis.X, 0, 4), x(2), GENEROUS_LIMIT);
		assertEquals(atOnce, batched.result());
	}

	@Test
	void slopeAdjacentToDockFailsAtThatBlock() {
		FakeRailView view = line(RailAxis.X, 0, 2)
				.put(x(3), rail(new RailInfo(RailAxis.X, RailKind.NORMAL, true, true)));

		Failure failure = scanFailure(view, x(1), GENEROUS_LIMIT);
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, failure.code());
		assertEquals(x(3), failure.location());
	}

	@Test
	void curvedRailFailsAtThatBlock() {
		FakeRailView view = line(RailAxis.X, 0, 2)
				.put(x(3), rail(new RailInfo(RailAxis.X, RailKind.NORMAL, false, false)));

		Failure failure = scanFailure(view, x(1), GENEROUS_LIMIT);
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, failure.code());
		assertEquals(x(3), failure.location());
	}

	@Test
	void axisChangeFailsAtThatBlock() {
		FakeRailView view = line(RailAxis.X, 0, 2)
				.put(x(3), rail(new RailInfo(RailAxis.Z, RailKind.NORMAL, true, false)));

		Failure failure = scanFailure(view, x(1), GENEROUS_LIMIT);
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, failure.code());
		assertEquals(x(3), failure.location());
	}

	@Test
	void detectorRailFailsAsUnsupportedRail() {
		FakeRailView view = line(RailAxis.X, 0, 2)
				.put(x(3), rail(new RailInfo(RailAxis.X, RailKind.DETECTOR, true, false)));

		Failure failure = scanFailure(view, x(1), GENEROUS_LIMIT);
		assertEquals(FailureCode.UNSUPPORTED_RAIL, failure.code());
		assertEquals(x(3), failure.location());
		assertEquals("DETECTOR", failure.detail());
	}

	@Test
	void activatorRailFailsAsUnsupportedRail() {
		FakeRailView view = line(RailAxis.X, 0, 2)
				.put(x(3), rail(new RailInfo(RailAxis.X, RailKind.ACTIVATOR, true, false)));

		Failure failure = scanFailure(view, x(1), GENEROUS_LIMIT);
		assertEquals(FailureCode.UNSUPPORTED_RAIL, failure.code());
		assertEquals(x(3), failure.location());
		assertEquals("ACTIVATOR", failure.detail());
	}

	@Test
	void unloadedNextBlockIsIncompleteRatherThanNoRail() {
		FakeRailView view = line(RailAxis.X, 0, 2)
				.put(x(3), RailProbe.unloaded());

		Failure failure = scanFailure(view, x(1), GENEROUS_LIMIT);
		assertEquals(FailureCode.SCAN_INCOMPLETE, failure.code());
		assertEquals(x(3), failure.location());
		assertNotEquals(FailureCode.NO_RAIL, failure.code());
	}

	@Test
	void unloadedDockIsIncompleteAtDock() {
		FakeRailView view = line(RailAxis.X, 0, 4)
				.put(x(2), RailProbe.unloaded());

		Failure failure = scanFailure(view, x(2), GENEROUS_LIMIT);
		assertEquals(FailureCode.SCAN_INCOMPLETE, failure.code());
		assertEquals(x(2), failure.location());
	}

	@Test
	void exceedingMaxRailsFailsWithLimitExceeded() {
		FakeRailView view = line(RailAxis.X, 0, 4);

		Failure failure = scanFailure(view, x(2), 3);
		assertEquals(FailureCode.LIMIT_EXCEEDED, failure.code());
		assertEquals("3", failure.detail());
		assertEquals(x(1), failure.location(), "the block that would exceed the limit is reported");
	}

	@Test
	void maxRailsEqualToLineLengthIncludingDockSucceeds() {
		LineScan scan = completeScan(line(RailAxis.X, 0, 4), x(2), 5);
		assertEquals(5, scan.size());
		assertEquals(2, scan.dockIndex());
	}

	@Test
	void missingRailAtDockFailsAsNoRail() {
		FakeRailView view = line(RailAxis.X, 0, 4);

		Failure failure = scanFailure(view, x(10), GENEROUS_LIMIT);
		assertEquals(FailureCode.NO_RAIL, failure.code());
		assertEquals(x(10), failure.location());
	}

	@Test
	void slopeAtDockFailsAsUnsupportedGeometry() {
		FakeRailView view = line(RailAxis.X, 0, 4)
				.put(x(2), rail(new RailInfo(RailAxis.X, RailKind.NORMAL, true, true)));

		Failure failure = scanFailure(view, x(2), GENEROUS_LIMIT);
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, failure.code());
		assertEquals(x(2), failure.location());
	}

	@Test
	void poweredRailsAreCountedIncludingTheDock() {
		FakeRailView view = line(RailAxis.X, 0, 4)
				.put(x(0), rail(new RailInfo(RailAxis.X, RailKind.POWERED, true, false)))
				.put(x(2), rail(new RailInfo(RailAxis.X, RailKind.POWERED, true, false)))
				.put(x(4), rail(new RailInfo(RailAxis.X, RailKind.POWERED, true, false)));

		LineScan scan = completeScan(view, x(2), GENEROUS_LIMIT);
		assertEquals(3, scan.poweredCount());
		assertEquals(List.of(x(0), x(1), x(2), x(3), x(4)), scan.rails());
	}

	@Test
	void scansAlongTheZAxis() {
		FakeRailView view = line(RailAxis.Z, -1, 3);

		LineScan scan = completeScan(view, z(1), GENEROUS_LIMIT);
		assertEquals(RailAxis.Z, scan.axis());
		assertEquals(List.of(z(-1), z(0), z(1), z(2), z(3)), scan.rails());
		assertEquals(2, scan.dockIndex());
		assertEquals(z(1), scan.dock());
		assertEquals(4.0, scan.length(), 0.0);
	}

	private static LineScan completeScan(FakeRailView view, BlockLocation dock, int maxRails) {
		StraightLineScanner scanner = new StraightLineScanner(DIMENSION, dock, view, maxRails);
		assertTrue(scanner.advance(100, NO_DEADLINE), "expected the scan to finish");
		assertTrue(scanner.isFinished());
		assertFalse(scanner.isFailed());
		return scanner.result();
	}

	private static Failure scanFailure(FakeRailView view, BlockLocation dock, int maxRails) {
		StraightLineScanner scanner = new StraightLineScanner(DIMENSION, dock, view, maxRails);
		assertTrue(scanner.advance(100, NO_DEADLINE), "expected the scan to reach a final state");
		assertTrue(scanner.isFinished());
		assertTrue(scanner.isFailed(), "expected the scan to fail");
		assertThrows(IllegalStateException.class, scanner::result, "a failed scan has no result");
		return scanner.failure();
	}

	private static LineScan lineScan(RailAxis axis, int from, int to, int dockCoordinate, int poweredCount) {
		List<BlockLocation> rails = new ArrayList<>();
		for (int coordinate = from; coordinate <= to; coordinate++) {
			rails.add(at(axis, coordinate));
		}
		return new LineScan(DIMENSION, axis, rails, dockCoordinate - from, poweredCount);
	}

	private static BlockLocation at(RailAxis axis, int coordinate) {
		return axis == RailAxis.X ? new BlockLocation(coordinate, 64, 0) : new BlockLocation(0, 64, coordinate);
	}

	private static BlockLocation x(int coordinate) {
		return at(RailAxis.X, coordinate);
	}

	private static BlockLocation z(int coordinate) {
		return at(RailAxis.Z, coordinate);
	}

	private static RailProbe rail(RailInfo info) {
		return RailProbe.rail(info);
	}

	private static FakeRailView line(RailAxis axis, int from, int to) {
		FakeRailView view = new FakeRailView();
		for (int coordinate = from; coordinate <= to; coordinate++) {
			view.put(at(axis, coordinate), rail(new RailInfo(axis, RailKind.NORMAL, true, false)));
		}
		return view;
	}
}
