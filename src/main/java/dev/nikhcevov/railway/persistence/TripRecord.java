package dev.nikhcevov.railway.persistence;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal trip checkpoint. It is reconciliation data, not proof of entity position.
 *
 * <p>Schema 4 adds the last checkpointed dimension and block position so recovery can
 * locate the cart's chunk without scanning the world. Both fields are null for
 * checkpoints written by older schemas or when a position was never captured.
 */
public record TripRecord(
		TripId id,
		UUID playerUuid,
		UUID cartUuid,
		StationId from,
		StationId to,
		TripPhase phase,
		long startedTick,
		String dimension,
		BlockLocation position
) {
	public TripRecord {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(cartUuid, "cartUuid");
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(phase, "phase");
		// A checkpoint always has both halves of the position or neither.
		if (dimension == null || dimension.isBlank() || position == null) {
			dimension = null;
			position = null;
		}
	}

	/** Positionless checkpoint, the shape persisted by schema 3. */
	public TripRecord(TripId id, UUID playerUuid, UUID cartUuid, StationId from, StationId to,
			TripPhase phase, long startedTick) {
		this(id, playerUuid, cartUuid, from, to, phase, startedTick, null, null);
	}

	public boolean hasPosition() {
		return dimension != null && position != null;
	}

	public TripRecord withCheckpoint(String dimension, BlockLocation position, TripPhase phase) {
		return new TripRecord(id, playerUuid, cartUuid, from, to, phase, startedTick, dimension, position);
	}

	public TripRecord withPhase(TripPhase phase) {
		return new TripRecord(id, playerUuid, cartUuid, from, to, phase, startedTick, dimension, position);
	}

	/** Drops the position, keeping the identity used by reconciliation. */
	public TripRecord withoutCheckpoint() {
		return new TripRecord(id, playerUuid, cartUuid, from, to, phase, startedTick);
	}
}
