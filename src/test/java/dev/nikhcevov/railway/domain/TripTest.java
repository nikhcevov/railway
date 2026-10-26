package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripTest {
	private static final RoutePlan PLAN = new RoutePlan(
			new StationId("alpha"),
			new StationId("beta"),
			"minecraft:overworld",
			RailAxis.X,
			new BlockLocation(0, 64, 0),
			new BlockLocation(4, 64, 0),
			new BlockLocation(4, 65, 0),
			90.0f,
			1,
			4.0,
			"minecraft:overworld|X|0,64,0|4,64,0"
	);

	@Test
	void followsTheNominalPhaseSequence() {
		Trip trip = newTrip(100L);
		assertEquals(TripPhase.PREPARING, trip.phase());
		assertEquals(100L, trip.phaseSinceTick());
		assertTrue(trip.isActive());

		trip.transitionTo(TripPhase.BOARDING, 110L);
		assertEquals(TripPhase.BOARDING, trip.phase());
		assertEquals(110L, trip.phaseSinceTick());
		assertTrue(trip.isActive());

		trip.transitionTo(TripPhase.RUNNING, 120L);
		assertEquals(TripPhase.RUNNING, trip.phase());
		assertTrue(trip.isActive());

		trip.transitionTo(TripPhase.ARRIVING, 180L);
		assertEquals(TripPhase.ARRIVING, trip.phase());
		assertEquals(180L, trip.phaseSinceTick());
		assertTrue(trip.isActive());

		trip.transitionTo(TripPhase.COMPLETED, 200L);
		assertEquals(TripPhase.COMPLETED, trip.phase());
		assertEquals(200L, trip.phaseSinceTick());
		assertTrue(trip.phase().isTerminal());
		assertFalse(trip.isActive());
	}

	@Test
	void illegalTransitionThrowsAndLeavesPhaseUntouched() {
		Trip trip = newTrip(100L);

		assertThrows(IllegalStateException.class, () -> trip.transitionTo(TripPhase.RUNNING, 105L));
		assertEquals(TripPhase.PREPARING, trip.phase());
		assertEquals(100L, trip.phaseSinceTick());

		assertThrows(IllegalStateException.class, () -> trip.transitionTo(TripPhase.COMPLETED, 106L));
		assertEquals(TripPhase.PREPARING, trip.phase());

		trip.transitionTo(TripPhase.BOARDING, 110L);
		assertThrows(IllegalStateException.class, () -> trip.transitionTo(TripPhase.ARRIVING, 115L));
		assertEquals(TripPhase.BOARDING, trip.phase());
		assertEquals(110L, trip.phaseSinceTick());
	}

	@Test
	void terminalPhasesRejectFurtherTransitions() {
		Trip trip = newTrip(100L);
		trip.transitionTo(TripPhase.BOARDING, 110L);
		trip.transitionTo(TripPhase.RUNNING, 120L);
		trip.transitionTo(TripPhase.ARRIVING, 180L);
		trip.transitionTo(TripPhase.COMPLETED, 200L);

		assertThrows(IllegalStateException.class, () -> trip.transitionTo(TripPhase.PREPARING, 210L));
		assertThrows(IllegalStateException.class, () -> trip.transitionTo(TripPhase.ABORTING, 211L));
		assertEquals(TripPhase.COMPLETED, trip.phase());
		assertEquals(200L, trip.phaseSinceTick());
	}

	@Test
	void abortFromRunningRecordsReasonThenCompletesAsAborted() {
		Trip trip = newTrip(100L);
		trip.transitionTo(TripPhase.BOARDING, 110L);
		trip.transitionTo(TripPhase.RUNNING, 120L);

		trip.aborting("cart destroyed", 130L);
		assertEquals(TripPhase.ABORTING, trip.phase());
		assertEquals(130L, trip.phaseSinceTick());
		assertEquals("cart destroyed", trip.abortReason());
		assertTrue(trip.isActive(), "ABORTING is not a terminal phase");

		trip.transitionTo(TripPhase.ABORTED, 140L);
		assertEquals(TripPhase.ABORTED, trip.phase());
		assertEquals(140L, trip.phaseSinceTick());
		assertEquals("cart destroyed", trip.abortReason());
		assertTrue(trip.phase().isTerminal());
		assertFalse(trip.isActive());

		assertThrows(IllegalStateException.class, () -> trip.transitionTo(TripPhase.PREPARING, 150L));
		assertEquals(TripPhase.ABORTED, trip.phase());
	}

	@Test
	void abortFromPreparingSkipsStraightToAborting() {
		Trip trip = newTrip(100L);

		trip.aborting("player left", 105L);

		assertEquals(TripPhase.ABORTING, trip.phase());
		assertEquals(105L, trip.phaseSinceTick());
		assertEquals("player left", trip.abortReason());

		trip.transitionTo(TripPhase.ABORTED, 110L);
		assertFalse(trip.isActive());
	}

	@Test
	void abortingTwiceKeepsTheTripInAborting() {
		Trip trip = newTrip(100L);
		trip.transitionTo(TripPhase.BOARDING, 110L);
		trip.transitionTo(TripPhase.RUNNING, 120L);

		trip.aborting("first", 130L);
		trip.aborting("second", 135L);

		assertEquals(TripPhase.ABORTING, trip.phase());
		assertEquals("second", trip.abortReason());
		assertEquals(130L, trip.phaseSinceTick(), "repeated abort must not restart the abort phase");
	}

	@Test
	void terminalPhasesAreNotActive() {
		assertFalse(TripPhase.COMPLETED.isActive());
		assertFalse(TripPhase.ABORTED.isActive());
		assertTrue(TripPhase.ABORTING.isActive());
		assertTrue(TripPhase.RECOVERING.isActive());

		assertFalse(TripPhase.COMPLETED.canTransitionTo(TripPhase.BOARDING));
		assertFalse(TripPhase.ABORTED.canTransitionTo(TripPhase.ABORTING));
		assertTrue(TripPhase.ABORTING.canTransitionTo(TripPhase.ABORTED));
	}

	private static Trip newTrip(long startedTick) {
		return new Trip(TripId.random(), UUID.randomUUID(), UUID.randomUUID(), PLAN, startedTick);
	}
}
