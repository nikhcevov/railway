package dev.nikhcevov.railway.domain;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A failure with an optional offending block, an optional detail string
 * (station id, block id, numeric limit...), an optional originating exception,
 * and a short reference id.
 *
 * <p>The cause is never serialized: it exists so logs can print one stack trace
 * with the failure that was reported, and so an {@code INTERNAL} failure can
 * still carry a non-empty detail when an exception has no message.
 *
 * <p>The ref is generated once per failure and is the same in the log line, the
 * player-facing message, and the failure journal.
 */
public record Failure(FailureCode code, BlockLocation location, String detail, Throwable cause, String ref) {
	public Failure {
		Objects.requireNonNull(code, "code");
		if (ref == null || ref.isBlank()) {
			ref = newRef();
		}
	}

	/** Failure without an originating exception. */
	public Failure(FailureCode code, BlockLocation location, String detail) {
		this(code, location, detail, null, null);
	}

	/** Failure with an originating exception but a fresh ref. */
	public Failure(FailureCode code, BlockLocation location, String detail, Throwable cause) {
		this(code, location, detail, cause, null);
	}

	public static Failure of(FailureCode code) {
		return new Failure(code, null, null);
	}

	public static Failure at(FailureCode code, BlockLocation location) {
		return new Failure(code, location, null);
	}

	public static Failure detail(FailureCode code, String detail) {
		return new Failure(code, null, detail);
	}

	public static Failure at(FailureCode code, BlockLocation location, String detail) {
		return new Failure(code, location, detail);
	}

	/**
	 * Failure for an unexpected exception. The detail is never blank, so a
	 * {@code NullPointerException} without a message still identifies its type.
	 */
	public static Failure fromThrowable(FailureCode code, Throwable error) {
		return new Failure(code, null, describe(error), error);
	}

	/** Non-blank description of an exception suitable for a detail field. */
	public static String describe(Throwable error) {
		if (error == null) {
			return "unknown error";
		}
		String type = error.getClass().getSimpleName();
		if (type.isEmpty()) {
			type = error.getClass().getName();
		}
		String message = error.getMessage();
		return message == null || message.isBlank() ? type : type + ": " + message;
	}

	private static String newRef() {
		return String.format("%08x", ThreadLocalRandom.current().nextInt());
	}
}
