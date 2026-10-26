package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

	@Test
	void compatibilityConstructorDefaultsStationAndMain() {
		StationId anchor = new StationId("home");
		SignMarker destination = new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, null);
		SignMarker anchored = new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, anchor);

		assertNull(destination.stationId());
		assertNull(destination.ownerStationId());
		assertFalse(destination.main());
		assertEquals(anchor, anchored.stationId());
		assertEquals(anchor, anchored.ownerStationId());
		assertFalse(anchored.main());
	}

	@Test
	void validatesStationOwnershipAndMainFlags() {
		StationId anchor = new StationId("home");
		StationId other = new StationId("other");

		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, anchor, other, false));
		assertThrows(IllegalArgumentException.class,
				() -> new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, null, null, true));

		SignMarker main = new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, anchor, anchor, true);
		assertEquals(anchor, main.ownerStationId());
		assertTrue(main.main());
	}

	@Test
	void allowsStationOwnedButtonlessMarkers() {
		StationId owner = new StationId("home");
		SignMarker main = new SignMarker(DIMENSION, SIGN, SUPPORT, true, null, null, owner, true);
		SignMarker notMain = new SignMarker(DIMENSION, SIGN, SUPPORT, true, null, null, owner, false);

		assertFalse(main.isAnchor());
		assertFalse(main.isDestination());
		assertFalse(main.hasButton());
		assertEquals(owner, main.stationId());
		assertEquals(owner, main.ownerStationId());
		assertNull(main.anchorStationId());
		assertTrue(main.main());
		assertFalse(notMain.main());
		assertEquals(main.ownerStationId(), notMain.ownerStationId());
		assertEquals(main, notMain.withMain(true));
		assertNull(notMain.withMain(false).button());
	}

	@Test
	void withHelpersPreserveOwnershipAndMain() {
		StationId anchor = new StationId("home");
		SignMarker main = new SignMarker(DIMENSION, SIGN, SUPPORT, true, BUTTON, anchor, anchor, true);

		SignMarker edited = main.withEditedFront(false);
		assertEquals(main.stationId(), edited.stationId());
		assertEquals(main.anchorStationId(), edited.anchorStationId());
		assertTrue(edited.main());
		assertEquals(main, edited.withEditedFront(true));

		SignMarker rebuttoned = main.withButton(new BlockLocation(2, 66, 2));
		assertEquals(main.stationId(), rebuttoned.stationId());
		assertTrue(rebuttoned.main());
		assertEquals(new BlockLocation(2, 66, 2), rebuttoned.button());
		assertEquals(anchor, main.withStationId(anchor).stationId());
		assertTrue(main.withAnchorStationId(anchor).main());
		assertFalse(main.withMain(false).main());
		assertTrue(main.withMain(false).isAnchor());
	}
}
