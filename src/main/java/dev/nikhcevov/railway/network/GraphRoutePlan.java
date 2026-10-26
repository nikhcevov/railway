package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripPlan;

import java.util.List;

/** Immutable graph route, including turnout shapes that must be set before traversal. */
public record GraphRoutePlan(
		StationId from,
		StationId to,
		BlockLocation fromDock,
		BlockLocation toDock,
		String dimension,
		List<RouteStep> steps,
		List<SwitchRequirement> switches,
		int length,
		String componentKey
) implements TripPlan {
	public GraphRoutePlan {
		steps = List.copyOf(steps);
		switches = List.copyOf(switches);
	}
}
