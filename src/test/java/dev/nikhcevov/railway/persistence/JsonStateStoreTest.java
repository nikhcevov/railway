package dev.nikhcevov.railway.persistence;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStateStoreTest {
	private static final String DIMENSION = "minecraft:overworld";

	private static final String ALPHA_JSON = """
			{
			  "id": {"value": "alpha"},
			  "displayName": "Alpha",
			  "dimension": "minecraft:overworld",
			  "dock": {"x": 0, "y": 64, "z": 0},
			  "exit": {"x": 1, "y": 64, "z": 0},
			  "exitYaw": 90.0,
			  "enabled": true,
			  "revision": 1
			}
			""";

	private static final String BETA_JSON = """
			{
			  "id": {"value": "beta"},
			  "displayName": "Beta",
			  "dimension": "minecraft:overworld",
			  "dock": {"x": 10, "y": 64, "z": 0},
			  "exit": {"x": 11, "y": 64, "z": 0},
			  "exitYaw": 180.0,
			  "enabled": false,
			  "revision": 2
			}
			""";

	private static final String PROVISIONAL_JSON = """
			{
			  "id": {"value": "mine"},
			  "displayName": "Mine",
			  "dimension": "minecraft:overworld",
			  "dock": null,
			  "exit": null,
			  "exitYaw": 0.0,
			  "enabled": false,
			  "revision": 3
			}
			""";

	private static final String BINDING_JSON = """
			{
			  "dimension": "minecraft:overworld",
			  "button": {"x": 5, "y": 64, "z": 5},
			  "expectedButtonBlockId": "minecraft:stone_button",
			  "fromStationId": {"value": "alpha"},
			  "toStationId": {"value": "beta"}
			}
			""";

	@TempDir
	Path tempDir;

	private Path stateFile() {
		return tempDir.resolve("railway").resolve("state.json");
	}

	private void writeState(String json) throws IOException {
		Files.createDirectories(stateFile().getParent());
		Files.writeString(stateFile(), json, StandardCharsets.UTF_8);
	}

	private static Station station(String id, long revision) {
		return new Station(new StationId(id), "Station " + id, DIMENSION,
				new BlockLocation(0, 64, 0), new BlockLocation(1, 64, 0), 90.0f, true, revision);
	}

	private static Station provisional(String id, long revision) {
		return Station.provisional(new StationId(id), "Station " + id, DIMENSION, revision);
	}

	private static String markerJson(String dimension, int signX, int buttonX, String anchorId) {
		String anchor = anchorId == null ? "null" : "{\"value\":\"" + anchorId + "\"}";
		return """
				{"dimension":"%s","sign":{"x":%d,"y":65,"z":0},"support":{"x":%d,"y":65,"z":1},\
				"editedFront":true,"button":{"x":%d,"y":65,"z":2},"anchorStationId":%s}
				""".formatted(dimension, signX, signX, buttonX, anchor).strip();
	}

	private static String ownedMarkerJson(String dimension, int signX, int buttonX,
			String anchorId, String stationId, boolean main) {
		String anchor = anchorId == null ? "null" : "{\"value\":\"" + anchorId + "\"}";
		String owner = stationId == null ? "null" : "{\"value\":\"" + stationId + "\"}";
		return """
				{"dimension":"%s","sign":{"x":%d,"y":65,"z":0},"support":{"x":%d,"y":65,"z":1},\
				"editedFront":true,"button":{"x":%d,"y":65,"z":2},"anchorStationId":%s,\
				"stationId":%s,"main":%b}
				""".formatted(dimension, signX, signX, buttonX, anchor, owner, main).strip();
	}

	private static Binding binding(String from, String to, BlockLocation button) {
		return new Binding(DIMENSION, button, "minecraft:stone_button",
				new StationId(from), new StationId(to));
	}

	private static UUID deterministicUuid(String prefix, int discriminator) {
		return UUID.nameUUIDFromBytes((prefix + '-' + discriminator).getBytes(StandardCharsets.UTF_8));
	}

	private static TripRecord trip(String from, String to, TripPhase phase, long startedTick, int discriminator) {
		return new TripRecord(
				new TripId(deterministicUuid("trip", discriminator)),
				deterministicUuid("player", discriminator),
				deterministicUuid("cart", discriminator),
				new StationId(from), new StationId(to), phase, startedTick);
	}

	@Test
	void absentFileLoadsAnEmptyStateWithoutCreatingIt() throws IOException, StateStore.StateException {
		JsonStateStore store = new JsonStateStore(tempDir);

		WorldState state = store.load();

		assertEquals(0, state.revision());
		assertTrue(state.stations().isEmpty());
		assertTrue(state.bindings().isEmpty());
		assertTrue(state.trips().isEmpty());
		assertFalse(Files.exists(store.file()), "load must not create a state file for a fresh world");
	}

	@Test
	void saveAndLoadRoundtripPreservesStationsBindingsTripsAndRevision()
			throws IOException, StateStore.StateException {
		Station alpha = station("alpha", 5);
		Station beta = station("beta", 6);
		Binding binding = binding("alpha", "beta", new BlockLocation(5, 64, 5));
		TripRecord trip = trip("alpha", "beta", TripPhase.RUNNING, 1234, 1);

		WorldState original = new WorldState();
		original.addStation(alpha);
		original.addStation(beta);
		assertTrue(original.addBinding(binding));
		original.addTrip(trip);

		JsonStateStore store = new JsonStateStore(tempDir);
		store.save(original);
		WorldState loaded = new JsonStateStore(tempDir).load();

		assertEquals(2, loaded.stations().size());
		assertEquals(alpha, loaded.station(alpha.id()).orElseThrow());
		assertEquals(beta, loaded.station(beta.id()).orElseThrow());
		assertEquals(List.of(binding), List.copyOf(loaded.bindings()));
		assertEquals(List.of(trip), loaded.trips());
		assertEquals(original.revision(), loaded.revision(), "revision must survive a save/load roundtrip");
	}

	@Test
	void terminalTripRecordsAreDroppedOnLoad() throws IOException, StateStore.StateException {
		WorldState state = new WorldState();
		state.addStation(station("alpha", 1));
		state.addStation(station("beta", 2));
		TripRecord active = trip("alpha", "beta", TripPhase.RUNNING, 100, 1);
		TripRecord completed = trip("alpha", "beta", TripPhase.COMPLETED, 200, 2);
		TripRecord aborted = trip("alpha", "beta", TripPhase.ABORTED, 300, 3);
		state.setTrips(List.of(active, completed, aborted));

		new JsonStateStore(tempDir).save(state);
		WorldState loaded = new JsonStateStore(tempDir).load();

		assertEquals(List.of(active), loaded.trips(), "only non-terminal checkpoints are restored");
	}

	@Test
	void duplicateStationIdInFileIsRejected() throws IOException {
		writeState("""
				{"schemaVersion": 1, "revision": 2, "stations": [%s, %s], "bindings": [], "trips": []}
				""".formatted(ALPHA_JSON, ALPHA_JSON));

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("duplicate station id"), error::getMessage);
	}

	@Test
	void bindingToUnknownStationIsRejected() throws IOException {
		writeState("""
				{"schemaVersion": 1, "revision": 2, "stations": [%s], "bindings": [%s], "trips": []}
				""".formatted(ALPHA_JSON, BINDING_JSON));

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("unknown station"), error::getMessage);
	}

	@Test
	void duplicateButtonBindingInFileIsRejected() throws IOException {
		writeState("""
				{"schemaVersion": 1, "revision": 2, "stations": [%s, %s], "bindings": [%s, %s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, BINDING_JSON, BINDING_JSON));

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("duplicate binding"), error::getMessage);
	}

	@Test
	void tripCheckpointReferencingUnknownStationIsRejected() throws IOException {
		String tripJson = """
				{
				  "id": {"value": "00000000-0000-0000-0000-000000000001"},
				  "playerUuid": "00000000-0000-0000-0000-0000000000a1",
				  "cartUuid": "00000000-0000-0000-0000-0000000000c1",
				  "from": {"value": "alpha"},
				  "to": {"value": "ghost"},
				  "phase": "RUNNING",
				  "startedTick": 10
				}
				""";
		writeState("""
				{"schemaVersion": 1, "revision": 2, "stations": [%s], "bindings": [], "trips": [%s]}
				""".formatted(ALPHA_JSON, tripJson));

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("unknown station"), error::getMessage);
	}

	@Test
	void futureSchemaIsRejectedAndFileIsLeftUntouched() throws IOException {
		Path file = stateFile();
		writeState("""
				{"schemaVersion": 4, "revision": 7, "stations": [], "bindings": [], "trips": []}
				""");
		byte[] original = Files.readAllBytes(file);

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("newer than supported"), error::getMessage);
		assertArrayEquals(original, Files.readAllBytes(file));
		try (var files = Files.list(tempDir.resolve("railway"))) {
			assertEquals(List.of("state.json"),
					files.map(path -> path.getFileName().toString()).sorted().toList(),
					"a rejected future schema must not create backups or rewrites");
		}
	}

	@Test
	void corruptJsonIsRejected() throws IOException {
		Path file = stateFile();
		writeState("{\"schemaVersion\": 1, \"revision\": ");
		byte[] original = Files.readAllBytes(file);

		assertThrows(StateStore.StateException.class, () -> new JsonStateStore(tempDir).load());
		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	void saveCreatesTheStateFileUnderRailwayDirectory() throws IOException {
		Path worldRoot = tempDir.resolve("world");
		JsonStateStore store = new JsonStateStore(worldRoot);

		store.save(new WorldState());

		Path expected = worldRoot.resolve("railway").resolve("state.json");
		assertEquals(expected, store.file());
		assertTrue(Files.isRegularFile(expected));
		String json = Files.readString(expected, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"schemaVersion\": 3"), json);
	}

	@Test
	void removingAStationRemovesItsBindingsAndBumpsRevision() {
		WorldState state = new WorldState();
		Station alpha = station("alpha", 1);
		Station beta = station("beta", 2);
		state.addStation(alpha);
		state.addStation(beta);
		Binding binding = binding("alpha", "beta", new BlockLocation(5, 64, 5));
		assertTrue(state.addBinding(binding));
		long revisionBefore = state.revision();

		List<Binding> removed = state.removeStation(alpha.id());

		assertEquals(List.of(binding), removed);
		assertTrue(state.station(alpha.id()).isEmpty());
		assertTrue(state.bindings().isEmpty());
		assertEquals(revisionBefore + 1, state.revision());
	}

	@Test
	void duplicateBindingIsRefusedWithoutBumpingRevision() {
		WorldState state = new WorldState();
		state.addStation(station("alpha", 1));
		state.addStation(station("beta", 2));
		BlockLocation button = new BlockLocation(5, 64, 5);
		Binding first = binding("alpha", "beta", button);
		assertTrue(state.addBinding(first));
		long revisionBefore = state.revision();

		boolean added = state.addBinding(binding("alpha", "alpha", button));

		assertFalse(added);
		assertEquals(revisionBefore, state.revision(), "a refused binding must not bump the revision");
		assertEquals(List.of(first), state.bindings(), "the existing binding must not be replaced");
	}

	@Test
	void versionOneStateIsBackedUpAndMigratedToVersionThree() throws IOException, StateStore.StateException {
		String original = """
				{"schemaVersion": 1, "revision": 2, "stations": [%s, %s], "bindings": [%s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, BINDING_JSON);
		writeState(original);
		JsonStateStore store = new JsonStateStore(tempDir);

		WorldState loaded = store.load();

		assertEquals(2, loaded.stations().size());
		assertEquals(List.of(binding("alpha", "beta", new BlockLocation(5, 64, 5))),
				List.copyOf(loaded.bindings()));
		assertNull(loaded.bindings().iterator().next().sourceDock(),
				"v1 bindings have no local source dock");
		assertTrue(loaded.signMarkers().isEmpty());
		Path backup = tempDir.resolve("railway").resolve("state.v1.bak");
		assertTrue(Files.isRegularFile(backup), "migrating v1 must keep the rolling backup");
		assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));

		store.save(loaded);
		String saved = Files.readString(store.file(), StandardCharsets.UTF_8);
		assertTrue(saved.contains("\"schemaVersion\": 3"), saved);
		assertTrue(saved.contains("\"signMarkers\": []"), saved);
		assertFalse(saved.contains("\"sourceDock\""), "a null source dock is omitted, not serialized");
		WorldState reloaded = new JsonStateStore(tempDir).load();
		assertEquals(1, reloaded.bindings().size());
		assertNull(reloaded.bindings().iterator().next().sourceDock());
		assertTrue(reloaded.signMarkers().isEmpty());
	}

	@Test
	void versionOneDuplicateDisplayNamesAreRenamedInsteadOfDisablingState()
			throws IOException, StateStore.StateException {
		String duplicateBeta = BETA_JSON.replace("\"Beta\"", "\"Alpha\"");
		writeState("""
				{"schemaVersion": 1, "revision": 2, "stations": [%s, %s], "bindings": [%s], "trips": []}
				""".formatted(ALPHA_JSON, duplicateBeta, BINDING_JSON));

		JsonStateStore store = new JsonStateStore(tempDir);
		WorldState loaded = store.load();

		assertEquals(2, loaded.stations().size());
		assertEquals("Alpha", loaded.station(new StationId("alpha")).orElseThrow().displayName());
		assertEquals("Alpha (2)", loaded.station(new StationId("beta")).orElseThrow().displayName());
		assertEquals(List.of(binding("alpha", "beta", new BlockLocation(5, 64, 5))),
				List.copyOf(loaded.bindings()), "renaming a legacy duplicate must keep bindings");

		store.save(loaded);
		WorldState reloaded = new JsonStateStore(tempDir).load();
		assertEquals("Alpha", reloaded.station(new StationId("alpha")).orElseThrow().displayName());
		assertEquals("Alpha (2)", reloaded.station(new StationId("beta")).orElseThrow().displayName(),
				"the migrated unique name must survive reload");
	}

	@Test
	void provisionalStationRoundTripsWithoutDockOrExit() throws IOException, StateStore.StateException {
		Station mine = provisional("mine", 3);
		WorldState original = new WorldState();
		original.addStation(mine);
		original.addStation(station("alpha", 4));

		JsonStateStore store = new JsonStateStore(tempDir);
		store.save(original);
		WorldState loaded = new JsonStateStore(tempDir).load();

		Station loadedMine = loaded.station(mine.id()).orElseThrow();
		assertEquals(mine, loadedMine);
		assertTrue(loadedMine.isProvisional());
		assertFalse(loadedMine.isAnchored());
		assertNull(loadedMine.dock());
		assertNull(loadedMine.exit());
		assertFalse(loadedMine.enabled());
	}

	@Test
	void signMarkersRoundTripAndRemainQueryableByButton() throws IOException, StateStore.StateException {
		Station home = station("home", 1);
		Station mine = provisional("mine", 2);
		SignMarker homeUnit = new SignMarker(DIMENSION, new BlockLocation(3, 65, 0),
				new BlockLocation(4, 65, 0), true, new BlockLocation(5, 65, 0), home.id());
		SignMarker anchorOnly = new SignMarker(DIMENSION, new BlockLocation(10, 70, 0),
				new BlockLocation(11, 70, 0), false, null, home.id());
		SignMarker destinationUnit = new SignMarker(DIMENSION, new BlockLocation(20, 66, 5),
				new BlockLocation(21, 66, 5), true, new BlockLocation(22, 66, 5), null);
		WorldState original = new WorldState();
		original.addStation(home);
		original.addStation(mine);
		assertTrue(original.addSignMarker(homeUnit));
		assertTrue(original.addSignMarker(anchorOnly));
		assertTrue(original.addSignMarker(destinationUnit));

		JsonStateStore store = new JsonStateStore(tempDir);
		store.save(original);
		WorldState loaded = new JsonStateStore(tempDir).load();

		assertEquals(List.of(homeUnit, anchorOnly, destinationUnit), List.copyOf(loaded.signMarkers()));
		assertEquals(anchorOnly, loaded.signMarker(DIMENSION, new BlockLocation(10, 70, 0)).orElseThrow());
		assertEquals(homeUnit,
				loaded.signMarkerForButton(DIMENSION, new BlockLocation(5, 65, 0)).orElseThrow());
		assertEquals(destinationUnit,
				loaded.signMarkerForButton(DIMENSION, new BlockLocation(22, 66, 5)).orElseThrow());
	}

	@Test
	void versionTwoWithoutSignMarkerListLoads() throws IOException, StateStore.StateException {
		writeState("""
				{"schemaVersion": 2, "revision": 2, "stations": [%s, %s], "bindings": [%s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, BINDING_JSON));

		WorldState loaded = new JsonStateStore(tempDir).load();

		assertTrue(loaded.signMarkers().isEmpty());
		assertEquals(1, loaded.bindings().size());
	}

	@Test
	void enabledOrExitBearingProvisionalStationsAreRejected() throws IOException {
		String enabled = """
				{"id":{"value":"mine"},"displayName":"Mine","dimension":"minecraft:overworld",\
				"dock":null,"exit":null,"exitYaw":0.0,"enabled":true,"revision":1}
				""".strip();
		writeState("""
				{"schemaVersion": 2, "revision": 1, "stations": [%s], "bindings": [], "signMarkers": [], "trips": []}
				""".formatted(enabled));
		StateStore.StateException enabledError = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());
		assertTrue(enabledError.getMessage().contains("provisional"), enabledError::getMessage);

		String withExit = """
				{"id":{"value":"mine"},"displayName":"Mine","dimension":"minecraft:overworld",\
				"dock":null,"exit":{"x":1,"y":64,"z":1},"exitYaw":0.0,"enabled":false,"revision":1}
				""".strip();
		writeState("""
				{"schemaVersion": 2, "revision": 1, "stations": [%s], "bindings": [], "signMarkers": [], "trips": []}
				""".formatted(withExit));
		StateStore.StateException exitError = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());
		assertTrue(exitError.getMessage().contains("provisional"), exitError::getMessage);
	}

	@Test
	void duplicateSignOrButtonMarkerKeysAreRejected() throws IOException {
		String duplicateSign = markerJson(DIMENSION, 3, 8, "alpha");
		String sameSignOtherButton = markerJson(DIMENSION, 3, 9, "alpha");
		writeState("""
				{"schemaVersion": 2, "revision": 3, "stations": [%s, %s], "bindings": [],
				 "signMarkers": [%s, %s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, duplicateSign, sameSignOtherButton));
		StateStore.StateException signError = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());
		assertTrue(signError.getMessage().contains("duplicate sign marker"), signError::getMessage);

		String firstButton = markerJson(DIMENSION, 4, 12, "alpha");
		String secondButton = markerJson(DIMENSION, 6, 12, "alpha");
		writeState("""
				{"schemaVersion": 2, "revision": 3, "stations": [%s, %s], "bindings": [],
				 "signMarkers": [%s, %s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, firstButton, secondButton));
		StateStore.StateException buttonError = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());
		assertTrue(buttonError.getMessage().contains("duplicate sign marker button"), buttonError::getMessage);
	}

	@Test
	void unknownOrCrossDimensionMarkerStationsAreRejected() throws IOException {
		writeState("""
				{"schemaVersion": 2, "revision": 3, "stations": [%s, %s], "bindings": [],
				 "signMarkers": [%s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, markerJson(DIMENSION, 40, 41, "ghost")));
		StateStore.StateException unknown = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());
		assertTrue(unknown.getMessage().contains("unknown station"), unknown::getMessage);

		writeState("""
				{"schemaVersion": 2, "revision": 3, "stations": [%s, %s], "bindings": [],
				 "signMarkers": [%s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON,
				markerJson("minecraft:the_nether", 50, 51, "alpha")));
		StateStore.StateException dimension = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());
		assertTrue(dimension.getMessage().contains("dimension"), dimension::getMessage);
	}

	@Test
	void bindingSourceDockAndMarkerStationMainRoundTrip() throws IOException, StateStore.StateException {
		Station alpha = station("alpha", 5);
		Station beta = station("beta", 6);
		BlockLocation button = new BlockLocation(5, 65, 2);
		BlockLocation sourceDock = new BlockLocation(3, 64, 4);
		Binding binding = new Binding(DIMENSION, button, "minecraft:stone_button",
				alpha.id(), beta.id(), sourceDock);
		SignMarker main = new SignMarker(DIMENSION, new BlockLocation(5, 66, 0),
				new BlockLocation(5, 66, 1), true, button, null, alpha.id(), true);
		WorldState original = new WorldState();
		original.addStation(alpha);
		original.addStation(beta);
		assertTrue(original.addBinding(binding));
		assertTrue(original.addSignMarker(main));

		JsonStateStore store = new JsonStateStore(tempDir);
		store.save(original);
		String json = Files.readString(store.file(), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"schemaVersion\": 3"), json);
		assertTrue(json.contains("\"sourceDock\""), json);
		assertTrue(json.contains("\"stationId\""), json);
		assertTrue(json.contains("\"main\": true"), json);

		WorldState loaded = new JsonStateStore(tempDir).load();

		Binding loadedBinding = loaded.binding(DIMENSION, button).orElseThrow();
		assertEquals(binding, loadedBinding);
		assertEquals(sourceDock, loadedBinding.sourceDock());
		SignMarker loadedMarker = loaded.signMarker(DIMENSION, main.sign()).orElseThrow();
		assertEquals(main, loadedMarker);
		assertEquals(alpha.id(), loadedMarker.stationId());
		assertTrue(loadedMarker.main());
		assertEquals(loadedMarker, loaded.mainMarkerForStation(alpha.id()).orElseThrow());
		assertEquals(1, loaded.signUnitCount(alpha.id()));
	}

	@Test
	void stationOwnedButtonlessMarkerRoundTrips() throws IOException, StateStore.StateException {
		Station alpha = station("alpha", 5);
		SignMarker main = new SignMarker(DIMENSION, new BlockLocation(5, 66, 0),
				new BlockLocation(5, 66, 1), true, null, null, alpha.id(), true);
		SignMarker attached = new SignMarker(DIMENSION, new BlockLocation(6, 66, 0),
				new BlockLocation(6, 66, 1), true, null, null, alpha.id(), false);
		WorldState original = new WorldState();
		original.addStation(alpha);
		assertTrue(original.addSignMarker(main));
		assertTrue(original.addSignMarker(attached));

		JsonStateStore store = new JsonStateStore(tempDir);
		store.save(original);
		String json = Files.readString(store.file(), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"schemaVersion\": 3"), json);
		assertTrue(json.contains("\"main\": true"), json);

		WorldState loaded = new JsonStateStore(tempDir).load();
		SignMarker loadedMain = loaded.signMarker(DIMENSION, main.sign()).orElseThrow();
		SignMarker loadedAttached = loaded.signMarker(DIMENSION, attached.sign()).orElseThrow();
		assertEquals(main, loadedMain);
		assertNull(loadedMain.button());
		assertNull(loadedMain.anchorStationId());
		assertEquals(alpha.id(), loadedMain.stationId());
		assertTrue(loadedMain.main());
		assertFalse(loadedAttached.main());
		assertEquals(loadedMain, loaded.mainMarkerForStation(alpha.id()).orElseThrow());
		assertTrue(loaded.hasMainMarker(alpha.id()));
		assertEquals(2, loaded.signUnitCount(alpha.id()));
		assertTrue(loaded.hasOtherSignMarkers(alpha.id(), DIMENSION, main.sign()));
	}

	@Test
	void versionTwoStateMigratesToVersionThreeWithInferredMarkerOwners()
			throws IOException, StateStore.StateException {
		String bindingJson = """
				{"dimension":"minecraft:overworld","button":{"x":6,"y":65,"z":2},\
				"expectedButtonBlockId":"minecraft:stone_button",\
				"fromStationId":{"value":"alpha"},"toStationId":{"value":"mine"}}
				""".strip();
		String anchorMarker = markerJson(DIMENSION, 20, 21, "alpha");
		String destinationMarker = markerJson(DIMENSION, 6, 6, null);
		String original = """
				{"schemaVersion": 2, "revision": 4, "stations": [%s, %s, %s], "bindings": [%s],
				 "signMarkers": [%s, %s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON, PROVISIONAL_JSON, bindingJson,
				anchorMarker, destinationMarker);
		writeState(original);
		JsonStateStore store = new JsonStateStore(tempDir);

		WorldState loaded = store.load();

		assertEquals(3, loaded.stations().size());
		assertEquals(1, loaded.bindings().size());
		Binding migratedBinding = loaded.bindings().iterator().next();
		assertNull(migratedBinding.sourceDock(), "legacy bindings keep the station main dock");

		SignMarker anchor = loaded.signMarker(DIMENSION, new BlockLocation(20, 65, 0)).orElseThrow();
		assertEquals(new StationId("alpha"), anchor.stationId(), "anchor markers own the anchor station");
		assertFalse(anchor.main());
		SignMarker destination = loaded.signMarker(DIMENSION, new BlockLocation(6, 65, 0)).orElseThrow();
		assertEquals(new StationId("alpha"), destination.stationId(),
				"destination markers infer the owner from their button binding");
		assertNull(destination.anchorStationId());
		assertFalse(destination.main());
		Station migratedAlpha = loaded.station(new StationId("alpha")).orElseThrow();
		assertEquals(new BlockLocation(0, 64, 0), migratedAlpha.dock(),
				"migration preserves the station main dock");
		assertEquals(new BlockLocation(1, 64, 0), migratedAlpha.exit(),
				"migration preserves the station main exit");
		assertEquals(90.0F, migratedAlpha.exitYaw());
		assertEquals(new StationId("beta"), loaded.station(new StationId("beta")).orElseThrow().id(),
				"unrelated stations survive migration");

		Path backup = tempDir.resolve("railway").resolve("state.v2.bak");
		assertTrue(Files.isRegularFile(backup), "migrating v2 must keep the rolling backup");
		assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));

		store.save(loaded);
		String saved = Files.readString(store.file(), StandardCharsets.UTF_8);
		assertTrue(saved.contains("\"schemaVersion\": 3"), saved);
		assertTrue(saved.contains("\"stationId\": {"), saved);
		WorldState reloaded = new JsonStateStore(tempDir).load();
		assertEquals(new StationId("alpha"),
				reloaded.signMarker(DIMENSION, new BlockLocation(6, 65, 0)).orElseThrow().stationId());
		assertNull(reloaded.bindings().iterator().next().sourceDock());
		assertFalse(reloaded.signMarker(DIMENSION, new BlockLocation(20, 65, 0)).orElseThrow().main());
	}

	@Test
	void stateWithTwoMainMarkersForOneStationIsRejected() throws IOException {
		writeState("""
				{"schemaVersion": 3, "revision": 5, "stations": [%s, %s], "bindings": [],
				 "signMarkers": [%s, %s], "trips": []}
				""".formatted(ALPHA_JSON, BETA_JSON,
				ownedMarkerJson(DIMENSION, 3, 4, "alpha", "alpha", true),
				ownedMarkerJson(DIMENSION, 8, 9, null, "alpha", true)));

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("main sign marker"), error::getMessage);
	}

	@Test
	void mainMarkerWithoutAnOwnerStationIsRejected() throws IOException {
		writeState("""
				{"schemaVersion": 3, "revision": 5, "stations": [%s], "bindings": [],
				 "signMarkers": [%s], "trips": []}
				""".formatted(ALPHA_JSON, ownedMarkerJson(DIMENSION, 3, 4, null, "ghost", true)));

		StateStore.StateException error = assertThrows(StateStore.StateException.class,
				() -> new JsonStateStore(tempDir).load());

		assertTrue(error.getMessage().contains("unknown station"), error::getMessage);
	}
}
