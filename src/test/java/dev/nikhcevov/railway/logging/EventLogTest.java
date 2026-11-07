package dev.nikhcevov.railway.logging;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.StationId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLogTest {
	@Test
	void writesStableKeyValueLineWithFailureAndRef() {
		String line = EventLog.event("scan.fail")
				.tick(42)
				.scan("scan-7")
				.station(new StationId("alpha"), "Магнитогорск")
				.revision(9)
				.failure(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY,
						new BlockLocation(-237, 66, 203), "four-way or ambiguous crossing"))
				.line();

		assertTrue(line.startsWith("event=scan.fail tick=42 scan=scan-7 station=alpha "
						+ "name=Магнитогорск rev=9 code=UNSUPPORTED_GEOMETRY "
						+ "pos=\"-237 66 203\" detail=\"four-way or ambiguous crossing\" ref="),
				line);
		assertTrue(line.matches(".* ref=[0-9a-f]{8}$"), line);
	}

	@Test
	void failureWithoutDetailUsesReadableCode() {
		String line = EventLog.event("trip.reject").failure(Failure.of(FailureCode.NO_PATH)).line();

		assertTrue(line.contains(" code=NO_PATH"), line);
		assertTrue(line.contains(" detail=\"no path\""), line);
		assertFalse(line.contains("pos="), line);
	}

	@Test
	void reusesTheFailureRefAcrossEvents() {
		Failure failure = Failure.of(FailureCode.NO_PATH);

		String reject = EventLog.event("trip.reject").failure(failure).line();
		String abort = EventLog.event("trip.abort").failure(failure).line();

		assertTrue(reject.endsWith(" ref=" + failure.ref()), reject);
		assertTrue(abort.contains(" ref=" + failure.ref()), abort);
	}

	@Test
	void quotesEscapeControlCharactersAndQuotes() {
		String line = EventLog.event("sign.reject")
				.failure(Failure.detail(FailureCode.SIGN_GEOMETRY, "line one\nline \"two\""))
				.line();

		assertTrue(line.contains("detail=\"line one\\nline \\\"two\\\"\""), line);
	}

	@Test
	void truncatesLongValuesToKeepOneEventPerLine() {
		String detail = "x".repeat(400);
		String line = EventLog.event("x").failure(Failure.detail(FailureCode.INTERNAL, detail)).line();

		assertTrue(line.contains("x".repeat(200) + "...\""), line);
		assertFalse(line.contains("x".repeat(201)), line);
	}

	@Test
	void emptyAndNullValuesAreSkipped() {
		String line = EventLog.event("x").field("a", (String) null).field("b", "").field("c", "v").line();

		assertEquals("event=x c=v", line);
		assertNull(EventLog.event("x").failure(null).failure());
	}
}
