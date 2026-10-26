package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.Failure;

/** Graph construction is all-or-nothing; a failure leaves no partial graph. */
public final class GraphBuildException extends Exception {
	private final Failure failure;

	public GraphBuildException(Failure failure) {
		super(failure.code() + (failure.location() == null ? "" : " at " + failure.location().shortString()));
		this.failure = failure;
	}

	public Failure failure() {
		return failure;
	}
}
