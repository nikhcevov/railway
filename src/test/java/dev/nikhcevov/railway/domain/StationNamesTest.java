package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationNamesTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	private static Station station(String id, String name, String dimension) {
		return new Station(new StationId(id), name, dimension, new BlockLocation(0, 64, 0),
				new BlockLocation(1, 64, 0), 0.0F, true, 1);
	}

	@Test
	void normalizeTrimsAndCollapsesWhitespace() {
		assertEquals("Home Mine", StationNames.normalize("  Home \t\n  Mine  "));
		assertEquals("Home Mine", StationNames.normalize("Home\u00a0Mine"));
		assertEquals("", StationNames.normalize("   "));
		assertEquals("", StationNames.normalize(null));
		assertTrue(StationNames.isValid(" Home "));
		assertFalse(StationNames.isValid(" \t "));
	}

	@Test
	void lookupKeyIsCaseInsensitiveAndDimensionScoped() {
		assertEquals(StationNames.lookupKey(OVERWORLD, " Home   Mine "),
				StationNames.lookupKey(OVERWORLD, "home mine"));
		assertNotEquals(StationNames.lookupKey(OVERWORLD, "Home"),
				StationNames.lookupKey(NETHER, "Home"));
		assertThrows(IllegalArgumentException.class, () -> StationNames.lookupKey(OVERWORLD, "  "));
	}

	@Test
	void findDetectsAmbiguityAndKeepsFirstDisplaySpelling() {
		Station first = station("first", "Home", OVERWORLD);
		Station duplicate = station("duplicate", "  home ", OVERWORLD);
		Station otherDimension = station("other", "Home", NETHER);
		List<Station> stations = List.of(first, duplicate, otherDimension);

		assertEquals(List.of(first, duplicate), StationNames.find(stations, OVERWORLD, "hOmE"));
		assertTrue(StationNames.isAmbiguous(stations, OVERWORLD, "home"));
		assertFalse(StationNames.isAvailable(stations, OVERWORLD, "home"));
		assertTrue(StationNames.unique(stations, OVERWORLD, "home").isEmpty());
		assertEquals("Home", StationNames.displaySpelling(stations, OVERWORLD, "HOME").orElseThrow());
		assertEquals(List.of(otherDimension), StationNames.find(stations, NETHER, "home"));
		assertTrue(StationNames.find(stations, OVERWORLD, "Mine").isEmpty());
		assertTrue(StationNames.isAvailable(stations, OVERWORLD, "Mine"));
	}

	@Test
	void generatedIdIsDeterministicHiddenAndCollisionSafe() {
		StationId id = StationNames.generatedId(OVERWORLD, "Home Mine");
		assertEquals(id, StationNames.generatedId(OVERWORLD, "  home   MINE "));
		assertTrue(StationId.isValid(id.value()));
		assertTrue(id.value().matches("[a-z0-9_-]{1,32}"));
		assertNotEquals(id, StationNames.generatedId(OVERWORLD, "Home"));
		assertNotEquals(id, StationNames.generatedId(NETHER, "Home Mine"));

		StationId base = StationNames.generatedId(OVERWORLD, "Home");
		StationId collided = StationNames.generatedId(OVERWORLD, "Home",
				List.of(station(base.value(), "Other", OVERWORLD)));
		assertNotEquals(base, collided, "a different identity holding the id must push to a suffix");
		assertTrue(StationId.isValid(collided.value()));
		assertEquals(base, StationNames.generatedId(OVERWORLD, "home",
				List.of(station(base.value(), "Home", OVERWORLD))),
				"regenerating for the same identity must return the same id");

		StationId skipped = StationNames.generatedId(OVERWORLD, "Home", candidate -> candidate.equals(base));
		assertNotEquals(base, skipped);
	}

	@Test
	void blankNamesCannotProduceLookupKeysOrIds() {
		assertThrows(IllegalArgumentException.class, () -> StationNames.generatedId(OVERWORLD, " "));
		assertThrows(IllegalArgumentException.class,
				() -> StationNames.generatedId(NETHER, "", List.of()));
	}
}
