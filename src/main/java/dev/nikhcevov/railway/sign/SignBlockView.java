package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

/**
 * Read-only block access used by sign geometry.
 *
 * <p>Implementations must be side-effect free and must return
 * {@link BlockSample#AIR} for unloaded or otherwise unavailable positions
 * rather than {@code null}. The interface deliberately depends only on domain
 * coordinates so geometry stays unit-testable without Minecraft.
 */
@FunctionalInterface
public interface SignBlockView {
	BlockSample sample(BlockLocation position);
}
