package dev.nikhcevov.railway.domain;

import java.util.Objects;
import java.util.UUID;

/** Runtime trip. Identity of the trip is distinct from the service cart UUID. */
public final class Trip {
	private final TripId id;
	private final UUID playerUuid;
	private final UUID cartUuid;
	private final TripPlan plan;
	private final long startedTick;
	private TripPhase phase;
	private long phaseSinceTick;
	private String abortReason;

	public Trip(TripId id, UUID playerUuid, UUID cartUuid, TripPlan plan, long startedTick) {
		this.id = Objects.requireNonNull(id, "id");
		this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
		this.cartUuid = Objects.requireNonNull(cartUuid, "cartUuid");
		this.plan = Objects.requireNonNull(plan, "plan");
		this.startedTick = startedTick;
		this.phase = TripPhase.PREPARING;
		this.phaseSinceTick = startedTick;
	}

	public TripId id() {
		return id;
	}

	public UUID playerUuid() {
		return playerUuid;
	}

	public UUID cartUuid() {
		return cartUuid;
	}

	public TripPlan plan() {
		return plan;
	}

	public long startedTick() {
		return startedTick;
	}

	public TripPhase phase() {
		return phase;
	}

	public long phaseSinceTick() {
		return phaseSinceTick;
	}

	public String abortReason() {
		return abortReason;
	}

	public void transitionTo(TripPhase next, long tick) {
		if (!phase.canTransitionTo(next)) {
			throw new IllegalStateException("illegal trip transition " + phase + " -> " + next);
		}
		phase = next;
		phaseSinceTick = tick;
	}

	public void aborting(String reason, long tick) {
		abortReason = reason;
		if (phase != TripPhase.ABORTING) {
			transitionTo(TripPhase.ABORTING, tick);
		}
	}

	public boolean isActive() {
		return phase.isActive();
	}
}
