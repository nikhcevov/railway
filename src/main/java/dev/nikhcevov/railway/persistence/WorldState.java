package dev.nikhcevov.railway.persistence;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.StationNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Mutable authoritative world state. All mutation happens on the server thread. */
public final class WorldState {
	private final Map<StationId, Station> stations = new LinkedHashMap<>();
	private final Map<String, Binding> bindings = new LinkedHashMap<>();
	private final Map<String, SignMarker> signMarkers = new LinkedHashMap<>();
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

	// ---------------------------------------------------------------- stations

	public Optional<Station> station(StationId id) {
		return Optional.ofNullable(stations.get(id));
	}

	public Collection<Station> stations() {
		return List.copyOf(stations.values());
	}

	/** Stations whose name matches case-insensitively in the dimension. */
	public List<Station> stationsNamed(String dimension, String name) {
		return StationNames.find(stations.values(), dimension, name);
	}

	/** The single station with this name, or empty when absent or ambiguous. */
	public Optional<Station> uniqueStationNamed(String dimension, String name) {
		return StationNames.unique(stations.values(), dimension, name);
	}

	public boolean hasStationNamed(String dimension, String name) {
		return !stationsNamed(dimension, name).isEmpty();
	}

	public boolean isStationNameAmbiguous(String dimension, String name) {
		return StationNames.isAmbiguous(stations.values(), dimension, name);
	}

	/** Display spelling of the first station registered under this name. */
	public Optional<String> displayNameFor(String dimension, String name) {
		return StationNames.displaySpelling(stations.values(), dimension, name);
	}

	public void addStation(Station station) {
		Objects.requireNonNull(station, "station");
		if (stations.containsKey(station.id())) {
			throw new IllegalStateException("station already exists: " + station.id());
		}
		requireUniqueName(station, null);
		stations.put(station.id(), station);
		revision = Math.max(revision, station.revision());
	}

	public void updateStation(Station station) {
		Objects.requireNonNull(station, "station");
		if (!stations.containsKey(station.id())) {
			throw new IllegalStateException("unknown station: " + station.id());
		}
		requireUniqueName(station, station.id());
		stations.put(station.id(), station);
		revision = Math.max(revision, station.revision());
	}

