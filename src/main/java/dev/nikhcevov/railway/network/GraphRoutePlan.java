package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.List;

/** Immutable graph route, including turnout shapes that must be set before traversal. */
public record GraphRoutePlan(
		BlockLocation fromDock,
		BlockLocation toDock,
		String dimension,
		List<RouteStep> steps,
		List<SwitchRequirement> switches,
		int length,
		String componentKey
) {
	public GraphRoutePlan {
		steps = List.copyOf(steps);
		switches = List.copyOf(switches);
	}
}
