package dev.nikhcevov.railway.sign;

import java.util.Objects;

/**
 * Immutable, Minecraft-free description of one block needed by Railway sign
 * geometry.
 *
 * <p>Adapters sample the world and set exactly the flags that apply. A sample
 * that is not a rail must set {@code rail} to {@code false}; a straight
 * horizontal rail additionally sets {@code straightHorizontalRail}.
 *
 * @param id                    namespaced block id, used for diagnostics only
 * @param solidSupport         block can carry a wall sign or a stone button
 * @param stoneButton          block is a stone button
 * @param rail                 block is a rail of any shape or slope
 * @param straightHorizontalRail rail is a straight, flat, horizontal rail
 */
public record BlockSample(
		String id,
		boolean solidSupport,
		boolean stoneButton,
		boolean rail,
		boolean straightHorizontalRail
) {
	public static final BlockSample AIR = new BlockSample("minecraft:air", false, false, false, false);

	public BlockSample {
		Objects.requireNonNull(id, "id");
		if (straightHorizontalRail && !rail) {
			throw new IllegalArgumentException("a straight horizontal rail must also be a rail");
		}
	}

	public static BlockSample solid(String id) {
		return new BlockSample(id, true, false, false, false);
	}

	public static BlockSample stoneButtonBlock() {
		return new BlockSample("minecraft:stone_button", false, true, false, false);
	}

	/** A rail that is not straight and horizontal, such as a curve or slope. */
	public static BlockSample rail(String id) {
		return new BlockSample(id, false, false, true, false);
	}

	public static BlockSample straightRail() {
		return new BlockSample("minecraft:rail", false, false, true, true);
	}

	public static BlockSample other(String id) {
		return new BlockSample(id, false, false, false, false);
	}
}
