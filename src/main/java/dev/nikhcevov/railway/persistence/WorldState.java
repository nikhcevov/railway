package dev.nikhcevov.railway.persistence;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Mutable authoritative world state. All mutation happens on the server thread. */
public final class WorldState {
	private final Map<StationId, Station> stations = new LinkedHashMap<>();
	private final Map<String, Binding> bindings = new LinkedHashMap<>();
	private final List<TripRecord> trips = new ArrayList<>();
	private long revision;

	public long revision() {
		return revision;
	}

	public void setRevision(long revision) {
		this.revision = Math.max(0, revision);
	}

	public long nextRevision() {
		return ++revision;
	}

	public Optional<Station> station(StationId id) {
		return Optional.ofNullable(stations.get(id));
	}

	public Collection<Station> stations() {
		return List.copyOf(stations.values());
	}

	public Optional<Binding> binding(String dimension, BlockLocation button) {
		return Optional.ofNullable(bindings.get(bindingKey(dimension, button)));
	}

	public Collection<Binding> bindings() {
		return List.copyOf(bindings.values());
	}

	public void addStation(Station station) {
		Objects.requireNonNull(station, "station");
		if (stations.putIfAbsent(station.id(), station) != null) {
			throw new IllegalStateException("station already exists: " + station.id());
		}
		revision = Math.max(revision, station.revision());
	}

	public void updateStation(Station station) {
		Objects.requireNonNull(station, "station");
		if (!stations.containsKey(station.id())) {
			throw new IllegalStateException("unknown station: " + station.id());
		}
		stations.put(station.id(), station);
		revision = Math.max(revision, station.revision());
	}

	public List<Binding> removeStation(StationId id) {
		stations.remove(id);
		revision++;
		List<Binding> removed = new ArrayList<>();
		bindings.entrySet().removeIf(entry -> {
			Binding binding = entry.getValue();
			if (binding.fromStationId().equals(id) || binding.toStationId().equals(id)) {
				removed.add(binding);
				return true;
			}
			return false;
		});
		return removed;
	}

	public boolean addBinding(Binding binding) {
		boolean added = bindings.putIfAbsent(bindingKey(binding.dimension(), binding.button()), binding) == null;
		if (added) {
			revision++;
		}
		return added;
	}

	public Optional<Binding> removeBinding(String dimension, BlockLocation button) {
		Binding removed = bindings.remove(bindingKey(dimension, button));
		if (removed != null) {
			revision++;
		}
		return Optional.ofNullable(removed);
	}

	public List<Binding> bindingsFor(StationId id) {
		return bindings.values().stream()
				.filter(binding -> binding.fromStationId().equals(id) || binding.toStationId().equals(id))
				.toList();
	}

	public List<TripRecord> trips() {
		return List.copyOf(trips);
	}

	public void setTrips(List<TripRecord> records) {
		trips.clear();
		trips.addAll(records);
	}

	public void addTrip(TripRecord record) {
		trips.add(record);
	}

	public void removeTrip(dev.nikhcevov.railway.domain.TripId tripId) {
		trips.removeIf(record -> record.id().equals(tripId));
	}

	public static String bindingKey(String dimension, BlockLocation button) {
		return dimension + '|' + button.x() + ',' + button.y() + ',' + button.z();
	}
}
