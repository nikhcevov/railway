package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.StationId;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphScannerTest {
	private static final String DIMENSION = "minecraft:overworld";

	@Test
	void scansStraightLineAndPlans() {
		FakeView view = new FakeView();
		for (int x = 0; x <= 4; x++) {
			view.rail(x, 64, 0, RailShapeType.EAST_WEST);
		}
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		assertEquals(5, scanner.railCount());
		GraphRoutePlan plan = planned(scanner.graph(), pos(0, 64, 0), pos(4, 64, 0));
		assertEquals(4, plan.length());
	}

	@Test
	void tickBudgetProducesSameGraph() {
		FakeView view = new FakeView();
		for (int x = 0; x <= 5; x++) {
			view.rail(x, 64, 0, RailShapeType.EAST_WEST);
		}
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		int calls = 0;
		while (!scanner.advance(1, Long.MAX_VALUE)) {
			calls++;
			if (calls > 100) {
				throw new AssertionError("scan did not finish");
			}
		}
		assertFalse(scanner.isFailed());
		GraphRoutePlan plan = planned(scanner.graph(), pos(0, 64, 0), pos(5, 64, 0));
		assertEquals(5, plan.length());
	}

	@Test
	void detectsTurnoutAlternativesAndPlansBranch() {
		FakeView view = new FakeView();
		for (int x = 0; x <= 2; x++) {
			view.rail(x, 64, 0, RailShapeType.EAST_WEST);
		}
		view.rail(3, 64, 0, RailShapeType.EAST_WEST);
		view.rail(4, 64, 0, RailShapeType.EAST_WEST);
		view.rail(5, 64, 0, RailShapeType.EAST_WEST);
		for (int z = 1; z <= 3; z++) {
			view.rail(3, 64, z, RailShapeType.NORTH_SOUTH);
		}
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		GraphRail junction = scanner.graph().rail(pos(3, 64, 0));
		assertTrue(junction.isSwitch());
		assertTrue(junction.alternatives().contains(RailShapeType.EAST_WEST));
		assertTrue(junction.alternatives().contains(RailShapeType.SOUTH_WEST));
		assertTrue(junction.alternatives().contains(RailShapeType.SOUTH_EAST));

		GraphRoutePlan toSouth = planned(scanner.graph(), pos(0, 64, 0), pos(3, 64, 3));
		assertEquals(RailShapeType.SOUTH_WEST, toSouth.switches().getFirst().requiredShape());
		GraphRoutePlan toEast = planned(scanner.graph(), pos(0, 64, 0), pos(5, 64, 0));
		assertEquals(RailShapeType.EAST_WEST, toEast.switches().getFirst().requiredShape());
	}

	@Test
	void scansCurveAndSlope() {
		FakeView view = new FakeView();
		view.rail(0, 64, 0, RailShapeType.EAST_WEST);
		view.rail(1, 64, 0, RailShapeType.EAST_WEST);
		view.rail(2, 64, 0, RailShapeType.ASCENDING_EAST);
		view.rail(3, 65, 0, RailShapeType.EAST_WEST);
		view.rail(4, 65, 0, RailShapeType.EAST_WEST);
		view.rail(5, 65, 0, RailShapeType.EAST_WEST);
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		GraphRoutePlan plan = planned(scanner.graph(), pos(0, 64, 0), pos(5, 65, 0));
		assertEquals(5, plan.length());
		assertEquals(RailShapeType.ASCENDING_EAST, plan.steps().get(2).shape());
	}

	@Test
	void reportsIncompleteScanAtUnloadedNeighbour() {
		FakeView view = new FakeView();
		view.rail(0, 64, 0, RailShapeType.EAST_WEST);
		view.unloaded(1, 64, 0);
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		assertTrue(scanner.isFailed());
		assertEquals(FailureCode.SCAN_INCOMPLETE, scanner.failure().code());
	}

	@Test
	void rejectsOverScanLimit() {
		FakeView view = new FakeView();
		for (int x = 0; x <= 4; x++) {
			view.rail(x, 64, 0, RailShapeType.EAST_WEST);
		}
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 3);
		assertTrue(scan(scanner));
		assertEquals(FailureCode.LIMIT_EXCEEDED, scanner.failure().code());
	}

	@Test
	void rejectsDetectorRail() {
		FakeView view = new FakeView();
		view.rail(0, 64, 0, RailShapeType.EAST_WEST);
		view.rail(1, 64, 0, RailShapeType.EAST_WEST, RailKind.DETECTOR);
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		assertEquals(FailureCode.UNSUPPORTED_RAIL, scanner.failure().code());
	}

	@Test
	void rejectsFourWayCrossing() {
		FakeView view = new FakeView();
		view.rail(1, 64, 0, RailShapeType.EAST_WEST);
		view.rail(0, 64, 0, RailShapeType.EAST_WEST);
		view.rail(2, 64, 0, RailShapeType.EAST_WEST);
		view.rail(1, 64, -1, RailShapeType.NORTH_SOUTH);
		view.rail(1, 64, 1, RailShapeType.NORTH_SOUTH);
		GraphScanner scanner = scanner(view, pos(1, 64, 0), 50);
		assertTrue(scan(scanner));
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, scanner.failure().code());
	}

	@Test
	void rejectsAmbiguousNeighbourHeights() {
		FakeView view = new FakeView();
		view.rail(0, 64, 0, RailShapeType.EAST_WEST);
		view.rail(1, 64, 0, RailShapeType.EAST_WEST);
		view.rail(1, 63, 0, RailShapeType.ASCENDING_WEST);
		GraphScanner scanner = scanner(view, pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, scanner.failure().code());
	}

	@Test
	void missingStartRailFails() {
		GraphScanner scanner = scanner(new FakeView(), pos(0, 64, 0), 50);
		assertTrue(scan(scanner));
		assertEquals(FailureCode.NO_RAIL, scanner.failure().code());
	}

	private static GraphScanner scanner(FakeView view, BlockLocation start, int maxRails) {
		return new GraphScanner(DIMENSION, start, view, maxRails, 1);
	}

	private static boolean scan(GraphScanner scanner) {
		for (int i = 0; i < 100 && !scanner.isFinished(); i++) {
			scanner.advance(1000, Long.MAX_VALUE);
		}
		return scanner.isFinished();
	}

	private static GraphRoutePlan planned(RailGraph graph, BlockLocation from, BlockLocation to) {
		RailGraphPlanner.Result result = RailGraphPlanner.plan(graph, new StationId("alpha"), new StationId("beta"), from, to, 100);
		assertTrue(result instanceof RailGraphPlanner.Result.Planned, "expected plan but got " + result);
		return ((RailGraphPlanner.Result.Planned) result).plan();
	}

	private static BlockLocation pos(int x, int y, int z) {
		return new BlockLocation(x, y, z);
	}

	private static final class FakeView implements GraphRailView {
		private final Map<BlockLocation, Probe.Rail> rails = new HashMap<>();
		private final Set<BlockLocation> unloaded = new HashSet<>();

		void rail(int x, int y, int z, RailShapeType shape) {
			rail(x, y, z, shape, RailKind.NORMAL);
		}

		void rail(int x, int y, int z, RailShapeType shape, RailKind kind) {
			rails.put(pos(x, y, z), new Probe.Rail(shape, kind));
		}

		void unloaded(int x, int y, int z) {
			unloaded.add(pos(x, y, z));
		}

		@Override
		public Probe graphProbe(BlockLocation pos) {
			if (unloaded.contains(pos)) {
				return new Probe.Unloaded();
			}
			Probe.Rail rail = rails.get(pos);
			return rail == null ? new Probe.Empty() : rail;
		}
	}
}
