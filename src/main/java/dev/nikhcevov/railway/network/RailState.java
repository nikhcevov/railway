package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;

/** A rail state is "which rail, and which side did we enter from". */
public record RailState(BlockLocation pos, RailPort entryPort) {
	public RailPort exitPort(RailShapeType shape) {
		return shape.other(entryPort);
	}
}
