package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * Station as persisted. {@code exit} is the block occupied by the passenger's feet;
 * {@code exitYaw} is the yaw applied after disembarking.
 *
 * <p>A station is <em>anchored</em> when it has a dock. A <em>provisional</em> station
 * ({@code dock == null}) has no exit and can never be enabled: it is created by a
 * destination reference and becomes anchored when its own sign is registered.
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
		if (displayName.isBlank()) {
			throw new IllegalArgumentException("station displayName must not be blank");
		}
		if (dimension.isBlank()) {
			throw new IllegalArgumentException("station dimension must not be blank");
		}
		if (dock == null) {
			if (exit != null) {
				throw new IllegalArgumentException("provisional station cannot have an exit");
			}
			if (enabled) {
				throw new IllegalArgumentException("provisional station cannot be enabled");
			}
		}
	}

	/** Creates a dockless, exitless, disabled station. */
	public static Station provisional(StationId id, String displayName, String dimension, long revision) {
		return new Station(id, displayName, dimension, null, null, 0.0F, false, revision);
	}

	public boolean isProvisional() {
		return dock == null;
	}

	public boolean isAnchored() {
		return dock != null;
	}

	public boolean hasExit() {
		return exit != null;
	}

	public Station withEnabled(boolean value, long newRevision) {
		if (value && isProvisional()) {
			throw new IllegalStateException("provisional station cannot be enabled: " + id);
		}
		return new Station(id, displayName, dimension, dock, exit, exitYaw, value, newRevision);
	}

	public Station withDisplayName(String value, long newRevision) {
		return new Station(id, value, dimension, dock, exit, exitYaw, enabled, newRevision);
	}

	public Station withExit(BlockLocation value, float yaw, long newRevision) {
		if (isProvisional()) {
			throw new IllegalStateException("provisional station cannot have an exit: " + id);
		}
		return new Station(id, displayName, dimension, dock, value, yaw, enabled, newRevision);
	}
}
