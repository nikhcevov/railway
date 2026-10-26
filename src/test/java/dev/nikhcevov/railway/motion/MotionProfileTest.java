package dev.nikhcevov.railway.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionProfileTest {
	private static final double EPSILON = 1.0e-12;
	private static final MotionProfile PROFILE = new MotionProfile(8.0, 2.0, 4.0, 0.1);

	@Test
	void brakingDistanceIsQuadraticInSpeed() {
		assertEquals(0.0, PROFILE.brakingDistance(0.0), EPSILON);
		assertEquals(0.5, PROFILE.brakingDistance(2.0), EPSILON);
		assertEquals(2.0, PROFILE.brakingDistance(4.0), EPSILON);
		assertEquals(8.0, PROFILE.brakingDistance(8.0), EPSILON);
		assertEquals(4.0 * PROFILE.brakingDistance(2.0), PROFILE.brakingDistance(4.0), EPSILON);
	}

	@Test
	void targetSpeedNeverExceedsMaxSpeed() {
		assertEquals(8.0, PROFILE.targetSpeed(1000.0), EPSILON);
		assertEquals(8.0, PROFILE.targetSpeed(50.0), EPSILON);
		assertTrue(PROFILE.targetSpeed(1000.0) <= PROFILE.maxSpeed());
	}

	@Test
	void targetSpeedFollowsTheStoppingCurve() {
		assertEquals(Math.sqrt(2.0 * 4.0 * (5.0 - 0.1)), PROFILE.targetSpeed(5.0), EPSILON);
		assertEquals(Math.sqrt(2.0 * 4.0 * (2.0 - 0.1)), PROFILE.targetSpeed(2.0), EPSILON);
	}

	@Test
	void targetSpeedDecreasesMonotonicallyAsRemainingShrinks() {
		double previous = Double.MAX_VALUE;
		for (int hundredths = 2000; hundredths >= 0; hundredths--) {
			double remaining = hundredths / 100.0;
			double current = PROFILE.targetSpeed(remaining);
			assertTrue(current <= previous + EPSILON,
					"target speed increased while remaining shrank to " + remaining);
			previous = current;
		}
	}

	@Test
	void finalApproachSpeedIsLimitedBelowOneBlock() {
		for (int hundredths = 1; hundredths <= 99; hundredths++) {
			double remaining = hundredths / 100.0;
			double target = PROFILE.targetSpeed(remaining);
			assertTrue(target <= 0.5 + EPSILON, "final approach speed too high at remaining " + remaining);
			assertTrue(target >= 0.0);
		}
	}

	@Test
	void targetSpeedIsZeroInsideTheStopTolerance() {
		assertEquals(0.0, PROFILE.targetSpeed(PROFILE.stopTolerance()), EPSILON);
		assertEquals(0.0, PROFILE.targetSpeed(0.05), EPSILON);
		assertEquals(0.0, PROFILE.targetSpeed(0.0), EPSILON);
		assertEquals(0.0, PROFILE.targetSpeed(-1.0), EPSILON);
	}

	@Test
	void stepAcceleratesWithinTheConfiguredAcceleration() {
		assertEquals(0.1, PROFILE.step(0.0, 8.0), EPSILON);
		assertEquals(1.0 + 2.0 * MotionProfile.TICK_SECONDS, PROFILE.step(1.0, 8.0), EPSILON);
		assertEquals(0.05, PROFILE.step(0.0, 0.05), EPSILON);
		assertEquals(3.0, PROFILE.step(3.0, 3.0), EPSILON);
		assertTrue(PROFILE.step(7.99, 8.0) <= PROFILE.maxSpeed());
	}

	@Test
	void stepBrakesWithinTheConfiguredBrakingRate() {
		assertEquals(4.8, PROFILE.step(5.0, 0.0), EPSILON);
		assertEquals(4.0 - 4.0 * MotionProfile.TICK_SECONDS, PROFILE.step(4.0, 0.0), EPSILON);
		assertEquals(0.0, PROFILE.step(0.1, 0.0), EPSILON, "speed never becomes negative");
		assertEquals(0.0, PROFILE.step(0.0, 0.0), EPSILON);
	}

	@Test
	void canStopWithinUsesTheBrakingDistanceBoundary() {
		assertTrue(PROFILE.canStopWithin(8.0, 8.0), "exactly the braking distance is enough");
		assertFalse(PROFILE.canStopWithin(8.0, 7.999));
		assertTrue(PROFILE.canStopWithin(4.0, 2.0));
		assertFalse(PROFILE.canStopWithin(4.0, 1.999));
		assertTrue(PROFILE.canStopWithin(0.0, 0.0));
	}

	@Test
	void simulatedBrakingFromMaxSpeedStopsWithinComputedDistance() {
		double startSpeed = 8.0;
		double stoppingDistance = PROFILE.brakingDistance(startSpeed);
		double speed = startSpeed;
		double travelled = 0.0;
		int ticks = 0;

		while (speed > 0.0 && ticks < 1000) {
			double remaining = stoppingDistance - travelled;
			speed = PROFILE.step(speed, PROFILE.targetSpeed(remaining));
			travelled += speed * MotionProfile.TICK_SECONDS;
			ticks++;
		}

		assertTrue(ticks < 1000, "cart never came to rest");
		assertEquals(0.0, speed, EPSILON);
		assertTrue(travelled > stoppingDistance - 0.5, "cart stopped implausibly early at " + travelled);
		assertTrue(travelled <= stoppingDistance + PROFILE.stopTolerance() + 1.0e-9,
				"cart overran the computed braking distance: " + travelled + " > " + stoppingDistance);
	}
}
