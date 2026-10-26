package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

/** One traversed rail in a planned route. */
public record RouteStep(BlockLocation pos, RailShapeType shape, RailPort entryPort, RailPort exitPort) {
}
