package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignMarkerTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final BlockLocation SIGN = new BlockLocation(1, 65, 2);
	private static final BlockLocation SUPPORT = new BlockLocation(1, 65, 3);
	private static final BlockLocation BUTTON = new BlockLocation(1, 66, 2);

	@Test
	void validatesPositionsAndIdentityFields() {
		assertThrows(NullPointerException.class,
				() -> new SignMarker(null, SIGN, SUPPORT, true, BUTTON, null));
		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker("  ", SIGN, SUPPORT, true, BUTTON, null));
		assertThrows(NullPointerException.class,
				() -> new SignMarker(DIMENSION, null, SUPPORT, true, BUTTON, null));
		assertThrows(NullPointerException.class,
				() -> new SignMarker(DIMENSION, SIGN, null, true, BUTTON, null));
		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker(DIMENSION, SIGN, SIGN, true, BUTTON, null));
		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker(DIMENSION, SIGN, SUPPORT, true, SIGN, null));
		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker(DIMENSION, SIGN, SUPPORT, true, SUPPORT, null));
		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker(DIMENSION, SIGN, SUPPORT, true, null, null));
	}

	@Test
	void oneMarkerCanBeAnchorAndDestination() {
		StationId anchor = new StationId("home");
		SignMarker destination = new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, null);
		SignMarker stationOnly = new SignMarker(DIMENSION, SIGN, SUPPORT, false, null, anchor);
		SignMarker both = new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, anchor);

		assertFalse(destination.isAnchor());
		assertTrue(destination.isDestination());
		assertTrue(stationOnly.isAnchor());
		assertFalse(stationOnly.isDestination());
		assertTrue(both.isAnchor());
		assertTrue(both.isDestination());
		assertTrue(both.hasButton());
	}
}
