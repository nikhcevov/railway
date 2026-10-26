package dev.nikhcevov.railway.sign;

/** Typed reason a pillar unit failed validation. */
public enum PillarError {
	/** The block behind the sign is not a solid support. */
	SIGN_SUPPORT_NOT_SOLID,
	/** The sign is not attached to the stated support along its normal axis. */
	SIGN_NOT_ATTACHED,
	/** The sign is outside {@code dockY + 1 .. dockY + 3}. */
	SIGN_HEIGHT_OUT_OF_RANGE,
	/** The destination unit has no button. */
	MISSING_BUTTON,
	/** The block at the button position is not a stone button. */
	BUTTON_NOT_STONE,
	/** The button is not attached to the sign's support column. */
	BUTTON_NOT_ON_PILLAR,
	/** The pillar block behind the button is not solid. */
	BUTTON_SUPPORT_NOT_SOLID,
	/** The button is outside {@code dockY + 1 .. dockY + 3}. */
	BUTTON_HEIGHT_OUT_OF_RANGE,
	/** The dock is not on the sign's normal axis. */
	DOCK_NOT_ALIGNED,
	/** The pillar column is not 1-3 blocks from the dock. */
	PILLAR_DISTANCE_OUT_OF_RANGE
}
