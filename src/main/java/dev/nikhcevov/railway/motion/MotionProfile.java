package dev.nikhcevov.railway.motion;

/**
 * Speed profile in blocks per second, evaluated once per server tick
 * (dt = 0.05 s). The vanilla entity remains the mover; this only chooses speed.
 */
public record MotionProfile(double maxSpeed, double acceleration, double braking, double stopTolerance) {
	public static final double TICK_SECONDS = 0.05;
	private static final double FINAL_APPROACH_SPEED = 0.5;

	public double brakingDistance(double speed) {
		return speed * speed / (2.0 * braking);
	}

	/** Highest speed from which the cart can still stop within {@code remaining}. */
	public double targetSpeed(double remaining) {
		if (remaining <= stopTolerance) {
			return 0.0;
		}
		double allowed = Math.sqrt(2.0 * braking * (remaining - stopTolerance));
		return Math.min(Math.min(maxSpeed, allowed), remaining < 1.0 ? FINAL_APPROACH_SPEED : Double.MAX_VALUE);
	}

	/** Moves current speed toward the target, never changing it faster than the configured rates. */
	public double step(double currentSpeed, double targetSpeed) {
		double dt = TICK_SECONDS;
		if (targetSpeed > currentSpeed) {
			return Math.min(targetSpeed, currentSpeed + acceleration * dt);
		}
		return Math.max(targetSpeed, currentSpeed - braking * dt);
	}

	public boolean canStopWithin(double speed, double remaining) {
		return brakingDistance(speed) <= remaining;
	}
}
