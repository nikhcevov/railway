package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

/** Required physical shape of a turnout rail. */
public record SwitchRequirement(BlockLocation pos, RailShapeType requiredShape) {
}
