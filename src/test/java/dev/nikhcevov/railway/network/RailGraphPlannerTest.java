package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.StationId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailGraphPlannerTest {
	private static final String DIMENSION = "minecraft:overworld";

	@Test
	void plansStraightLineInBothDirections() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST),
				fixed(2, 64, 0, RailShapeType.EAST_WEST),
				fixed(3, 64, 0, RailShapeType.EAST_WEST),
				fixed(4, 64, 0, RailShapeType.EAST_WEST)
		);

		GraphRoutePlan forward = planned(graph, pos(0, 64, 0), pos(4, 64, 0), 100);
		assertEquals(4, forward.length());
		assertEquals(5, forward.steps().size());
		assertTrue(forward.switches().isEmpty());
		assertEquals(pos(0, 64, 0), forward.steps().getFirst().pos());
		assertEquals(pos(4, 64, 0), forward.steps().getLast().pos());

		GraphRoutePlan reverse = planned(graph, pos(4, 64, 0), pos(0, 64, 0), 100);
		assertEquals(4, reverse.length());
		assertEquals(5, reverse.steps().size());
	}

	@Test
	void rejectsDockWithLessThanTwoAlignmentRails() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST)
		);
		Failure failure = rejected(graph, pos(0, 64, 0), pos(1, 64, 0), 100);
		assertEquals(FailureCode.TOO_SHORT, failure.code());
	}

	@Test
	void rejectsRouteOverLengthLimit() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST),
				fixed(2, 64, 0, RailShapeType.EAST_WEST),
				fixed(3, 64, 0, RailShapeType.EAST_WEST)
		);
		Failure failure = rejected(graph, pos(0, 64, 0), pos(3, 64, 0), 2);
		assertEquals(FailureCode.LIMIT_EXCEEDED, failure.code());
		assertEquals("2", failure.detail());
	}

	@Test
	void rejectsDockThatIsNotInGraph() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST)
		);
		Failure failure = rejected(graph, pos(9, 64, 0), pos(1, 64, 0), 100);
		assertEquals(FailureCode.DOCK_NOT_ON_LINE, failure.code());
	}

	@Test
	void rejectsEmptyGraph() {
		GraphBuildException exception = assertThrows(GraphBuildException.class, () -> graph());
		assertEquals(FailureCode.INTERNAL, exception.failure().code());
	}

	@Test
	void rejectsMissingPath() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST),
				fixed(10, 64, 0, RailShapeType.EAST_WEST),
				fixed(11, 64, 0, RailShapeType.EAST_WEST)
		);
		Failure failure = rejected(graph, pos(1, 64, 0), pos(10, 64, 0), 100);
		assertEquals(FailureCode.NO_PATH, failure.code());
	}

	@Test
	void routesThroughCurveAndStraightAlignment() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.SOUTH_WEST),
				fixed(1, 64, 1, RailShapeType.NORTH_SOUTH),
				fixed(1, 64, 2, RailShapeType.NORTH_SOUTH),
				fixed(1, 64, 3, RailShapeType.NORTH_SOUTH)
		);
		GraphRoutePlan plan = planned(graph, pos(0, 64, 0), pos(1, 64, 3), 100);
		assertEquals(5, plan.steps().size());
		assertEquals(RailShapeType.SOUTH_WEST, plan.steps().get(1).shape());
		assertEquals(pos(1, 64, 3), plan.steps().getLast().pos());
	}

	@Test
	void routesUpSlopeWithHeightValidation() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST),
				fixed(2, 64, 0, RailShapeType.ASCENDING_EAST),
				fixed(3, 65, 0, RailShapeType.EAST_WEST),
				fixed(4, 65, 0, RailShapeType.EAST_WEST),
				fixed(5, 65, 0, RailShapeType.EAST_WEST)
		);
		GraphRoutePlan plan = planned(graph, pos(0, 64, 0), pos(5, 65, 0), 100);
		assertEquals(5, plan.length());
		assertEquals(RailShapeType.ASCENDING_EAST, plan.steps().get(2).shape());
		assertEquals(65, plan.steps().get(3).pos().y());
	}

	@Test
	void turnoutSelectsBranchAndCarriesRequiredShape() throws Exception {
		RailGraph graph = turnoutGraph();
		GraphRoutePlan toSouth = planned(graph, pos(0, 64, 0), pos(3, 64, 3), 100);
		assertEquals(6, toSouth.length());
		assertEquals(1, toSouth.switches().size());
		assertEquals(pos(3, 64, 0), toSouth.switches().getFirst().pos());
		assertEquals(RailShapeType.SOUTH_WEST, toSouth.switches().getFirst().requiredShape());

		GraphRoutePlan toEast = planned(graph, pos(0, 64, 0), pos(5, 64, 0), 100);
		assertEquals(5, toEast.length());
		assertEquals(1, toEast.switches().size());
		assertEquals(RailShapeType.EAST_WEST, toEast.switches().getFirst().requiredShape());

		GraphRoutePlan backWest = planned(graph, pos(3, 64, 3), pos(0, 64, 0), 100);
		assertEquals(6, backWest.length());
		assertEquals(RailShapeType.SOUTH_WEST, backWest.switches().getFirst().requiredShape());
	}

	@Test
	void rejectsTurnoutAsDock() throws Exception {
		RailGraph graph = turnoutGraph();
		Failure failure = rejected(graph, pos(3, 64, 0), pos(0, 64, 0), 100);
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, failure.code());
	}

	@Test
	void rejectsAmbiguousAdjacency() {
		GraphBuildException exception = assertThrows(GraphBuildException.class, () -> graph(
				fixed(1, 64, 0, RailShapeType.EAST_WEST),
				fixed(2, 64, 0, RailShapeType.EAST_WEST),
				fixed(2, 63, 0, RailShapeType.ASCENDING_WEST)
		));
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, exception.failure().code());
	}

	@Test
	void rejectsDetectorRail() {
		GraphBuildException exception = assertThrows(GraphBuildException.class, () -> graph(
				new GraphRail(pos(0, 64, 0), RailKind.DETECTOR, RailShapeType.EAST_WEST,
						List.of(RailShapeType.EAST_WEST))
		));
		assertEquals(FailureCode.UNSUPPORTED_RAIL, exception.failure().code());
	}

	@Test
	void rejectsTurnoutOnSlope() {
		GraphBuildException exception = assertThrows(GraphBuildException.class, () -> graph(
				GraphRail.turnout(pos(2, 64, 0), RailKind.NORMAL, RailShapeType.ASCENDING_EAST,
						List.of(RailShapeType.ASCENDING_EAST, RailShapeType.ASCENDING_NORTH))
		));
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, exception.failure().code());
	}

	@Test
	void rejectsFourWayAlternatives() {
		GraphBuildException exception = assertThrows(GraphBuildException.class, () -> graph(
				GraphRail.turnout(pos(2, 64, 0), RailKind.NORMAL, RailShapeType.EAST_WEST,
						List.of(RailShapeType.EAST_WEST, RailShapeType.NORTH_SOUTH,
								RailShapeType.SOUTH_EAST, RailShapeType.NORTH_WEST))
		));
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, exception.failure().code());
	}

	@Test
	void rejectsFourWayCrossingEvenWithTwoThroughShapes() {
		GraphBuildException exception = assertThrows(GraphBuildException.class, () -> graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				GraphRail.turnout(pos(1, 64, 0), RailKind.NORMAL, RailShapeType.EAST_WEST,
						List.of(RailShapeType.EAST_WEST, RailShapeType.NORTH_SOUTH)),
				fixed(2, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, -1, RailShapeType.NORTH_SOUTH),
				fixed(1, 64, 1, RailShapeType.NORTH_SOUTH)
		));
		assertEquals(FailureCode.UNSUPPORTED_GEOMETRY, exception.failure().code());
	}

	@Test
	void repeatedPlanningIsDeterministic() throws Exception {
		RailGraph graph = turnoutGraph();
		GraphRoutePlan first = planned(graph, pos(0, 64, 0), pos(3, 64, 3), 100);
		GraphRoutePlan second = planned(graph, pos(0, 64, 0), pos(3, 64, 3), 100);
		assertEquals(first.steps(), second.steps());
		assertEquals(first.switches(), second.switches());
	}

	@Test
	void exposesStableComponentKeyAndSwitchPositions() throws Exception {
		RailGraph graph = turnoutGraph();
		assertTrue(graph.componentKey().startsWith(DIMENSION));
		assertTrue(graph.switches().contains(pos(3, 64, 0)));
		assertFalse(graph.rails().isEmpty());
		assertNotNull(graph.rail(pos(3, 64, 0)));
	}

	private static RailGraph turnoutGraph() throws GraphBuildException {
		return graph(
				fixed(0, 64, 0, RailShapeType.EAST_WEST),
				fixed(1, 64, 0, RailShapeType.EAST_WEST),
				fixed(2, 64, 0, RailShapeType.EAST_WEST),
				GraphRail.turnout(pos(3, 64, 0), RailKind.NORMAL, RailShapeType.EAST_WEST,
						List.of(RailShapeType.EAST_WEST, RailShapeType.SOUTH_WEST, RailShapeType.SOUTH_EAST)),
				fixed(4, 64, 0, RailShapeType.EAST_WEST),
				fixed(5, 64, 0, RailShapeType.EAST_WEST),
				fixed(3, 64, 1, RailShapeType.NORTH_SOUTH),
				fixed(3, 64, 2, RailShapeType.NORTH_SOUTH),
				fixed(3, 64, 3, RailShapeType.NORTH_SOUTH)
		);
	}

	private static RailGraph graph(GraphRail... rails) throws GraphBuildException {
		return RailGraph.build(DIMENSION, 1, List.of(rails));
	}

	private static GraphRail fixed(int x, int y, int z, RailShapeType shape) {
		return GraphRail.fixed(pos(x, y, z), RailKind.NORMAL, shape);
	}

	private static BlockLocation pos(int x, int y, int z) {
		return new BlockLocation(x, y, z);
	}

	private static GraphRoutePlan planned(RailGraph graph, BlockLocation from, BlockLocation to, int max)
			throws Exception {
		RailGraphPlanner.Result result = RailGraphPlanner.plan(graph, new StationId("alpha"), new StationId("beta"), from, to, max);
		assertTrue(result instanceof RailGraphPlanner.Result.Planned, "expected plan but got " + result);
		return ((RailGraphPlanner.Result.Planned) result).plan();
	}

	private static Failure rejected(RailGraph graph, BlockLocation from, BlockLocation to, int max) {
		RailGraphPlanner.Result result = RailGraphPlanner.plan(graph, new StationId("alpha"), new StationId("beta"), from, to, max);
		assertTrue(result instanceof RailGraphPlanner.Result.Rejected, "expected rejection but got " + result);
		return ((RailGraphPlanner.Result.Rejected) result).failure();
	}
}
