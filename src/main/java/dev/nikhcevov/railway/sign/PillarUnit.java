package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.Objects;

/**
 * One physical pillar unit: a wall sign attached to a solid pillar column and,
 * for a destination unit, one stone button attached to the same column.
 *
 * <p>{@code signSupport} is the solid block directly behind the sign along the
 * sign's normal axis. The button's support is the block at
 * {@code (signSupport.x, button.y, signSupport.z)}; validation checks that it
 * is solid and that the button is attached to it. A station-only anchor has no
 * button and uses the three-argument constructor.
 */
public record PillarUnit(
		BlockLocation sign,
		BlockLocation signSupport,
		HorizontalAxis axis,
		BlockLocation button
) {
	public PillarUnit {
		Objects.requireNonNull(sign, "sign");
		Objects.requireNonNull(signSupport, "signSupport");
		Objects.requireNonNull(axis, "axis");
	}

	/** Station-only anchor with no button. */
	public PillarUnit(BlockLocation sign, BlockLocation signSupport, HorizontalAxis axis) {
		this(sign, signSupport, axis, null);
	}

	public boolean hasButton() {
		return button != null;
	}
}
