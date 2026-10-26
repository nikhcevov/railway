package dev.nikhcevov.railway.sign;

import java.util.Objects;

/** Outcome of validating one destination pillar unit against its dock. */
public sealed interface PillarValidation permits PillarValidation.Valid, PillarValidation.Invalid {

	/**
	 * Pillar unit is valid.
	 *
	 * @param pillarDistance horizontal distance from the pillar column to the reference
	 *                       dock; anchors are 1-3, destination units are at most 4
	 */
	record Valid(int pillarDistance) implements PillarValidation {
		public Valid {
			if (pillarDistance < 0 || pillarDistance > PillarGeometry.MAX_DESTINATION_DISTANCE) {
				throw new IllegalArgumentException("pillar distance out of range: " + pillarDistance);
			}
		}
	}

	/** Pillar unit is invalid and why. */
	record Invalid(PillarError error) implements PillarValidation {
		public Invalid {
			Objects.requireNonNull(error, "error");
		}
	}
}
