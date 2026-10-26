package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Destination button registration. Exactly one binding may exist per button block.
 *
 * <p>{@code sourceDock} is the local dock of the destination unit owning the button.
 * Legacy and command-created bindings have no local dock ({@code null}) and depart from
 * the station's main dock.
 */
public record Binding(
		String dimension,
		BlockLocation button,
		String expectedButtonBlockId,
		StationId fromStationId,
		StationId toStationId,
		BlockLocation sourceDock
) {
	public Binding {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(button, "button");
		Objects.requireNonNull(expectedButtonBlockId, "expectedButtonBlockId");
		Objects.requireNonNull(fromStationId, "fromStationId");
		Objects.requireNonNull(toStationId, "toStationId");
	}

	/** Compatibility constructor for bindings without a local source dock. */
	public Binding(String dimension, BlockLocation button, String expectedButtonBlockId,
			StationId fromStationId, StationId toStationId) {
		this(dimension, button, expectedButtonBlockId, fromStationId, toStationId, null);
	}

	public boolean hasSourceDock() {
		return sourceDock != null;
	}

	public Binding withSourceDock(BlockLocation value) {
		return new Binding(dimension, button, expectedButtonBlockId, fromStationId, toStationId, value);
	}
}
