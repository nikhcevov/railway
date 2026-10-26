package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.Objects;

/**
 * Pure pillar and dock geometry for Railway service signs.
 *
 * <p>Dock resolution scans both directions of a sign's front/back normal axis
 * in a one-block-wide prism, 1-5 blocks horizontally and from
 * {@code signY - 3} through {@code signY} vertically. The first column that
 * contains rails decides the result: exactly one straight horizontal rail is a
 * dock, more than one rail is ambiguous, and one non-straight rail is rejected.
 * Both sides finding a dock is ambiguous; no side finding one is rejected.
 *
 * <p>Pillar validation checks that the sign is mounted on a solid pillar
 * column, that the sign and button heights are independently within
 * {@code dockY + 1 .. dockY + 3}, that a destination unit has exactly one stone
 * button attached to the same column, and that the pillar is 1-3 blocks from a
 * dock that lies on the sign's normal axis.
 *
 * <p>Station exits, station identity, and persistence are deliberately out of
 * scope. Error results are typed for the caller to map to user messages.
 */
public final class PillarGeometry {
	public static final int MIN_DOCK_DISTANCE = 1;
	public static final int MAX_DOCK_DISTANCE = 5;
	public static final int DOCK_SCAN_DEPTH = 3;
	public static final int MIN_PILLAR_DISTANCE = 1;
	public static final int MAX_PILLAR_DISTANCE = 3;
	/** A destination unit only has to sit within this horizontal distance of the station dock. */
	public static final int MAX_DESTINATION_DISTANCE = 4;
	public static final int MIN_UNIT_HEIGHT = 1;
	public static final int MAX_UNIT_HEIGHT = 3;

	private PillarGeometry() {
	}

	/** Resolves the single dock for a sign, or a typed rejection. */
	public static DockResolution resolveDock(BlockLocation sign, HorizontalAxis axis, SignBlockView view) {
		Objects.requireNonNull(sign, "sign");
		Objects.requireNonNull(axis, "axis");
		Objects.requireNonNull(view, "view");

		SideScan positive = scanSide(sign, axis, 1, view);
		SideScan negative = scanSide(sign, axis, -1, view);

		// A rail on both sides is ambiguous even when only one side is a valid
		// dock; the sign cannot identify which track it serves.
		if (positive.hitRail() && negative.hitRail()) {
			return new DockResolution.Rejected(DockError.AMBIGUOUS_BOTH_SIDES);
		}
		SideScan hit;
		int direction;
		if (positive.hitRail()) {
			hit = positive;
			direction = 1;
		} else {
			hit = negative;
			direction = -1;
		}
		if (!hit.hitRail()) {
			return new DockResolution.Rejected(DockError.NO_RAIL);
		}
		if (hit.dock() != null) {
			return new DockResolution.Found(hit.dock(), axis, direction, hit.distance());
		}
		return new DockResolution.Rejected(hit.error());
	}

	/** Validates a destination unit, which must have exactly one stone button. */
	public static PillarValidation validate(PillarUnit unit, BlockLocation dock, SignBlockView view) {
		return validate(unit, dock, view, true);
	}

	/** Validates a station-only anchor, which must not need a button. */
	public static PillarValidation validateAnchor(PillarUnit unit, BlockLocation dock, SignBlockView view) {
		return validate(unit, dock, view, false);
	}

	/**
	 * Validates a destination unit belonging to an already anchored station. The sign
	 * axis does not have to point at the station dock: only the sign/button heights and
	 * the horizontal distance from the pillar column to the dock are checked.
	 */
	public static PillarValidation validateDestination(
			PillarUnit unit,
			BlockLocation stationDock,
			SignBlockView view
	) {
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(stationDock, "stationDock");
		Objects.requireNonNull(view, "view");

		PillarError error = signError(unit, stationDock, view);
		if (error != null) {
			return new PillarValidation.Invalid(error);
		}
		error = buttonError(unit, stationDock, view);
		if (error != null) {
			return new PillarValidation.Invalid(error);
		}

		BlockLocation support = unit.signSupport();
		int dx = support.x() - stationDock.x();
		int dz = support.z() - stationDock.z();
		double horizontal = Math.hypot(dx, dz);
		if (horizontal > MAX_DESTINATION_DISTANCE) {
			return new PillarValidation.Invalid(PillarError.PILLAR_DISTANCE_OUT_OF_RANGE);
		}
		return new PillarValidation.Valid(Math.min(MAX_DESTINATION_DISTANCE, (int) Math.ceil(horizontal)));
	}

	private static PillarValidation validate(
			PillarUnit unit,
			BlockLocation dock,
			SignBlockView view,
			boolean requireButton
	) {
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(dock, "dock");
		Objects.requireNonNull(view, "view");

		PillarError error = signError(unit, dock, view);
		if (error != null) {
			return new PillarValidation.Invalid(error);
		}
		if (requireButton) {
			error = buttonError(unit, dock, view);
			if (error != null) {
				return new PillarValidation.Invalid(error);
			}
		}

		error = dockError(unit, dock);
		if (error != null) {
			return new PillarValidation.Invalid(error);
		}
		return new PillarValidation.Valid(pillarDistance(unit, dock));
	}

