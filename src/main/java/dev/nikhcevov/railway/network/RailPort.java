package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

/** Horizontal side of a rail block. */
public enum RailPort {
	NORTH(0, -1),
	EAST(1, 0),
	SOUTH(0, 1),
	WEST(-1, 0);

	private final int dx;
	private final int dz;

	RailPort(int dx, int dz) {
		this.dx = dx;
		this.dz = dz;
	}

	public int dx() {
		return dx;
	}

	public int dz() {
		return dz;
	}

	public RailPort opposite() {
		return switch (this) {
			case NORTH -> SOUTH;
			case SOUTH -> NORTH;
			case EAST -> WEST;
			case WEST -> EAST;
		};
	}

	public BlockLocation adjacent(BlockLocation pos, int y) {
		return new BlockLocation(pos.x() + dx, y, pos.z() + dz);
	}
}
