package dev.nikhcevov.railway.domain;

public enum TripPhase {
	PREPARING,
	BOARDING,
	RUNNING,
	ARRIVING,
	COMPLETED,
	ABORTING,
	ABORTED,
	RECOVERING;

	public boolean isTerminal() {
		return this == COMPLETED || this == ABORTED;
	}

	public boolean isActive() {
		return !isTerminal();
	}

	public boolean canTransitionTo(TripPhase next) {
		return switch (this) {
			case PREPARING -> next == BOARDING || next == ABORTING;
			case BOARDING -> next == RUNNING || next == ABORTING;
			case RUNNING -> next == ARRIVING || next == ABORTING;
			case ARRIVING -> next == COMPLETED || next == ABORTING;
			case ABORTING -> next == ABORTED;
			case RECOVERING -> next == ABORTED || next == COMPLETED;
			case COMPLETED, ABORTED -> false;
		};
	}
}
