package dev.nikhcevov.railway.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Station as persisted. {@code exit} is the block occupied by the passenger's feet;
 * {@code exitYaw} is the yaw applied after disembarking.
 *
 * <p>A station is <em>anchored</em> when it has a dock. A <em>provisional</em> station
 * ({@code dock == null}) has no exit and can never be enabled: it is created by a
 * destination reference and becomes anchored when its own sign is registered.
 *
 * <p>{@code arrivalPlatforms} holds at most one platform per source station. A trip
 * arriving from a source with a platform uses that platform's dock, exit, and yaw
 * instead of the station main ones.
 */
public record Station(
		StationId id,
		String displayName,
		String dimension,
		BlockLocation dock,
		BlockLocation exit,
		float exitYaw,
		boolean enabled,
		long revision,
		List<ArrivalPlatform> arrivalPlatforms
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
			if (arrivalPlatforms != null && !arrivalPlatforms.isEmpty()) {
				throw new IllegalArgumentException("provisional station cannot have arrival platforms");
			}
		}
		List<ArrivalPlatform> platforms = arrivalPlatforms == null ? List.of() : arrivalPlatforms;
		Set<StationId> sources = new HashSet<>();
		for (ArrivalPlatform platform : platforms) {
			if (platform == null) {
				throw new IllegalArgumentException("arrival platform must not be null");
			}
			if (!sources.add(platform.source())) {
				throw new IllegalArgumentException(
						"duplicate arrival platform source: " + platform.source());
			}
		}
		arrivalPlatforms = List.copyOf(platforms);
	}

	/** Compatibility constructor for a station without per-origin arrival platforms. */
	public Station(StationId id, String displayName, String dimension, BlockLocation dock,
			BlockLocation exit, float exitYaw, boolean enabled, long revision) {
		this(id, displayName, dimension, dock, exit, exitYaw, enabled, revision, List.of());
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

	/** The arrival platform registered for this source, when one exists. */
	public Optional<ArrivalPlatform> arrivalFrom(StationId source) {
		Objects.requireNonNull(source, "source");
		for (ArrivalPlatform platform : arrivalPlatforms) {
			if (platform.source().equals(source)) {
				return Optional.of(platform);
			}
		}
		return Optional.empty();
	}

	public Station withEnabled(boolean value, long newRevision) {
		if (value && isProvisional()) {
			throw new IllegalStateException("provisional station cannot be enabled: " + id);
		}
		return new Station(id, displayName, dimension, dock, exit, exitYaw, value, newRevision,
				arrivalPlatforms);
	}

	public Station withDisplayName(String value, long newRevision) {
		return new Station(id, value, dimension, dock, exit, exitYaw, enabled, newRevision,
				arrivalPlatforms);
	}

	public Station withExit(BlockLocation value, float yaw, long newRevision) {
		if (isProvisional()) {
			throw new IllegalStateException("provisional station cannot have an exit: " + id);
		}
		return new Station(id, displayName, dimension, dock, value, yaw, enabled, newRevision,
				arrivalPlatforms);
	}

	/** Adds the platform or replaces the existing platform for the same source station. */
	public Station withArrivalPlatform(ArrivalPlatform platform, long newRevision) {
		Objects.requireNonNull(platform, "platform");
		if (isProvisional()) {
			throw new IllegalStateException("provisional station cannot have an arrival platform: " + id);
		}
		List<ArrivalPlatform> updated = new ArrayList<>(arrivalPlatforms.size() + 1);
		boolean replaced = false;
		for (ArrivalPlatform existing : arrivalPlatforms) {
			if (existing.source().equals(platform.source())) {
				updated.add(platform);
				replaced = true;
			} else {
				updated.add(existing);
			}
		}
		if (!replaced) {
			updated.add(platform);
		}
		return new Station(id, displayName, dimension, dock, exit, exitYaw, enabled, newRevision, updated);
	}

	/** Removes the platform for the source; the platform list is unchanged when none exists. */
	public Station withoutArrivalPlatform(StationId source, long newRevision) {
		Objects.requireNonNull(source, "source");
		List<ArrivalPlatform> updated = arrivalPlatforms.stream()
				.filter(platform -> !platform.source().equals(source))
				.toList();
		return new Station(id, displayName, dimension, dock, exit, exitYaw, enabled, newRevision, updated);
	}

	/**
	 * Replaces the station's main dock, exit, and yaw. The id, display name, dimension,
	 * and enabled flag are preserved. A provisional station becomes anchored (but stays
	 * disabled) when given a main dock.
	 */
	public Station withMain(BlockLocation newDock, BlockLocation newExit, float newExitYaw, long newRevision) {
		return new Station(id, displayName, dimension, newDock, newExit, newExitYaw, enabled, newRevision,
				arrivalPlatforms);
	}

	/** Replaces the main dock, exit, and yaw without changing the station revision. */
	public Station withMain(BlockLocation newDock, BlockLocation newExit, float newExitYaw) {
		return withMain(newDock, newExit, newExitYaw, revision);
	}
}
