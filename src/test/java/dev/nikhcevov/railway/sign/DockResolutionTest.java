package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DockResolutionTest {
	private static final BlockLocation SIGN = new BlockLocation(0, 67, 0);

	private final Map<BlockLocation, BlockSample> blocks = new HashMap<>();
	private final SignBlockView view = position -> blocks.getOrDefault(position, BlockSample.AIR);

	@Test
	void findsDockOnPositiveNormalSide() {
		put(2, 64, 0, BlockSample.straightRail());

		DockResolution.Found found = found(SIGN, HorizontalAxis.X);

		assertEquals(new BlockLocation(2, 64, 0), found.dock());
		assertEquals(HorizontalAxis.X, found.axis());
		assertEquals(1, found.direction());
		assertEquals(2, found.distance());
	}

	@Test
	void findsDockOnNegativeNormalSide() {
		put(-1, 66, 0, BlockSample.straightRail());

		DockResolution.Found found = found(SIGN, HorizontalAxis.X);

		assertEquals(new BlockLocation(-1, 66, 0), found.dock());
		assertEquals(-1, found.direction());
		assertEquals(1, found.distance());
	}

	@Test
	void findsDockAlongZAxis() {
		put(0, 65, 3, BlockSample.straightRail());

		DockResolution.Found found = found(SIGN, HorizontalAxis.Z);

		assertEquals(new BlockLocation(0, 65, 3), found.dock());
		assertEquals(HorizontalAxis.Z, found.axis());
		assertEquals(1, found.direction());
		assertEquals(3, found.distance());
	}

	@Test
	void rejectsWhenBothSidesFindDocks() {
		put(1, 64, 0, BlockSample.straightRail());
		put(-2, 64, 0, BlockSample.straightRail());

		assertEquals(DockError.AMBIGUOUS_BOTH_SIDES, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void rejectsWhenBothSidesHitRailsEvenIfOnlyOneIsStraight() {
		put(2, 64, 0, BlockSample.straightRail());
		put(-1, 64, 0, BlockSample.rail("minecraft:rail"));

		assertEquals(DockError.AMBIGUOUS_BOTH_SIDES, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void rejectsWhenNoSideFindsRail() {
		assertEquals(DockError.NO_RAIL, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void rejectsMultipleRailsInOneColumn() {
		put(2, 64, 0, BlockSample.straightRail());
		put(2, 65, 0, BlockSample.straightRail());

		assertEquals(DockError.MULTIPLE_RAILS, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void rejectsCurveOrSlopeInTheFirstRailColumn() {
		put(2, 64, 0, BlockSample.rail("minecraft:rail"));

		assertEquals(DockError.RAIL_NOT_STRAIGHT, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void firstRailColumnDecidesAndDoesNotSkipPastNonStraightRail() {
		put(1, 64, 0, BlockSample.rail("minecraft:rail"));
		put(2, 64, 0, BlockSample.straightRail());

		assertEquals(DockError.RAIL_NOT_STRAIGHT, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void stopsAtFirstDistanceWithDock() {
		put(2, 64, 0, BlockSample.straightRail());
		put(3, 64, 0, BlockSample.straightRail());

		DockResolution.Found found = found(SIGN, HorizontalAxis.X);
		assertEquals(2, found.distance());
		assertEquals(new BlockLocation(2, 64, 0), found.dock());
	}

	@Test
	void scansExactlyThreeBlocksBelowTheSign() {
		put(1, 64, 0, BlockSample.straightRail());

		DockResolution.Found found = found(SIGN, HorizontalAxis.X);
		assertEquals(1, found.distance());
	}

	@Test
	void ignoresRailsBelowTheVerticalScanWindow() {
		put(1, 63, 0, BlockSample.straightRail());

		assertEquals(DockError.NO_RAIL, dockError(SIGN, HorizontalAxis.X));
	}

	@Test
	void scansUpToFiveBlocksHorizontally() {
		put(5, 64, 0, BlockSample.straightRail());

		DockResolution.Found found = found(SIGN, HorizontalAxis.X);
		assertEquals(5, found.distance());
	}

	@Test
	void ignoresRailsBeyondFiveBlocks() {
		put(6, 64, 0, BlockSample.straightRail());

		assertEquals(DockError.NO_RAIL, dockError(SIGN, HorizontalAxis.X));
	}

	private void put(int x, int y, int z, BlockSample sample) {
		blocks.put(new BlockLocation(x, y, z), sample);
	}

	private DockResolution.Found found(BlockLocation sign, HorizontalAxis axis) {
		return assertInstanceOf(DockResolution.Found.class, PillarGeometry.resolveDock(sign, axis, view));
	}

	private DockError dockError(BlockLocation sign, HorizontalAxis axis) {
		return assertInstanceOf(DockResolution.Rejected.class, PillarGeometry.resolveDock(sign, axis, view)).error();
	}
}
