package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Station as persisted. {@code exit} is the block occupied by the passenger's feet;
 * {@code exitYaw} is the yaw applied after disembarking.
 */
public record Station(
		StationId id,
		String displayName,
		String dimension,
		BlockLocation dock,
		BlockLocation exit,
		float exitYaw,
		boolean enabled,
		long revision
) {
	public Station {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(dock, "dock");
	}

	public boolean hasExit() {
		return exit != null;
	}

	public Station withEnabled(boolean value, long newRevision) {
		return new Station(id, displayName, dimension, dock, exit, exitYaw, value, newRevision);
	}

	public Station withDisplayName(String value, long newRevision) {
		return new Station(id, value, dimension, dock, exit, exitYaw, enabled, newRevision);
	}

	public Station withExit(BlockLocation value, float yaw, long newRevision) {
		return new Station(id, displayName, dimension, dock, value, yaw, enabled, newRevision);
	}
}
