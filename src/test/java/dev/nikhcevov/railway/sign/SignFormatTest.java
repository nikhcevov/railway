package dev.nikhcevov.railway.sign;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignFormatTest {
	private static final String MARKER = "[Railway]";

	@Test
	void parsesDestinationSignAndNormalizesNames() {
		SignFormat.Result result = SignFormat.parse(List.of(" [Railway] ", "  Home   Base ", "\tMine  Station \n"));

		SignFormat.Parsed parsed = assertInstanceOf(SignFormat.Parsed.class, result);
		assertEquals("Home Base", parsed.from());
		assertEquals("Mine Station", parsed.to());
		assertFalse(parsed.stationOnly());
		assertFalse(parsed.main());
	}

	@Test
	void acceptsMarkerCaseInsensitively() {
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse("[railway]", "Home", "Mine"));

		assertEquals("Home", parsed.from());
		assertEquals("Mine", parsed.to());
	}

	@Test
	void blankLineThreeIsAStationOnlyAnchor() {
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(MARKER, "Home", "   "));

		assertEquals("Home", parsed.from());
		assertEquals("", parsed.to());
		assertTrue(parsed.stationOnly());
	}

	@Test
	void nullLineThreeIsAlsoAStationOnlyAnchor() {
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(MARKER, "Home", null));

		assertTrue(parsed.stationOnly());
		assertEquals("", parsed.to());
	}

	@Test
	void threeArgumentOverloadMatchesTheListOverload() {
		assertEquals(
				SignFormat.parse(List.of(MARKER, "Home", "Mine")),
				SignFormat.parse(MARKER, "Home", "Mine"));
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(MARKER, "Home", "Mine"));
		assertFalse(parsed.main());
	}

	@Test
	void fourLineSignWithBlankFlagsHasNoMainFlag() {
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(List.of(MARKER, "Home", "Mine", "   ")));

		assertEquals("Home", parsed.from());
		assertEquals("Mine", parsed.to());
		assertFalse(parsed.main());
		assertFalse(parsed.stationOnly());
	}

	@Test
	void parsesMainFlagCaseInsensitively() {
		SignFormat.Parsed lower = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(List.of(MARKER, "Home", "Mine", "m")));
		SignFormat.Parsed upper = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(List.of(MARKER, "Home", "Mine", " \tM \n")));

		assertTrue(lower.main());
		assertTrue(upper.main());
		assertEquals("Mine", upper.to());
	}

	@Test
	void fourArgumentOverloadMatchesTheListOverload() {
		assertEquals(
				SignFormat.parse(List.of(MARKER, "Home", "Mine", "m")),
				SignFormat.parse(MARKER, "Home", "Mine", "m"));
		assertEquals(
				SignFormat.parse(List.of(MARKER, "Home", "Mine", "")),
				SignFormat.parse(MARKER, "Home", "Mine", null));
	}

	@Test
	void stationOnlySignCanCarryMainFlag() {
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(MARKER, "Home", "   ", "m"));

		assertTrue(parsed.stationOnly());
		assertTrue(parsed.main());
	}

	@Test
	void lineThreeMIsADestinationNameNotAFlag() {
		SignFormat.Parsed parsed = assertInstanceOf(
				SignFormat.Parsed.class,
				SignFormat.parse(List.of(MARKER, "Home", "m")));

		assertEquals("m", parsed.to());
		assertFalse(parsed.main());
	}

	@Test
	void rejectsUnknownFlags() {
		SignFormat.Rejected rejected = rejected(SignFormat.parse(MARKER, "Home", "Mine", "x"));
		assertEquals(SignFormat.Error.INVALID_FLAGS, rejected.error());
		assertTrue(rejected.detail().contains("x"));

		assertEquals(SignFormat.Error.INVALID_FLAGS,
				error(SignFormat.parse(List.of(MARKER, "Home", "Mine", "main"))));
	}

	@Test
	void rejectsMultipleFlags() {
		assertEquals(SignFormat.Error.INVALID_FLAGS,
				error(SignFormat.parse(MARKER, "Home", "Mine", "m m")));
		assertEquals(SignFormat.Error.INVALID_FLAGS,
				error(SignFormat.parse(List.of(MARKER, "Home", "Mine", "m x"))));
	}

	@Test
	void fourLineSignsStillValidateMarkerAndNames() {
		assertEquals(SignFormat.Error.UNSUPPORTED_MARKER,
				error(SignFormat.parse("[station]", "Home", "Mine", "m")));
		assertEquals(SignFormat.Error.INVALID_MARKER,
				error(SignFormat.parse("Railway", "Home", "Mine", "m")));
		assertEquals(SignFormat.Error.MISSING_FROM,
				error(SignFormat.parse(MARKER, "   ", "Mine", "m")));
	}

	@Test
	void compatibilityConstructorDefaultsMainToFalse() {
		SignFormat.Parsed parsed = new SignFormat.Parsed("Home", "Mine");

		assertFalse(parsed.main());
		assertFalse(parsed.stationOnly());
	}

	@Test
	void rejectsRetiredMarkers() {
		assertEquals(SignFormat.Error.UNSUPPORTED_MARKER,
				error(SignFormat.parse("[station]", "Home", "Mine")));
		assertEquals(SignFormat.Error.UNSUPPORTED_MARKER,
				error(SignFormat.parse("[GO]", "Home", "Mine")));
	}

	@Test
	void rejectsLegacyOrUnknownMarkerText() {
		assertEquals(SignFormat.Error.INVALID_MARKER,
				error(SignFormat.parse("[Railway] Home", "Mine", "")));
		assertEquals(SignFormat.Error.INVALID_MARKER,
				error(SignFormat.parse("Railway", "Home", "Mine")));
	}

	@Test
	void rejectsBlankMarker() {
		assertEquals(SignFormat.Error.MISSING_MARKER,
				error(SignFormat.parse("   ", "Home", "Mine")));
		assertEquals(SignFormat.Error.MISSING_MARKER,
				error(SignFormat.parse(null, "Home", "Mine")));
	}

	@Test
	void rejectsMissingOrBlankFromName() {
		assertEquals(SignFormat.Error.MISSING_FROM,
				error(SignFormat.parse(MARKER, "   ", "Mine")));
		assertEquals(SignFormat.Error.MISSING_FROM,
				error(SignFormat.parse(MARKER, null, "Mine")));
	}

	@Test
	void rejectsWrongLineCount() {
		assertEquals(SignFormat.Error.LINE_COUNT,
				error(SignFormat.parse(List.of(MARKER, "Home"))));
		assertEquals(SignFormat.Error.LINE_COUNT,
				error(SignFormat.parse(List.of(MARKER, "Home", "Mine", "m", "Extra"))));
		assertEquals(SignFormat.Error.LINE_COUNT,
				error(SignFormat.parse(List.of())));
		assertEquals(SignFormat.Error.LINE_COUNT, error(SignFormat.parse((List<String>) null)));
	}

	@Test
	void normalizesOnlyWhitespaceAndPreservesDisplayCase() {
		assertEquals("Home Base", SignFormat.normalize("  Home \t\n Base "));
		assertEquals("HoMe", SignFormat.normalize("HoMe"));
		assertEquals("", SignFormat.normalize(null));
	}

	@Test
	void recognizesOnlyKnownMarkerLinesAsServiceIntent() {
		assertTrue(SignFormat.isKnownMarkerLine("[Railway]"));
		assertTrue(SignFormat.isKnownMarkerLine(" [railway] "));
		assertTrue(SignFormat.isKnownMarkerLine("[station]"));
		assertTrue(SignFormat.isKnownMarkerLine("[GO]"));
		assertFalse(SignFormat.isKnownMarkerLine("Welcome"));
		assertFalse(SignFormat.isKnownMarkerLine("[Railway] Home"));
		assertFalse(SignFormat.isKnownMarkerLine(""));
	}

	private static SignFormat.Rejected rejected(SignFormat.Result result) {
		return assertInstanceOf(SignFormat.Rejected.class, result);
	}

	private static SignFormat.Error error(SignFormat.Result result) {
		return rejected(result).error();
	}
}
