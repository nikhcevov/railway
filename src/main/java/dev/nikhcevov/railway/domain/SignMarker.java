package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Persisted registration for one destination unit (sign plus optional button).
 *
 * <p>{@code editedFront} records which side of the sign was registered. The marker may
 * anchor a station ({@code anchorStationId}), carry a button binding ({@code button}),
 * or both: the anchor and a destination unit match on the same physical pillar.
 * A station-only anchor has a button of {@code null}.
 */
public record SignMarker(
		String dimension,
		BlockLocation sign,
		BlockLocation support,
		boolean editedFront,
		BlockLocation button,
		StationId anchorStationId
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
		if (button == null && anchorStationId == null) {
			throw new IllegalArgumentException("sign marker must anchor a station, carry a button, or both");
		}
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

	public SignMarker withEditedFront(boolean value) {
		return new SignMarker(dimension, sign, support, value, button, anchorStationId);
	}

	public SignMarker withButton(BlockLocation value) {
		return new SignMarker(dimension, sign, support, editedFront, value, anchorStationId);
	}

	public SignMarker withAnchorStationId(StationId value) {
		return new SignMarker(dimension, sign, support, editedFront, button, value);
	}
}
