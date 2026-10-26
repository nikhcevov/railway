package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Immutable result of route planning. {@code departureSign} is +1 when the cart
 * leaves along increasing dock coordinates and -1 otherwise.
 */
public record RoutePlan(
		StationId from,
		StationId to,
		String dimension,
		RailAxis axis,
		BlockLocation fromDock,
		BlockLocation toDock,
		BlockLocation exit,
		float exitYaw,
		int departureSign,
		double length,
		String componentKey
) {
	public RoutePlan {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(axis, "axis");
		Objects.requireNonNull(fromDock, "fromDock");
		Objects.requireNonNull(toDock, "toDock");
		Objects.requireNonNull(exit, "exit");
		if (departureSign != -1 && departureSign != 1) {
			throw new IllegalArgumentException("departureSign must be -1 or 1");
		}
	}
}
