package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.StationId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchCheckTest {
	@Test
	void loadedStepsExcludeUnloadedTail() {
		GraphRoutePlan plan = plan(40, List.of());

		List<RouteStep> loaded = LaunchCheck.loadedSteps(plan, pos -> pos.x() < 16);

		assertEquals(16, loaded.size());
		assertEquals(0, loaded.getFirst().pos().x());
		assertEquals(15, loaded.getLast().pos().x());
		assertEquals(24, plan.steps().size() - loaded.size());
	}

	@Test
	void unloadedTailDoesNotInvalidateLaunchScope() {
		GraphRoutePlan plan = plan(40, List.of());

		assertTrue(LaunchCheck.loadedSteps(plan, pos -> false).isEmpty());
		assertEquals(40, plan.steps().size());
	}

	@Test
	void loadedSwitchesExcludeUnloadedOnes() {
		GraphRoutePlan plan = plan(40, List.of(
				new SwitchRequirement(new BlockLocation(2, 64, 0), RailShapeType.NORTH_EAST),
				new SwitchRequirement(new BlockLocation(30, 64, 0), RailShapeType.SOUTH_WEST)));

		List<SwitchRequirement> loaded = LaunchCheck.loadedSwitches(plan, pos -> pos.x() < 16);

		assertEquals(1, loaded.size());
		assertEquals(2, loaded.getFirst().pos().x());
	}

	private static GraphRoutePlan plan(int steps, List<SwitchRequirement> switches) {
		List<RouteStep> route = new ArrayList<>(steps);
		for (int x = 0; x < steps; x++) {
			route.add(new RouteStep(new BlockLocation(x, 64, 0), RailShapeType.EAST_WEST,
					RailPort.WEST, RailPort.EAST));
		}
		return new GraphRoutePlan(new StationId("from"), new StationId("to"),
				new BlockLocation(0, 64, 0), new BlockLocation(steps - 1, 64, 0),
				"minecraft:overworld", route, switches, steps, "test");
	}
}
