package dev.nikhcevov.railway.sign;

/** Typed reason a sign could not resolve a single target rail dock. */
public enum DockError {
	/** Neither normal direction found a rail within the 1-5 block scan. */
	NO_RAIL,
	/** A scanned column contains more than one rail block. */
	MULTIPLE_RAILS,
	/** The first rail found is a curve, slope, crossing, or otherwise not straight. */
	RAIL_NOT_STRAIGHT,
	/** Both normal directions found a valid dock, so the target is ambiguous. */
	AMBIGUOUS_BOTH_SIDES
}
