package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PillarValidationTest {
	private static final BlockLocation DOCK = new BlockLocation(0, 64, 0);

	private final Map<BlockLocation, BlockSample> blocks = new HashMap<>();
	private final SignBlockView view = position -> blocks.getOrDefault(position, BlockSample.AIR);

	@Test
	void acceptsValidDestinationUnit() {
		PillarValidation.Valid valid = valid(unit(1, 66, 66));

		assertEquals(1, valid.pillarDistance());
	}

	@Test
	void acceptsIndependentSignAndButtonHeights() {
		PillarValidation.Valid valid = valid(unit(1, 67, 65));

		assertEquals(1, valid.pillarDistance());
	}

	@Test
	void acceptsEveryPillarDistanceFromOneToThree() {
		for (int supportX = 1; supportX <= 3; supportX++) {
			PillarValidation.Valid valid = valid(unit(supportX, 66, 66));
			assertEquals(supportX, valid.pillarDistance(), "support x " + supportX);
		}
	}

	@Test
	void rejectsPillarTooCloseAndTooFar() {
		assertEquals(PillarError.PILLAR_DISTANCE_OUT_OF_RANGE, invalid(unit(0, 66, 66)));
		assertEquals(PillarError.PILLAR_DISTANCE_OUT_OF_RANGE, invalid(unit(4, 66, 66)));
	}

	@Test
	void rejectsSignAtDockHeightAndAboveTheUnitRange() {
		assertEquals(PillarError.SIGN_HEIGHT_OUT_OF_RANGE, invalid(unit(1, 64, 66)));
		assertEquals(PillarError.SIGN_HEIGHT_OUT_OF_RANGE, invalid(unit(1, 68, 66)));
	}

	@Test
	void rejectsButtonAtDockHeightAndAboveTheUnitRange() {
		assertEquals(PillarError.BUTTON_HEIGHT_OUT_OF_RANGE, invalid(unit(1, 66, 64)));
		assertEquals(PillarError.BUTTON_HEIGHT_OUT_OF_RANGE, invalid(unit(1, 66, 68)));
	}

	@Test
	void rejectsMissingButton() {
		BlockLocation support = new BlockLocation(1, 66, 0);
		put(support, solid());
		PillarUnit anchor = new PillarUnit(new BlockLocation(2, 66, 0), support, HorizontalAxis.X);

		assertEquals(PillarError.MISSING_BUTTON, invalid(anchor));
	}

	@Test
	void rejectsNonStoneButton() {
		PillarUnit unit = unit(1, 66, 66);
		put(new BlockLocation(1, 66, 1), BlockSample.other("minecraft:oak_button"));

		assertEquals(PillarError.BUTTON_NOT_STONE, invalid(unit));
	}

	@Test
	void rejectsButtonNotAttachedToThePillarColumn() {
		PillarUnit unit = unit(1, 66, 66);
		put(new BlockLocation(1, 66, 1), BlockSample.AIR);
		BlockLocation away = new BlockLocation(4, 66, 1);
		put(away, BlockSample.stoneButtonBlock());

		PillarUnit moved = new PillarUnit(unit.sign(), unit.signSupport(), HorizontalAxis.X, away);
		assertEquals(PillarError.BUTTON_NOT_ON_PILLAR, invalid(moved));
	}

	@Test
	void rejectsButtonWithoutSolidSupportBehindIt() {
		BlockLocation support = new BlockLocation(1, 66, 0);
		put(support, solid());
		BlockLocation button = new BlockLocation(1, 65, 1);
		put(button, BlockSample.stoneButtonBlock());

		PillarUnit unit = new PillarUnit(new BlockLocation(2, 66, 0), support, HorizontalAxis.X, button);
		assertEquals(PillarError.BUTTON_SUPPORT_NOT_SOLID, invalid(unit));
	}

	@Test
	void rejectsNonSolidSignSupport() {
		BlockLocation support = new BlockLocation(1, 66, 0);
		PillarUnit unit = new PillarUnit(new BlockLocation(2, 66, 0), support, HorizontalAxis.X);

		assertEquals(PillarError.SIGN_SUPPORT_NOT_SOLID, invalid(unit));
	}

	@Test
	void rejectsSignNotAttachedAlongTheNormalAxis() {
		BlockLocation support = new BlockLocation(1, 66, 0);
		put(support, solid());

		PillarUnit tooFar = new PillarUnit(new BlockLocation(3, 66, 0), support, HorizontalAxis.X);
		assertEquals(PillarError.SIGN_NOT_ATTACHED, invalid(tooFar));

		PillarUnit wrongHeight = new PillarUnit(new BlockLocation(2, 67, 0), support, HorizontalAxis.X);
		assertEquals(PillarError.SIGN_NOT_ATTACHED, invalid(wrongHeight));

		PillarUnit offAxis = new PillarUnit(new BlockLocation(2, 66, 1), support, HorizontalAxis.X);
		assertEquals(PillarError.SIGN_NOT_ATTACHED, invalid(offAxis));
	}

	@Test
	void rejectsDockNotOnTheSignNormalAxis() {
		BlockLocation misalignedDock = new BlockLocation(0, 64, 1);

		assertEquals(PillarError.DOCK_NOT_ALIGNED, invalid(unit(1, 66, 66), misalignedDock));
	}

	@Test
	void acceptsZAxisUnit() {
		BlockLocation support = new BlockLocation(0, 66, 1);
		put(support, solid());
		BlockLocation button = new BlockLocation(1, 66, 1);
		put(button, BlockSample.stoneButtonBlock());

		PillarUnit unit = new PillarUnit(new BlockLocation(0, 66, 2), support, HorizontalAxis.Z, button);

		PillarValidation.Valid valid = assertInstanceOf(
				PillarValidation.Valid.class, PillarGeometry.validate(unit, DOCK, view));
		assertEquals(1, valid.pillarDistance());
	}

	@Test
	void anchorValidationDoesNotRequireAButton() {
		BlockLocation support = new BlockLocation(1, 66, 0);
		put(support, solid());
		PillarUnit anchor = new PillarUnit(new BlockLocation(2, 66, 0), support, HorizontalAxis.X);

		PillarValidation.Valid valid = assertInstanceOf(
				PillarValidation.Valid.class, PillarGeometry.validateAnchor(anchor, DOCK, view));
		assertEquals(1, valid.pillarDistance());
	}

	@Test
	void destinationValidationDoesNotRequireDockAxisAlignment() {
		PillarUnit unit = unit(1, 66, 66);
		BlockLocation offAxisDock = new BlockLocation(0, 64, 1);

		PillarValidation.Valid valid = assertInstanceOf(PillarValidation.Valid.class,
				PillarGeometry.validateDestination(unit, offAxisDock, view));
		assertEquals(2, valid.pillarDistance());

		PillarValidation.Invalid tooFar = assertInstanceOf(PillarValidation.Invalid.class,
				PillarGeometry.validateDestination(unit, new BlockLocation(6, 64, 0), view));
		assertEquals(PillarError.PILLAR_DISTANCE_OUT_OF_RANGE, tooFar.error());
	}

	@Test
	void anchorValidationStillChecksSignGeometry() {
		BlockLocation support = new BlockLocation(1, 64, 0);
		put(support, solid());
		PillarUnit anchor = new PillarUnit(new BlockLocation(2, 64, 0), support, HorizontalAxis.X);

		PillarValidation.Invalid invalid = assertInstanceOf(
				PillarValidation.Invalid.class, PillarGeometry.validateAnchor(anchor, DOCK, view));
		assertEquals(PillarError.SIGN_HEIGHT_OUT_OF_RANGE, invalid.error());
	}

	private PillarUnit unit(int supportX, int signY, int buttonY) {
		BlockLocation support = new BlockLocation(supportX, signY, 0);
		BlockLocation sign = new BlockLocation(supportX + 1, signY, 0);
		BlockLocation button = new BlockLocation(supportX, buttonY, 1);
		put(support, solid());
		put(new BlockLocation(supportX, buttonY, 0), solid());
		put(button, BlockSample.stoneButtonBlock());
		return new PillarUnit(sign, support, HorizontalAxis.X, button);
	}

	private static BlockSample solid() {
		return BlockSample.solid("minecraft:stone");
	}

	private void put(BlockLocation position, BlockSample sample) {
		blocks.put(position, sample);
	}

	private PillarValidation.Valid valid(PillarUnit unit) {
		return assertInstanceOf(PillarValidation.Valid.class, PillarGeometry.validate(unit, DOCK, view));
	}

	private PillarError invalid(PillarUnit unit) {
		return invalid(unit, DOCK);
	}

	private PillarError invalid(PillarUnit unit, BlockLocation dock) {
		return assertInstanceOf(PillarValidation.Invalid.class,
				PillarGeometry.validate(unit, dock, view)).error();
	}
}
