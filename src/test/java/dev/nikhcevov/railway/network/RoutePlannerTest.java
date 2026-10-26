package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.RailAxis;
import dev.nikhcevov.railway.domain.RoutePlan;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final int GENEROUS_LIMIT = 64;

	@Test
	void plansForwardRouteAroundTheSharedLine() {
		LineScan line = line(OVERWORLD, 0, 6, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", OVERWORLD, 4, new BlockLocation(4, 65, 1), 90.0f, true);

		RoutePlan plan = planned(from, to, line, line, GENEROUS_LIMIT);

		assertEquals(new StationId("alpha"), plan.from());
		assertEquals(new StationId("beta"), plan.to());
		assertEquals(OVERWORLD, plan.dimension());
		assertEquals(RailAxis.X, plan.axis());
		assertEquals(location(0), plan.fromDock());
		assertEquals(location(4), plan.toDock());
		assertEquals(new BlockLocation(4, 65, 1), plan.exit(), "arrival exit comes from the destination station");
		assertEquals(90.0f, plan.exitYaw(), 0.0f, "arrival yaw comes from the destination station");
		assertEquals(1, plan.departureSign());
		assertEquals(4.0, plan.length(), 0.0);
		assertEquals(line.componentKey(), plan.componentKey());
	}

	@Test
	void plansReverseRouteWithNegativeDepartureSign() {
		LineScan line = line(OVERWORLD, 0, 6, 3);
		Station from = station("beta", OVERWORLD, 4, null, 0.0f, true);
		Station to = station("alpha", OVERWORLD, 0, new BlockLocation(0, 65, 0), -90.0f, true);

		RoutePlan plan = planned(from, to, line, line, GENEROUS_LIMIT);

		assertEquals(-1, plan.departureSign(), "travel toward decreasing coordinates is negative");
		assertEquals(4.0, plan.length(), 0.0);
		assertEquals(location(4), plan.fromDock());
		assertEquals(location(0), plan.toDock());
		assertEquals(-90.0f, plan.exitYaw(), 0.0f);
		assertEquals(line.componentKey(), plan.componentKey());
	}

	@Test
	void rejectsDisabledDepartureStation() {
		LineScan line = line(OVERWORLD, 0, 6, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, false);
		Station to = station("beta", OVERWORLD, 4, new BlockLocation(4, 65, 0), 0.0f, true);

		Failure failure = rejected(from, to, line, line, GENEROUS_LIMIT);
		assertEquals(FailureCode.STATION_DISABLED, failure.code());
		assertEquals("alpha", failure.detail());
	}

	@Test
	void rejectsDisabledDestinationStation() {
		LineScan line = line(OVERWORLD, 0, 6, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", OVERWORLD, 4, new BlockLocation(4, 65, 0), 0.0f, false);

		Failure failure = rejected(from, to, line, line, GENEROUS_LIMIT);
		assertEquals(FailureCode.STATION_DISABLED, failure.code());
		assertEquals("beta", failure.detail());
	}

	@Test
	void rejectsStationsInDifferentDimensions() {
		LineScan overworldLine = line(OVERWORLD, 0, 6, 0);
		LineScan netherLine = line(NETHER, 0, 6, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", NETHER, 4, new BlockLocation(4, 65, 0), 0.0f, true);

		Failure failure = rejected(from, to, overworldLine, netherLine, GENEROUS_LIMIT);
		assertEquals(FailureCode.DIMENSION_MISMATCH, failure.code());
	}

	@Test
	void rejectsStationsOnDifferentLines() {
		LineScan firstLine = line(OVERWORLD, 0, 6, 0);
		LineScan secondLine = line(OVERWORLD, 10, 16, 12);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", OVERWORLD, 12, new BlockLocation(12, 65, 0), 0.0f, true);

		Failure failure = rejected(from, to, firstLine, secondLine, GENEROUS_LIMIT);
		assertEquals(FailureCode.NO_PATH, failure.code());
	}

	@Test
	void rejectsDestinationWithFewerThanTwoArrivalRails() {
		LineScan line = line(OVERWORLD, 0, 5, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);

		Station oneRailLeft = station("beta", OVERWORLD, 1, new BlockLocation(1, 65, 0), 0.0f, true);
		Failure one = rejected(from, oneRailLeft, line, line, GENEROUS_LIMIT);
		assertEquals(FailureCode.TOO_SHORT, one.code());
		assertEquals("1", one.detail());
		assertEquals(location(1), one.location());

		Station twoRailsLeft = station("delta", OVERWORLD, 2, new BlockLocation(2, 65, 0), 0.0f, true);
		RoutePlan plan = planned(from, twoRailsLeft, line, line, GENEROUS_LIMIT);
		assertEquals(2.0, plan.length(), 0.0);
	}

	@Test
	void rejectsRouteLongerThanConfiguredLimit() {
		LineScan line = line(OVERWORLD, 0, 10, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", OVERWORLD, 8, new BlockLocation(8, 65, 0), 0.0f, true);

		Failure failure = rejected(from, to, line, line, 5);
		assertEquals(FailureCode.LIMIT_EXCEEDED, failure.code());
		assertEquals("5", failure.detail());
		assertEquals(location(8), failure.location());
	}

	@Test
	void allowsRouteExactlyAtConfiguredLimit() {
		LineScan line = line(OVERWORLD, 0, 10, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", OVERWORLD, 8, new BlockLocation(8, 65, 0), 0.0f, true);

		RoutePlan plan = planned(from, to, line, line, 8);
		assertEquals(8.0, plan.length(), 0.0);
		assertEquals(1, plan.departureSign());
	}

	@Test
	void rejectsDestinationWithoutExit() {
		LineScan line = line(OVERWORLD, 0, 6, 0);
		Station from = station("alpha", OVERWORLD, 0, null, 0.0f, true);
		Station to = station("beta", OVERWORLD, 4, null, 0.0f, true);

		Failure failure = rejected(from, to, line, line, GENEROUS_LIMIT);
		assertEquals(FailureCode.STATION_INCOMPLETE, failure.code());
		assertEquals("beta", failure.detail());
	}

	private static RoutePlan planned(Station from, Station to, LineScan fromLine, LineScan toLine, int maxRouteLength) {
		RoutePlanner.Result result = RoutePlanner.plan(from, to, fromLine, toLine, maxRouteLength);
		assertTrue(result instanceof RoutePlanner.Result.Planned, "expected a planned route but got " + result);
		return ((RoutePlanner.Result.Planned) result).plan();
	}

	private static Failure rejected(Station from, Station to, LineScan fromLine, LineScan toLine, int maxRouteLength) {
		RoutePlanner.Result result = RoutePlanner.plan(from, to, fromLine, toLine, maxRouteLength);
		assertTrue(result instanceof RoutePlanner.Result.Rejected, "expected a rejection but got " + result);
		return ((RoutePlanner.Result.Rejected) result).failure();
	}

	private static BlockLocation location(int x) {
		return new BlockLocation(x, 64, 0);
	}

	private static Station station(
			String id,
			String dimension,
			int dockX,
			BlockLocation exit,
			float exitYaw,
			boolean enabled
	) {
		return new Station(new StationId(id), "Station " + id, dimension, location(dockX), exit, exitYaw, enabled, 1L);
	}

	private static LineScan line(String dimension, int from, int to, int dockX) {
		List<BlockLocation> rails = new ArrayList<>();
		for (int x = from; x <= to; x++) {
			rails.add(location(x));
		}
		return new LineScan(dimension, RailAxis.X, rails, dockX - from, 0);
	}
}
