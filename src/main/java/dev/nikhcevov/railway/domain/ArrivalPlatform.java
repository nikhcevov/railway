package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Arrival dock, exit, and yaw a destination station uses for trips coming from
 * {@code source}. {@code dock} is the block an arriving cart is planned to reach;
 * {@code exit} is the block occupied by the passenger's feet after disembarking.
 */
public record ArrivalPlatform(StationId source, BlockLocation dock, BlockLocation exit, float yaw) {
	public ArrivalPlatform {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(dock, "dock");
		Objects.requireNonNull(exit, "exit");
	}
}
