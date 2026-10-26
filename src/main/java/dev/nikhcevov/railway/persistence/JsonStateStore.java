package dev.nikhcevov.railway.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.StationNames;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JSON state under {@code <world>/railway/state.json}. */
public final class JsonStateStore implements StateStore {
	/** Schema 2 adds sign markers and dockless provisional stations. */
	public static final int SCHEMA_VERSION = 2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path file;

	public JsonStateStore(Path worldRoot) {
		this.file = worldRoot.resolve("railway").resolve("state.json");
	}

	@Override
	public WorldState load() throws StateStore.StateException {
		WorldState state = new WorldState();
		if (!Files.exists(file)) {
			return state;
		}
		JsonObject object;
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (!JsonParser.parseString(text).isJsonObject()) {
				throw new StateStore.StateException("state root must be a JSON object");
			}
			object = JsonParser.parseString(text).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			throw new StateStore.StateException("cannot read state: " + e.getMessage(), e);
		}

		int schema;
		try {
			schema = object.has("schemaVersion") ? object.get("schemaVersion").getAsInt() : -1;
		} catch (RuntimeException e) {
			throw new StateStore.StateException("invalid state schemaVersion", e);
		}
		if (schema > SCHEMA_VERSION) {
			throw new StateStore.StateException(
					"state schema " + schema + " is newer than supported " + SCHEMA_VERSION);
		}
		if (schema < 1) {
			throw new StateStore.StateException("missing or invalid state schemaVersion");
		}
		if (schema < SCHEMA_VERSION) {
			backup(schema);
		}

