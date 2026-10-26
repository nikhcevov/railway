package dev.nikhcevov.railway.dispatch;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.network.GraphRail;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.network.RailGraphPlanner;
import dev.nikhcevov.railway.network.RailKind;
import dev.nikhcevov.railway.network.RailShapeType;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteResourcePlannerTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final StationId FROM = new StationId("alpha");
	private static final StationId TO = new StationId("beta");

	@Test
	void straightRouteReservesBothPlatformsAndOneSection() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0), fixed(1, 64, 0), fixed(2, 64, 0),
				fixed(3, 64, 0), fixed(4, 64, 0)
		);
		GraphRoutePlan plan = plan(graph, pos(0, 64, 0), pos(4, 64, 0));
		List<String> resources = RouteResourcePlanner.derive(graph, plan,
				Set.of(pos(0, 64, 0), pos(4, 64, 0)));
		assertEquals(3, resources.size());
		assertTrue(resources.get(0).startsWith("plat|"));
		assertTrue(resources.get(1).startsWith("plat|"));
		assertTrue(resources.get(2).startsWith("sec|"));
		assertFalse(resources.stream().anyMatch(key -> key.startsWith("swz|")));
	}

	@Test
	void switchRouteReservesSwitchZoneAndSections() throws Exception {
		RailGraph graph = turnoutGraph();
		GraphRoutePlan forward = plan(graph, pos(0, 64, 0), pos(3, 64, 3));
		List<String> resources = RouteResourcePlanner.derive(graph, forward,
				Set.of(pos(0, 64, 0), pos(3, 64, 3), pos(5, 64, 0)));
		assertTrue(resources.stream().anyMatch(key -> key.startsWith("swz|")));
		assertEquals(2, resources.stream().filter(key -> key.startsWith("plat|")).count());
		assertTrue(resources.stream().filter(key -> key.startsWith("sec|")).count() >= 2);

		GraphRoutePlan reverse = plan(graph, pos(3, 64, 3), pos(0, 64, 0));
		List<String> reverseResources = RouteResourcePlanner.derive(graph, reverse,
				Set.of(pos(0, 64, 0), pos(3, 64, 3), pos(5, 64, 0)));
		assertEquals(new HashSet<>(resources), new HashSet<>(reverseResources));
	}

	@Test
	void routesSharingTurnoutShareResources() throws Exception {
		RailGraph graph = turnoutGraph();
		Set<BlockLocation> docks = Set.of(pos(0, 64, 0), pos(3, 64, 3), pos(5, 64, 0));
		GraphRoutePlan toSouth = plan(graph, pos(0, 64, 0), pos(3, 64, 3));
		GraphRoutePlan fromEast = plan(graph, pos(5, 64, 0), pos(3, 64, 3));
		Set<String> common = new HashSet<>(RouteResourcePlanner.derive(graph, toSouth, docks));
		common.retainAll(RouteResourcePlanner.derive(graph, fromEast, docks));
		assertTrue(common.stream().anyMatch(key -> key.startsWith("swz|")));
		assertTrue(common.stream().anyMatch(key -> key.startsWith("plat|")));
	}

	@Test
	void opposingStraightTripsConflictAndReleaseCleanly() throws Exception {
		RailGraph graph = graph(
				fixed(0, 64, 0), fixed(1, 64, 0), fixed(2, 64, 0), fixed(3, 64, 0)
		);
		Set<BlockLocation> docks = Set.of(pos(0, 64, 0), pos(3, 64, 0));
		GraphRoutePlan forward = plan(graph, pos(0, 64, 0), pos(3, 64, 0));
		GraphRoutePlan reverse = plan(graph, pos(3, 64, 0), pos(0, 64, 0));
		List<String> forwardResources = RouteResourcePlanner.derive(graph, forward, docks);
		List<String> reverseResources = RouteResourcePlanner.derive(graph, reverse, docks);
		assertEquals(new HashSet<>(forwardResources), new HashSet<>(reverseResources));

		ReservationManager reservations = new ReservationManager();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		assertTrue(reservations.tryAcquire(forwardResources, first));
		assertEquals(forwardResources.getFirst(), forwardResources.stream()
				.filter(resource -> !reservations.isFree(resource)).findFirst().orElseThrow());
		assertFalse(reservations.tryAcquire(reverseResources, second));
		assertTrue(reservations.tryAcquire(reverseResources, first));
		reservations.releaseAll(first);
		assertTrue(reservations.tryAcquire(reverseResources, second));
	}

	@Test
	void disconnectedComponentsHaveDisjointResources() throws Exception {
		RailGraph first = graph(fixed(0, 64, 0), fixed(1, 64, 0), fixed(2, 64, 0));
		RailGraph second = graph(fixed(20, 64, 0), fixed(21, 64, 0), fixed(22, 64, 0));
		List<String> a = RouteResourcePlanner.derive(first, plan(first, pos(0, 64, 0), pos(2, 64, 0)),
				Set.of(pos(0, 64, 0), pos(2, 64, 0)));
		List<String> b = RouteResourcePlanner.derive(second, plan(second, pos(20, 64, 0), pos(22, 64, 0)),
				Set.of(pos(20, 64, 0), pos(22, 64, 0)));
		assertTrue(a.stream().noneMatch(b::contains));
	}

	@Test
	void resourceSetIsDeterministic() throws Exception {
		RailGraph graph = turnoutGraph();
		GraphRoutePlan plan = plan(graph, pos(0, 64, 0), pos(3, 64, 3));
		Set<BlockLocation> docks = Set.of(pos(0, 64, 0), pos(3, 64, 3), pos(5, 64, 0));
		assertEquals(RouteResourcePlanner.derive(graph, plan, docks),
				RouteResourcePlanner.derive(graph, plan, docks));
	}

	private static GraphRoutePlan plan(RailGraph graph, BlockLocation from, BlockLocation to) {
		RailGraphPlanner.Result result = RailGraphPlanner.plan(graph, FROM, TO, from, to, 100);
		assertTrue(result instanceof RailGraphPlanner.Result.Planned, "expected plan but got " + result);
		return ((RailGraphPlanner.Result.Planned) result).plan();
	}

	private static RailGraph graph(GraphRail... rails) throws Exception {
		return RailGraph.build(DIMENSION, 1, List.of(rails));
	}

	private static GraphRail fixed(int x, int y, int z) {
		return GraphRail.fixed(pos(x, y, z), RailKind.NORMAL, RailShapeType.EAST_WEST);
	}

	private static RailGraph turnoutGraph() throws Exception {
		return graph(
				fixed(0, 64, 0), fixed(1, 64, 0), fixed(2, 64, 0),
				GraphRail.turnout(pos(3, 64, 0), RailKind.NORMAL, RailShapeType.EAST_WEST,
						List.of(RailShapeType.EAST_WEST, RailShapeType.SOUTH_WEST, RailShapeType.SOUTH_EAST)),
				GraphRail.fixed(pos(4, 64, 0), RailKind.NORMAL, RailShapeType.EAST_WEST),
				GraphRail.fixed(pos(5, 64, 0), RailKind.NORMAL, RailShapeType.EAST_WEST),
				GraphRail.fixed(pos(3, 64, 1), RailKind.NORMAL, RailShapeType.NORTH_SOUTH),
				GraphRail.fixed(pos(3, 64, 2), RailKind.NORMAL, RailShapeType.NORTH_SOUTH),
				GraphRail.fixed(pos(3, 64, 3), RailKind.NORMAL, RailShapeType.NORTH_SOUTH)
		);
	}

	private static BlockLocation pos(int x, int y, int z) {
		return new BlockLocation(x, y, z);
	}
}
