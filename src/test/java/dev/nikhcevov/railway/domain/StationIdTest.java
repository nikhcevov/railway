package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationIdTest {
	private static final String ID_32 = "abcdefghijklmnopqrstuvwxyz012345";
	private static final String ID_33 = "abcdefghijklmnopqrstuvwxyz0123456";

	@Test
	void acceptsValidIdentifiers() {
		assertEquals(ID_32.length(), 32);
		assertEquals("home", new StationId("home").value());
		assertEquals("mine_2", new StationId("mine_2").value());
		assertEquals(ID_32, new StationId(ID_32).value());
		assertEquals("a", new StationId("a").value(), "single character is the lower bound");
		assertEquals("a-b_c9", new StationId("a-b_c9").value(), "hyphen and underscore are allowed");
	}

	@Test
	void rejectsUppercaseCharacters() {
		assertThrows(IllegalArgumentException.class, () -> new StationId("Home"));
		assertThrows(IllegalArgumentException.class, () -> new StationId("home Station"));
	}

	@Test
	void rejectsEmptyIdentifier() {
		assertThrows(IllegalArgumentException.class, () -> new StationId(""));
	}

	@Test
	void rejectsIdentifiersLongerThan32Characters() {
		assertEquals(ID_33.length(), 33);
		assertThrows(IllegalArgumentException.class, () -> new StationId(ID_33));
	}

	@Test
	void rejectsWhitespace() {
		assertThrows(IllegalArgumentException.class, () -> new StationId("my station"));
		assertThrows(IllegalArgumentException.class, () -> new StationId(" home"));
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new StationId(null));
	}

	@Test
	void isValidMatchesConstructionRules() {
		assertTrue(StationId.isValid("home"));
		assertTrue(StationId.isValid("mine_2"));
		assertTrue(StationId.isValid(ID_32));
		assertTrue(StationId.isValid("a-b_c9"));

		assertFalse(StationId.isValid(null), "null is not a valid id");
		assertFalse(StationId.isValid(""));
		assertFalse(StationId.isValid("Home"));
		assertFalse(StationId.isValid(ID_33));
		assertFalse(StationId.isValid("my station"));
	}
}
