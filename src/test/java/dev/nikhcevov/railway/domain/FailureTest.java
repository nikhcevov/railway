package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTest {
	@Test
	void fromThrowableKeepsTypeWhenMessageIsMissing() {
		NullPointerException error = new NullPointerException();

		Failure failure = Failure.fromThrowable(FailureCode.INTERNAL, error);

		assertEquals("NullPointerException", failure.detail());
		assertSame(error, failure.cause());
		assertNull(failure.location());
	}

	@Test
	void fromThrowableKeepsMessageAndType() {
		IllegalStateException error = new IllegalStateException("boom");

		assertEquals("IllegalStateException: boom",
				Failure.fromThrowable(FailureCode.INTERNAL, error).detail());
	}

	@Test
	void describeHandlesNullAndAnonymousTypes() {
		assertEquals("unknown error", Failure.describe(null));
		assertEquals("IllegalArgumentException: bad", Failure.describe(new IllegalArgumentException("bad")));
	}

	@Test
	void factoriesLeaveTheCauseEmpty() {
		assertNull(Failure.at(FailureCode.NO_RAIL, new BlockLocation(1, 64, 1)).cause());
		assertNull(Failure.of(FailureCode.INTERNAL).cause());
	}

	@Test
	void everyFailureCarriesAStableRef() {
		Failure failure = Failure.of(FailureCode.NO_PATH);

		assertTrue(failure.ref().matches("[0-9a-f]{8}"), failure.ref());
		Failure explicit = new Failure(failure.code(), failure.location(), failure.detail(),
				failure.cause(), "deadbeef");
		assertEquals("deadbeef", explicit.ref());
	}
}
