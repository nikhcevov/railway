package dev.nikhcevov.railway.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;

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
	public static final int SCHEMA_VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path file;

	public JsonStateStore(Path worldRoot) {
		this.file = worldRoot.resolve("railway").resolve("state.json");
	}

	@Override
	public WorldState load() throws StateException {
		WorldState state = new WorldState();
		if (!Files.exists(file)) {
			return state;
		}
		JsonObject object;
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (!JsonParser.parseString(text).isJsonObject()) {
				throw new StateException("state root must be a JSON object");
			}
			object = JsonParser.parseString(text).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			throw new StateException("cannot read state: " + e.getMessage(), e);
		}

		int schema = object.has("schemaVersion") ? object.get("schemaVersion").getAsInt() : -1;
		if (schema > SCHEMA_VERSION) {
			throw new StateException("state schema " + schema + " is newer than supported " + SCHEMA_VERSION);
		}
		if (schema < 1) {
			throw new StateException("missing or invalid state schemaVersion");
		}
		if (schema < SCHEMA_VERSION) {
			backup(schema);
		}

		StateFile parsed;
		try {
			parsed = GSON.fromJson(object, StateFile.class);
		} catch (RuntimeException e) {
			throw new StateException("cannot parse state: " + e.getMessage(), e);
		}
		if (parsed == null) {
			throw new StateException("empty state file");
		}
		validate(parsed, state);
		return state;
	}

	private void backup(int schema) throws StateException {
		Path backup = file.resolveSibling("state.v" + schema + ".bak");
		try {
			Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new StateException("cannot back up state before migration: " + e.getMessage(), e);
		}
	}

	private static void validate(StateFile parsed, WorldState state) throws StateException {
		if (parsed.revision < 0) {
			throw new StateException("negative revision");
		}
		state.setRevision(parsed.revision);
		Set<StationId> ids = new HashSet<>();
		if (parsed.stations != null) {
			for (Station station : parsed.stations) {
				if (station == null || station.id() == null || station.dock() == null
						|| station.dimension() == null || station.dimension().isBlank()) {
					throw new StateException("station has missing required fields");
				}
				if (!ids.add(station.id())) {
					throw new StateException("duplicate station id: " + station.id());
				}
				state.addStation(station);
			}
		}
		Set<String> buttons = new HashSet<>();
		if (parsed.bindings != null) {
			for (Binding binding : parsed.bindings) {
				if (binding == null || binding.button() == null || binding.dimension() == null) {
					throw new StateException("binding has missing required fields");
				}
				if (!ids.contains(binding.fromStationId()) || !ids.contains(binding.toStationId())) {
					throw new StateException("binding references unknown station: " + binding);
				}
				String key = WorldState.bindingKey(binding.dimension(), binding.button());
				if (!buttons.add(key)) {
					throw new StateException("duplicate binding for button " + key);
				}
				if (!state.addBinding(binding)) {
					throw new StateException("duplicate binding for button " + key);
				}
			}
		}
		List<TripRecord> trips = new ArrayList<>();
		if (parsed.trips != null) {
			for (TripRecord trip : parsed.trips) {
				if (trip == null || trip.id() == null || trip.playerUuid() == null || trip.cartUuid() == null
						|| trip.from() == null || trip.to() == null || trip.phase() == null) {
					throw new StateException("trip checkpoint has missing required fields");
				}
				if (!ids.contains(trip.from()) || !ids.contains(trip.to())) {
					throw new StateException("trip checkpoint references unknown station: " + trip.id());
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

	@Override
	public void save(WorldState state) throws IOException {
		StateFile fileData = new StateFile(
				SCHEMA_VERSION,
				state.revision(),
				List.copyOf(state.stations()),
				List.copyOf(state.bindings()),
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
		List<TripRecord> trips;

		StateFile() {
		}

		StateFile(int schemaVersion, long revision, List<Station> stations, List<Binding> bindings, List<TripRecord> trips) {
			this.schemaVersion = schemaVersion;
			this.revision = revision;
			this.stations = stations;
			this.bindings = bindings;
			this.trips = trips;
		}
	}
}
