package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Persisted registration for one destination unit (sign plus optional button).
 *
 * <p>{@code editedFront} records which side of the sign was registered. The marker may
 * anchor a station ({@code anchorStationId}), carry a button binding ({@code button}),
 * or both: the anchor and a destination unit match on the same physical pillar.
 * A station-only anchor has a button of {@code null}.
 *
 * <p>{@code stationId} is the station owning this unit. It equals the anchor station for
 * anchor markers and the {@code from} station for destination units of an anchored
 * station. A main-only unit (a station-only sign carrying the {@code m} flag) also has
 * {@code stationId} but neither a button nor an anchor. Legacy markers may leave it
 * {@code null}; the persistence layer infers it where possible. {@code main} marks the
 * unit whose dock/exit is the station's arrival point; at most one marker per station
 * may carry it.
 */
public record SignMarker(
		String dimension,
		BlockLocation sign,
		BlockLocation support,
		boolean editedFront,
		BlockLocation button,
		StationId anchorStationId,
		StationId stationId,
		boolean main
) {
	public SignMarker {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(sign, "sign");
		Objects.requireNonNull(support, "support");
		if (dimension.isBlank()) {
			throw new IllegalArgumentException("sign marker dimension must not be blank");
		}
		if (sign.equals(support)) {
			throw new IllegalArgumentException("sign and support positions must differ");
		}
		if (button != null && (button.equals(sign) || button.equals(support))) {
			throw new IllegalArgumentException("button position must differ from the sign and support");
		}
		if (button == null && anchorStationId == null && stationId == null) {
			throw new IllegalArgumentException(
					"sign marker must belong to a station, anchor a station, carry a button, or a combination");
		}
		if (anchorStationId != null && stationId != null && !anchorStationId.equals(stationId)) {
			throw new IllegalArgumentException(
					"sign marker stationId must match its anchor station: " + anchorStationId);
		}
		if (main && stationId == null && anchorStationId == null) {
			throw new IllegalArgumentException("a main sign marker must belong to a station");
		}
	}

	/** Compatibility constructor: the owning station is the anchor (null for a pure destination). */
	public SignMarker(String dimension, BlockLocation sign, BlockLocation support,
			boolean editedFront, BlockLocation button, StationId anchorStationId) {
		this(dimension, sign, support, editedFront, button, anchorStationId, anchorStationId, false);
	}

	public boolean hasButton() {
		return button != null;
	}

	public boolean isAnchor() {
		return anchorStationId != null;
	}

	public boolean isDestination() {
		return button != null;
	}

	/** Owning station, falling back to the anchor for legacy markers. */
	public StationId ownerStationId() {
		return stationId != null ? stationId : anchorStationId;
	}

	public SignMarker withEditedFront(boolean value) {
		return new SignMarker(dimension, sign, support, value, button, anchorStationId, stationId, main);
	}

	public SignMarker withButton(BlockLocation value) {
		return new SignMarker(dimension, sign, support, editedFront, value, anchorStationId, stationId, main);
	}

	public SignMarker withAnchorStationId(StationId value) {
		return new SignMarker(dimension, sign, support, editedFront, button, value, stationId, main);
	}

	public SignMarker withStationId(StationId value) {
		return new SignMarker(dimension, sign, support, editedFront, button, anchorStationId, value, main);
	}

	public SignMarker withMain(boolean value) {
		return new SignMarker(dimension, sign, support, editedFront, button, anchorStationId, stationId, value);
	}
}
