package dev.nikhcevov.railway.domain;

import java.util.Objects;

/** Destination button registration. Exactly one binding may exist per button block. */
public record Binding(
		String dimension,
		BlockLocation button,
		String expectedButtonBlockId,
		StationId fromStationId,
		StationId toStationId
) {
	public Binding {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(button, "button");
		Objects.requireNonNull(expectedButtonBlockId, "expectedButtonBlockId");
		Objects.requireNonNull(fromStationId, "fromStationId");
		Objects.requireNonNull(toStationId, "toStationId");
	}
}
