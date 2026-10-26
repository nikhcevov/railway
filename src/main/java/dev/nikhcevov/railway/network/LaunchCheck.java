package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Launch validation scope. Only the currently loaded portion of a planned route
 * can be inspected physically; the rest is trusted from the scan that produced
 * the graph. Those deferred steps are re-checked as the local ticket window
 * brings their chunks in and the cart approaches them.
 */
public final class LaunchCheck {
	private LaunchCheck() {
	}

	public static List<RouteStep> loadedSteps(GraphRoutePlan plan, Predicate<BlockLocation> loaded) {
		List<RouteStep> result = new ArrayList<>(plan.steps().size());
		for (RouteStep step : plan.steps()) {
			if (loaded.test(step.pos())) {
				result.add(step);
			}
		}
		return result;
	}

	public static List<SwitchRequirement> loadedSwitches(GraphRoutePlan plan, Predicate<BlockLocation> loaded) {
		List<SwitchRequirement> result = new ArrayList<>(plan.switches().size());
		for (SwitchRequirement requirement : plan.switches()) {
			if (loaded.test(requirement.pos())) {
				result.add(requirement);
			}
		}
		return result;
	}
}
