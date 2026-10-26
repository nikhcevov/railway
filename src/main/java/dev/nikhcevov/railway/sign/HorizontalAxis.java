package dev.nikhcevov.railway.sign;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.Objects;

/** Horizontal axis of a wall sign's front/back normal. */
public enum HorizontalAxis {
	X,
	Z;

	/** Moves {@code origin} by {@code distance} blocks along this axis. */
	public BlockLocation offset(BlockLocation origin, int distance) {
		Objects.requireNonNull(origin, "origin");
		return switch (this) {
			case X -> origin.offset(distance, 0, 0);
			case Z -> origin.offset(0, 0, distance);
		};
	}

	/** Returns this axis' coordinate from {@code position}. */
	public int component(BlockLocation position) {
		Objects.requireNonNull(position, "position");
		return switch (this) {
			case X -> position.x();
			case Z -> position.z();
		};
	}
}
