package dev.nikhcevov.railway.network;

import java.util.List;

/**
 * Rail block shapes in the same vocabulary as vanilla {@code RailShape}, kept
 * Minecraft-free for the graph core. Port heights are relative to the rail
 * block's Y: ascending shapes have one end one block higher.
 */
public enum RailShapeType {
	NORTH_SOUTH(RailPort.NORTH, RailPort.SOUTH),
	EAST_WEST(RailPort.EAST, RailPort.WEST),
	ASCENDING_EAST(RailPort.WEST, RailPort.EAST),
	ASCENDING_WEST(RailPort.EAST, RailPort.WEST),
	ASCENDING_NORTH(RailPort.SOUTH, RailPort.NORTH),
	ASCENDING_SOUTH(RailPort.NORTH, RailPort.SOUTH),
	SOUTH_EAST(RailPort.SOUTH, RailPort.EAST),
	SOUTH_WEST(RailPort.SOUTH, RailPort.WEST),
	NORTH_WEST(RailPort.NORTH, RailPort.WEST),
	NORTH_EAST(RailPort.NORTH, RailPort.EAST);

	private final RailPort first;
	private final RailPort second;

	RailShapeType(RailPort first, RailPort second) {
		this.first = first;
		this.second = second;
	}

	public List<RailPort> ports() {
		return List.of(first, second);
	}

	public boolean hasPort(RailPort port) {
		return port == first || port == second;
	}

	public RailPort other(RailPort port) {
		if (port == first) {
			return second;
		}
		if (port == second) {
			return first;
		}
		throw new IllegalArgumentException(port + " is not a port of " + this);
	}

	public int portHeight(RailPort port) {
		if (!hasPort(port)) {
			throw new IllegalArgumentException(port + " is not a port of " + this);
		}
		return switch (this) {
			case ASCENDING_EAST -> port == RailPort.EAST ? 1 : 0;
			case ASCENDING_WEST -> port == RailPort.WEST ? 1 : 0;
			case ASCENDING_NORTH -> port == RailPort.NORTH ? 1 : 0;
			case ASCENDING_SOUTH -> port == RailPort.SOUTH ? 1 : 0;
			default -> 0;
		};
	}

	public boolean isSlope() {
		return this == ASCENDING_EAST || this == ASCENDING_WEST || this == ASCENDING_NORTH || this == ASCENDING_SOUTH;
	}

	public boolean isCurve() {
		return !isStraight();
	}

	public boolean isStraight() {
		return switch (this) {
			case NORTH_SOUTH, EAST_WEST, ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH -> true;
			case SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST -> false;
		};
	}

	public boolean isStraightHorizontal() {
		return isStraight() && !isSlope();
	}
}