	/**
	 * Removes a station, its bindings, and markers that reference it. Marker removal is
	 * limited to the explicitly removed station's anchor/bindings; other station data is
	 * never touched by marker lifecycle.
	 */
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
		Set<String> removedButtons = new HashSet<>();
		for (Binding binding : removed) {
			removedButtons.add(bindingKey(binding.dimension(), binding.button()));
		}
		signMarkers.entrySet().removeIf(entry -> {
			SignMarker marker = entry.getValue();
			if (id.equals(marker.anchorStationId())) {
				return true;
			}
			return marker.button() != null
					&& removedButtons.contains(bindingKey(marker.dimension(), marker.button()));
		});
		return removed;
	}

	/**
	 * Prunes dockless provisional stations that no binding or marker references.
	 * Anchored stations and provisional stations with any reference are retained.
	 */
	public List<Station> pruneProvisionalStations() {
		List<Station> pruned = new ArrayList<>();
		for (Station station : List.copyOf(stations.values())) {
			if (!station.isProvisional()) {
				continue;
			}
			if (!bindingsFor(station.id()).isEmpty()) {
				continue;
			}
			if (hasMarkerReference(station.id())) {
				continue;
			}
			stations.remove(station.id());
			pruned.add(station);
		}
		if (!pruned.isEmpty()) {
			revision++;
		}
		return List.copyOf(pruned);
	}

	private boolean hasMarkerReference(StationId id) {
		for (SignMarker marker : signMarkers.values()) {
			if (id.equals(marker.anchorStationId())) {
				return true;
			}
		}
		return false;
	}

	private void requireUniqueName(Station station, StationId excluded) {
		for (Station existing : stations.values()) {
			if (excluded != null && existing.id().equals(excluded)) {
				continue;
			}
			if (StationNames.matches(existing, station.dimension(), station.displayName())) {
				throw new IllegalStateException("station name already exists in " + station.dimension()
						+ ": " + station.displayName());
			}
		}
	}

	// ---------------------------------------------------------------- bindings

	public Optional<Binding> binding(String dimension, BlockLocation button) {
		return Optional.ofNullable(bindings.get(bindingKey(dimension, button)));
	}

	public Collection<Binding> bindings() {
		return List.copyOf(bindings.values());
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

	// ------------------------------------------------------------- sign markers

	public Optional<SignMarker> signMarker(String dimension, BlockLocation sign) {
		return Optional.ofNullable(signMarkers.get(signMarkerKey(dimension, sign)));
	}

	public Optional<SignMarker> signMarkerForButton(String dimension, BlockLocation button) {
		for (SignMarker marker : signMarkers.values()) {
			if (marker.dimension().equals(dimension) && button.equals(marker.button())) {
				return Optional.of(marker);
			}
		}
		return Optional.empty();
	}

	public Collection<SignMarker> signMarkers() {
		return List.copyOf(signMarkers.values());
	}

	/** True when a registered sign anchored this station. */
	public boolean hasAnchorMarker(StationId id) {
		for (SignMarker marker : signMarkers.values()) {
			if (id.equals(marker.anchorStationId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Registers or replaces the marker at its sign. Returns false when the identical
	 * marker is already registered. A button may only be owned by one marker.
	 */
	public boolean updateSignMarker(SignMarker marker) {
		Objects.requireNonNull(marker, "marker");
		requireKnownAnchor(marker);
		String key = signMarkerKey(marker.dimension(), marker.sign());
		SignMarker existing = signMarkers.get(key);
		if (existing != null && existing.equals(marker)) {
			return false;
		}
		if (marker.button() != null) {
			for (SignMarker other : signMarkers.values()) {
				if (other.dimension().equals(marker.dimension())
						&& marker.button().equals(other.button())
						&& !signMarkerKey(other.dimension(), other.sign()).equals(key)) {
					throw new IllegalStateException(
							"button already has a sign marker: " + marker.button().shortString());
				}
			}
		}
		signMarkers.put(key, marker);
		revision++;
		return true;
	}

	/** Adds a marker. Returns false when the identical marker is already registered. */
	public boolean addSignMarker(SignMarker marker) {
		Objects.requireNonNull(marker, "marker");
		requireKnownAnchor(marker);
		String key = signMarkerKey(marker.dimension(), marker.sign());
		SignMarker existing = signMarkers.get(key);
		if (existing != null) {
			if (existing.equals(marker)) {
				return false;
			}
			throw new IllegalStateException("sign marker already exists at " + marker.sign().shortString());
		}
		if (marker.button() != null) {
			Optional<SignMarker> buttonOwner = signMarkerForButton(marker.dimension(), marker.button());
			if (buttonOwner.isPresent()) {
				throw new IllegalStateException("button already has a sign marker: " + marker.button().shortString());
			}
		}
		signMarkers.put(key, marker);
		revision++;
		return true;
	}

	/** Removes only the marker at this sign; station and binding data stay untouched. */
	public Optional<SignMarker> removeSignMarker(String dimension, BlockLocation sign) {
		SignMarker removed = signMarkers.remove(signMarkerKey(dimension, sign));
		if (removed != null) {
			revision++;
		}
		return Optional.ofNullable(removed);
	}

	/** Removes only the marker owning this button; station and binding data stay untouched. */
	public Optional<SignMarker> removeSignMarkerForButton(String dimension, BlockLocation button) {
		SignMarker removed = null;
		for (SignMarker marker : List.copyOf(signMarkers.values())) {
			if (marker.dimension().equals(dimension) && button.equals(marker.button())) {
				removed = marker;
				signMarkers.remove(signMarkerKey(marker.dimension(), marker.sign()));
				break;
			}
		}
		if (removed != null) {
			revision++;
		}
		return Optional.ofNullable(removed);
	}

	private void requireKnownAnchor(SignMarker marker) {
		if (marker.anchorStationId() == null) {
			return;
		}
		Station anchor = stations.get(marker.anchorStationId());
		if (anchor == null) {
			throw new IllegalStateException("sign marker references unknown station: " + marker.anchorStationId());
		}
		if (!anchor.dimension().equals(marker.dimension())) {
			throw new IllegalStateException("sign marker dimension does not match anchor station: "
					+ marker.anchorStationId());
		}
	}

	// -------------------------------------------------------------------- trips

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

	public static String signMarkerKey(String dimension, BlockLocation sign) {
		return dimension + '|' + sign.x() + ',' + sign.y() + ',' + sign.z();
	}
}
