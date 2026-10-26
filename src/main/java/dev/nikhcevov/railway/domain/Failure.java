package dev.nikhcevov.railway.domain;

import java.util.Objects;

/**
 * A failure with an optional offending block and an optional detail string
 * (station id, block id, numeric limit...).
 */
public record Failure(FailureCode code, BlockLocation location, String detail) {
	public Failure {
		Objects.requireNonNull(code, "code");
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
}
