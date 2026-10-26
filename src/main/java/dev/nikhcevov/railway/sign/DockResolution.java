package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.Objects;

/** Outcome of resolving a sign's target rail dock. */
public sealed interface DockResolution permits DockResolution.Found, DockResolution.Rejected {

	/**
	 * Exactly one side found a dock.
	 *
	 * @param dock      rail block acting as the dock
	 * @param axis      sign's front/back normal axis
	 * @param direction {@code 1} for the positive axis direction, {@code -1}
	 *                  for the negative axis direction
	 * @param distance  horizontal distance from the sign block
	 */
	record Found(BlockLocation dock, HorizontalAxis axis, int direction, int distance) implements DockResolution {
		public Found {
			Objects.requireNonNull(dock, "dock");
			Objects.requireNonNull(axis, "axis");
			if (direction != 1 && direction != -1) {
				throw new IllegalArgumentException("direction must be 1 or -1");
			}
			if (distance < PillarGeometry.MIN_DOCK_DISTANCE || distance > PillarGeometry.MAX_DOCK_DISTANCE) {
				throw new IllegalArgumentException("distance out of scan range: " + distance);
			}
		}
	}

	/** No usable dock and why. */
	record Rejected(DockError error) implements DockResolution {
		public Rejected {
			Objects.requireNonNull(error, "error");
		}
	}
}
