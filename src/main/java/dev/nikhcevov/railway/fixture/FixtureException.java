package dev.nikhcevov.railway.fixture;

/** Recoverable fixture build/verify failure with a user-facing message. */
public final class FixtureException extends RuntimeException {
	public FixtureException(String message) {
		super(message);
	}
}