	private static PillarError signError(PillarUnit unit, BlockLocation dock, SignBlockView view) {
		BlockLocation sign = unit.sign();
		BlockLocation support = unit.signSupport();
		if (!attached(sign, support, unit.axis())) {
			return PillarError.SIGN_NOT_ATTACHED;
		}
		if (!sample(view, support).solidSupport()) {
			return PillarError.SIGN_SUPPORT_NOT_SOLID;
		}
		if (!withinUnitHeight(sign.y(), dock.y())) {
			return PillarError.SIGN_HEIGHT_OUT_OF_RANGE;
		}
		return null;
	}

	private static PillarError buttonError(PillarUnit unit, BlockLocation dock, SignBlockView view) {
		BlockLocation button = unit.button();
		if (button == null) {
			return PillarError.MISSING_BUTTON;
		}
		if (!sample(view, button).stoneButton()) {
			return PillarError.BUTTON_NOT_STONE;
		}
		if (!withinUnitHeight(button.y(), dock.y())) {
			return PillarError.BUTTON_HEIGHT_OUT_OF_RANGE;
		}

		BlockLocation support = unit.signSupport();
		BlockLocation buttonSupport = new BlockLocation(support.x(), button.y(), support.z());
		int horizontalDistance = Math.abs(button.x() - buttonSupport.x())
				+ Math.abs(button.z() - buttonSupport.z());
		if (horizontalDistance != 1) {
			return PillarError.BUTTON_NOT_ON_PILLAR;
		}
		if (!sample(view, buttonSupport).solidSupport()) {
			return PillarError.BUTTON_SUPPORT_NOT_SOLID;
		}
		return null;
	}

	private static PillarError dockError(PillarUnit unit, BlockLocation dock) {
		BlockLocation support = unit.signSupport();
		int dx = support.x() - dock.x();
		int dz = support.z() - dock.z();
		boolean aligned = switch (unit.axis()) {
			case X -> dz == 0;
			case Z -> dx == 0;
		};
		if (!aligned) {
			return PillarError.DOCK_NOT_ALIGNED;
		}
		int distance = pillarDistance(unit, dock);
		if (distance < MIN_PILLAR_DISTANCE || distance > MAX_PILLAR_DISTANCE) {
			return PillarError.PILLAR_DISTANCE_OUT_OF_RANGE;
		}
		return null;
	}

	private static int pillarDistance(PillarUnit unit, BlockLocation dock) {
		BlockLocation support = unit.signSupport();
		return Math.abs(unit.axis().component(support) - unit.axis().component(dock));
	}

	private static boolean withinUnitHeight(int blockY, int dockY) {
		int height = blockY - dockY;
		return height >= MIN_UNIT_HEIGHT && height <= MAX_UNIT_HEIGHT;
	}

	private static boolean attached(BlockLocation block, BlockLocation support, HorizontalAxis axis) {
		if (block.y() != support.y()) {
			return false;
		}
		int dx = support.x() - block.x();
		int dz = support.z() - block.z();
		return switch (axis) {
			case X -> Math.abs(dx) == 1 && dz == 0;
			case Z -> Math.abs(dz) == 1 && dx == 0;
		};
	}

	private static BlockSample sample(SignBlockView view, BlockLocation position) {
		BlockSample sample = view.sample(position);
		return sample == null ? BlockSample.AIR : sample;
	}

	private static SideScan scanSide(BlockLocation sign, HorizontalAxis axis, int direction, SignBlockView view) {
		for (int distance = MIN_DOCK_DISTANCE; distance <= MAX_DOCK_DISTANCE; distance++) {
			BlockLocation column = axis.offset(sign, distance * direction);
			BlockLocation rail = null;
			boolean nonStraight = false;
			for (int y = sign.y() - DOCK_SCAN_DEPTH; y <= sign.y(); y++) {
				BlockSample sample = sample(view, new BlockLocation(column.x(), y, column.z()));
				if (!sample.rail()) {
					continue;
				}
				if (rail != null) {
					return new SideScan(null, distance, DockError.MULTIPLE_RAILS);
				}
				rail = new BlockLocation(column.x(), y, column.z());
				nonStraight = !sample.straightHorizontalRail();
			}
			if (rail == null) {
				continue;
			}
			if (nonStraight) {
				return new SideScan(null, distance, DockError.RAIL_NOT_STRAIGHT);
			}
			return new SideScan(rail, distance, null);
		}
		return new SideScan(null, 0, DockError.NO_RAIL);
	}

	private record SideScan(BlockLocation dock, int distance, DockError error) {
		boolean hitRail() {
			return error != DockError.NO_RAIL;
		}
	}
}
