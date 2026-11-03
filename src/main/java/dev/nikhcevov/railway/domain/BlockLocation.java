package dev.nikhcevov.railway.domain;

import java.util.Comparator;

/** Immutable block coordinate that does not depend on Minecraft classes. */
public record BlockLocation(int x, int y, int z) {
	/** Stable x, then y, then z ordering used wherever sets or maps must be deterministic. */
	public static final Comparator<BlockLocation> POSITION_ORDER =
			Comparator.comparingInt(BlockLocation::x)
					.thenComparingInt(BlockLocation::y)
					.thenComparingInt(BlockLocation::z);

	public BlockLocation offset(int dx, int dy, int dz) {
		return new BlockLocation(x + dx, y + dy, z + dz);
	}

	public BlockLocation offset(BlockLocation other) {
		return new BlockLocation(x + other.x, y + other.y, z + other.z);
	}

	public String shortString() {
		return x + " " + y + " " + z;
	}
}
