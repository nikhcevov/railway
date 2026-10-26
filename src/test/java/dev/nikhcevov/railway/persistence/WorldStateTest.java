package dev.nikhcevov.railway.persistence;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStateTest {
	private static final String DIMENSION = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	private static Station anchored(String id, String name, long revision) {
		return anchored(id, name, DIMENSION, revision);
	}

	private static Station anchored(String id, String name, String dimension, long revision) {
		return new Station(new StationId(id), name, dimension, new BlockLocation(0, 64, 0),
				new BlockLocation(1, 64, 0), 0.0F, true, revision);
	}

	private static Station provisional(String id, String name, long revision) {
		return Station.provisional(new StationId(id), name, DIMENSION, revision);
	}

	@Test
	void provisionalStationsArePrunedOnlyWithoutReferences() {
		WorldState state = new WorldState();
		Station abandoned = provisional("abandoned", "Abandoned", 1);
		Station destination = provisional("destination", "Destination", 2);
		Station markerReferenced = provisional("referenced", "Referenced", 3);
		Station anchored = anchored("anchored", "Anchored", 4);
		state.addStation(abandoned);
		state.addStation(destination);
		state.addStation(markerReferenced);
		state.addStation(anchored);
		Binding binding = new Binding(DIMENSION, new BlockLocation(5, 64, 5),
				"minecraft:stone_button", anchored.id(), destination.id());
		assertTrue(state.addBinding(binding));
		SignMarker reference = new SignMarker(DIMENSION, new BlockLocation(10, 65, 0),
				new BlockLocation(11, 65, 0), true, null, markerReferenced.id());
		assertTrue(state.addSignMarker(reference));
		long revisionBefore = state.revision();

		List<Station> pruned = state.pruneProvisionalStations();

		assertEquals(List.of(abandoned), pruned);
		assertEquals(revisionBefore + 1, state.revision());
		assertTrue(state.station(abandoned.id()).isEmpty());
		assertTrue(state.station(destination.id()).isPresent(), "a bound destination must remain");
		assertTrue(state.station(markerReferenced.id()).isPresent(), "a marker reference must remain");
		assertTrue(state.station(anchored.id()).isPresent(), "anchored stations are never pruned");

		assertTrue(state.pruneProvisionalStations().isEmpty());
		assertEquals(revisionBefore + 1, state.revision(), "an empty prune must not bump the revision");
	}

	@Test
	void markerAddRemoveAndButtonLookupFollowRevisionRules() {
		WorldState state = new WorldState();
		Station home = anchored("home", "Home", 1);
		state.addStation(home);
		BlockLocation sign = new BlockLocation(3, 65, 0);
		BlockLocation support = new BlockLocation(4, 65, 0);
		BlockLocation button = new BlockLocation(3, 66, 0);
		SignMarker marker = new SignMarker(DIMENSION, sign, support, true, button, home.id());
		long revisionBefore = state.revision();

		assertTrue(state.addSignMarker(marker));
		assertEquals(revisionBefore + 1, state.revision());
		assertFalse(state.addSignMarker(marker), "the identical marker is not re-added");
		assertEquals(revisionBefore + 1, state.revision());
		assertEquals(marker, state.signMarker(DIMENSION, sign).orElseThrow());
		assertEquals(marker, state.signMarkerForButton(DIMENSION, button).orElseThrow());

		SignMarker removed = state.removeSignMarkerForButton(DIMENSION, button).orElseThrow();

		assertEquals(marker, removed);
		assertTrue(state.signMarker(DIMENSION, sign).isEmpty());
		assertTrue(state.station(home.id()).isPresent(), "removing a marker must not delete station data");
		assertEquals(revisionBefore + 2, state.revision());
		assertTrue(state.removeSignMarker(DIMENSION, sign).isEmpty());
		assertEquals(revisionBefore + 2, state.revision());
	}

	@Test
	void duplicateNamesAndConflictingMarkerKeysAreRejected() {
		WorldState state = new WorldState();
		Station home = anchored("home", "Home", 1);
		Station mine = anchored("mine", "Mine", 2);
		state.addStation(home);
		state.addStation(mine);

		assertThrows(IllegalStateException.class, () -> state.addStation(anchored("other", "  home ", 3)));
		assertThrows(IllegalStateException.class,
				() -> state.updateStation(mine.withDisplayName("home", 4)));
		assertDoesNotThrow(() -> state.addStation(anchored("other", "Home", NETHER, 3)));
		assertEquals(1, state.stationsNamed(DIMENSION, "home").size());
		assertEquals(1, state.stationsNamed(NETHER, "home").size());

		BlockLocation sign = new BlockLocation(3, 65, 0);
		BlockLocation support = new BlockLocation(4, 65, 0);
		BlockLocation button = new BlockLocation(3, 66, 0);
		assertTrue(state.addSignMarker(new SignMarker(DIMENSION, sign, support, true, button, home.id())));
		assertThrows(IllegalStateException.class, () -> state.addSignMarker(
				new SignMarker(DIMENSION, sign, support, true, new BlockLocation(5, 66, 0), home.id())));
		assertThrows(IllegalStateException.class, () -> state.addSignMarker(
				new SignMarker(DIMENSION, new BlockLocation(8, 65, 0), new BlockLocation(9, 65, 0),
						true, button, home.id())));
		assertThrows(IllegalStateException.class, () -> state.addSignMarker(
				new SignMarker(DIMENSION, new BlockLocation(12, 65, 0), new BlockLocation(13, 65, 0),
						true, null, new StationId("ghost"))));
	}

	@Test
	void updateSignMarkerReplacesMarkerAndChecksOwnership() {
		WorldState state = new WorldState();
		Station home = anchored("home", "Home", 1);
		state.addStation(home);
		BlockLocation sign = new BlockLocation(3, 65, 0);
		BlockLocation support = new BlockLocation(4, 65, 0);
		BlockLocation button = new BlockLocation(3, 66, 0);
		SignMarker anchorOnly = new SignMarker(DIMENSION, sign, support, true, null, home.id());

		assertTrue(state.addSignMarker(anchorOnly));
		assertTrue(state.hasAnchorMarker(home.id()));
		assertFalse(state.hasAnchorMarker(new StationId("unknown")));
		long revisionBefore = state.revision();
		assertFalse(state.updateSignMarker(anchorOnly), "an identical update is a no-op");
		assertEquals(revisionBefore, state.revision());

		SignMarker destination = anchorOnly.withButton(button);
		assertTrue(state.updateSignMarker(destination));
		assertEquals(revisionBefore + 1, state.revision());
		assertEquals(destination, state.signMarker(DIMENSION, sign).orElseThrow());
		assertTrue(state.hasAnchorMarker(home.id()));

		assertThrows(IllegalStateException.class, () -> state.updateSignMarker(new SignMarker(DIMENSION,
				new BlockLocation(8, 65, 0), new BlockLocation(9, 65, 0), true, button, null)));
		assertThrows(IllegalStateException.class, () -> state.updateSignMarker(new SignMarker(DIMENSION,
				new BlockLocation(12, 65, 0), new BlockLocation(13, 65, 0), true, null,
				new StationId("ghost"))));
	}

	@Test
	void removingAStationClearsItsBindingsAndMarkers() {
		WorldState state = new WorldState();
		Station home = anchored("home", "Home", 1);
		Station mine = anchored("mine", "Mine", 2);
		state.addStation(home);
		state.addStation(mine);
		BlockLocation button = new BlockLocation(3, 66, 0);
		Binding binding = new Binding(DIMENSION, button, "minecraft:stone_button", home.id(), mine.id());
		assertTrue(state.addBinding(binding));
		assertTrue(state.addSignMarker(new SignMarker(DIMENSION, new BlockLocation(3, 65, 0),
				new BlockLocation(4, 65, 0), true, button, home.id())));

		List<Binding> removed = state.removeStation(home.id());

		assertEquals(List.of(binding), removed);
		assertTrue(state.bindings().isEmpty());
		assertTrue(state.signMarkers().isEmpty());
		assertTrue(state.station(mine.id()).isPresent());
	}

	@Test
	void provisionalStationsCannotBeEnabledOrGivenAnExit() {
		Station provisional = Station.provisional(new StationId("mine"), "Mine", DIMENSION, 1);

		assertTrue(provisional.isProvisional());
		assertFalse(provisional.isAnchored());
		assertThrows(IllegalStateException.class, () -> provisional.withEnabled(true, 2));
		assertThrows(IllegalStateException.class,
				() -> provisional.withExit(new BlockLocation(1, 64, 1), 0.0F, 2));
		assertThrows(IllegalArgumentException.class, () -> new Station(new StationId("bad"), "Bad", DIMENSION,
				null, new BlockLocation(1, 64, 1), 0.0F, false, 1));
		assertThrows(IllegalArgumentException.class, () -> new Station(new StationId("bad"), "Bad", DIMENSION,
				null, null, 0.0F, true, 1));
	}
}
