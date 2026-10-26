package dev.nikhcevov.railway.persistence;

import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;

import java.util.Objects;
import java.util.UUID;

/** Minimal trip checkpoint. It is reconciliation data, not proof of entity position. */
public record TripRecord(
		TripId id,
		UUID playerUuid,
		UUID cartUuid,
		StationId from,
		StationId to,
		TripPhase phase,
		long startedTick
) {
	public TripRecord {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(cartUuid, "cartUuid");
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(phase, "phase");
	}
}