		StateFile parsed;
		try {
			parsed = GSON.fromJson(object, StateFile.class);
		} catch (RuntimeException e) {
			throw new StateStore.StateException("cannot parse state: " + rootMessage(e), e);
		}
		if (parsed == null) {
			throw new StateStore.StateException("empty state file");
		}
		validate(parsed, state, schema);
		return state;
	}

	private static String rootMessage(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	private void backup(int schema) throws StateStore.StateException {
		Path backup = file.resolveSibling("state.v" + schema + ".bak");
		try {
			Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new StateStore.StateException("cannot back up state before migration: " + e.getMessage(), e);
		}
	}

	private static void validate(StateFile parsed, WorldState state, int schema)
			throws StateStore.StateException {
		if (parsed.revision < 0) {
			throw new StateStore.StateException("negative revision");
		}
		state.setRevision(parsed.revision);
		Set<StationId> ids = new HashSet<>();
		Set<String> stationNames = new HashSet<>();
		Set<String> declaredNameKeys = declaredStationNameKeys(parsed);
		if (parsed.stations != null) {
			for (Station station : parsed.stations) {
				if (station == null || station.id() == null || station.dimension() == null
						|| station.displayName() == null) {
					throw new StateStore.StateException("station has missing required fields");
				}
				if (!ids.add(station.id())) {
					throw new StateStore.StateException("duplicate station id: " + station.id());
				}
				String nameKey;
				try {
					nameKey = StationNames.lookupKey(station.dimension(), station.displayName());
				} catch (IllegalArgumentException e) {
					throw new StateStore.StateException("station has an invalid name: " + station.id(), e);
				}
				if (!stationNames.add(nameKey)) {
					if (schema >= 2) {
						throw new StateStore.StateException("duplicate station name in " + station.dimension()
								+ ": " + station.displayName());
					}
					// Names became identity in schema 2. Legacy schema 1 allowed duplicate
					// display names; preserve every station and binding by renaming the
					// later duplicate instead of disabling the whole mod on upgrade.
					station = withUniqueDisplayName(station, stationNames, declaredNameKeys);
					nameKey = StationNames.lookupKey(station.dimension(), station.displayName());
					stationNames.add(nameKey);
				}
				try {
					state.addStation(station);
				} catch (IllegalStateException e) {
					throw new StateStore.StateException(e.getMessage(), e);
				}
			}
		}
		Set<String> buttons = new HashSet<>();
		if (parsed.bindings != null) {
			for (Binding binding : parsed.bindings) {
				if (binding == null || binding.button() == null || binding.dimension() == null) {
					throw new StateStore.StateException("binding has missing required fields");
				}
				if (!ids.contains(binding.fromStationId()) || !ids.contains(binding.toStationId())) {
					throw new StateStore.StateException("binding references unknown station: " + binding);
				}
				String key = WorldState.bindingKey(binding.dimension(), binding.button());
				if (!buttons.add(key)) {
					throw new StateStore.StateException("duplicate binding for button " + key);
				}
				if (!state.addBinding(binding)) {
					throw new StateStore.StateException("duplicate binding for button " + key);
				}
			}
		}
		Set<String> markerSigns = new HashSet<>();
		Set<String> markerButtons = new HashSet<>();
		if (parsed.signMarkers != null) {
			for (SignMarker marker : parsed.signMarkers) {
				if (marker == null || marker.dimension() == null || marker.sign() == null
						|| marker.support() == null) {
					throw new StateStore.StateException("sign marker has missing required fields");
				}
				String signKey = WorldState.signMarkerKey(marker.dimension(), marker.sign());
				if (!markerSigns.add(signKey)) {
					throw new StateStore.StateException("duplicate sign marker at " + signKey);
				}
				if (marker.button() != null) {
					String buttonKey = WorldState.bindingKey(marker.dimension(), marker.button());
					if (!markerButtons.add(buttonKey)) {
						throw new StateStore.StateException("duplicate sign marker button " + buttonKey);
					}
				}
				if (marker.anchorStationId() != null) {
					Station anchor = state.station(marker.anchorStationId()).orElse(null);
					if (anchor == null) {
						throw new StateStore.StateException(
								"sign marker references unknown station: " + marker.anchorStationId());
					}
					if (!anchor.dimension().equals(marker.dimension())) {
						throw new StateStore.StateException(
								"sign marker dimension does not match station: " + marker.anchorStationId());
					}
				}
				try {
					if (!state.addSignMarker(marker)) {
						throw new StateStore.StateException("duplicate sign marker at " + signKey);
					}
				} catch (IllegalStateException e) {
					throw new StateStore.StateException(e.getMessage(), e);
				}
			}
		}
		List<TripRecord> trips = new ArrayList<>();
		if (parsed.trips != null) {
			for (TripRecord trip : parsed.trips) {
				if (trip == null || trip.id() == null || trip.playerUuid() == null || trip.cartUuid() == null
						|| trip.from() == null || trip.to() == null || trip.phase() == null) {
					throw new StateStore.StateException("trip checkpoint has missing required fields");
				}
				if (!ids.contains(trip.from()) || !ids.contains(trip.to())) {
					throw new StateStore.StateException("trip checkpoint references unknown station: " + trip.id());
				}
				if (trip.phase().isTerminal()) {
					continue;
				}
				trips.add(trip);
			}
		}
		state.setTrips(trips);
		long maxStationRevision = 0;
		for (Station station : state.stations()) {
			maxStationRevision = Math.max(maxStationRevision, station.revision());
		}
		// Loading bindings goes through the normal mutation path, so restore the
		// persisted revision (never below a station revision).
		state.setRevision(Math.max(parsed.revision, maxStationRevision));
	}

	private static Set<String> declaredStationNameKeys(StateFile parsed) {
		Set<String> keys = new HashSet<>();
		if (parsed.stations == null) {
			return keys;
		}
		for (Station station : parsed.stations) {
			if (station == null || station.dimension() == null || station.displayName() == null) {
				continue;
			}
			try {
				keys.add(StationNames.lookupKey(station.dimension(), station.displayName()));
			} catch (IllegalArgumentException ignored) {
				// The main validation loop reports the malformed station.
			}
		}
		return keys;
	}

	private static Station withUniqueDisplayName(Station station, Set<String> assigned, Set<String> declared) {
		String base = StationNames.normalize(station.displayName());
		for (int suffix = 2; ; suffix++) {
			String candidate = base + " (" + suffix + ")";
			String key = StationNames.lookupKey(station.dimension(), candidate);
			if (!assigned.contains(key) && !declared.contains(key)) {
				return new Station(station.id(), candidate, station.dimension(), station.dock(),
						station.exit(), station.exitYaw(), station.enabled(), station.revision());
			}
		}
	}

	@Override
	public void save(WorldState state) throws IOException {
		StateFile fileData = new StateFile(
				SCHEMA_VERSION,
				state.revision(),
				List.copyOf(state.stations()),
				List.copyOf(state.bindings()),
				List.copyOf(state.signMarkers()),
				state.trips()
		);
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling("state.json.tmp");
		Files.writeString(tmp, GSON.toJson(fileData), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	public Path file() {
		return file;
	}

	/** Serialized shape. Kept nested-private so the domain model stays free of persistence concerns. */
	private static final class StateFile {
		int schemaVersion;
		long revision;
		List<Station> stations;
		List<Binding> bindings;
		List<SignMarker> signMarkers;
		List<TripRecord> trips;

		StateFile() {
		}

		StateFile(int schemaVersion, long revision, List<Station> stations, List<Binding> bindings,
				List<SignMarker> signMarkers, List<TripRecord> trips) {
			this.schemaVersion = schemaVersion;
			this.revision = revision;
			this.stations = stations;
			this.bindings = bindings;
			this.signMarkers = signMarkers;
			this.trips = trips;
		}
	}
}
