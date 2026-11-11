package dev.nikhcevov.railway.dispatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Route-resource ownership. Exclusive resources (platforms, segments, switch
 * conflict zones) are held by at most one trip. Directional corridors
 * ({@code sec|...}) may be shared by trips travelling in the same direction
 * while blocking opposing traffic. Resources whose occupants cannot be verified
 * leave ownership and enter quarantine until an explicit reconciliation by
 * service-cart UUID.
 */
public final class ReservationManager {
	public record Quarantine(UUID tripId, UUID cartUuid, String reason) {
	}

	public record QuarantinedResource(String resource, Quarantine quarantine) {
	}

	public record CorridorRequest(String resource, String direction) {
		public CorridorRequest {
			Objects.requireNonNull(resource, "resource");
			Objects.requireNonNull(direction, "direction");
		}
	}

	private static final class CorridorState {
		String direction;
		final Set<UUID> owners = new LinkedHashSet<>();

		CorridorState(String direction) {
			this.direction = direction;
		}
	}

	private final Map<String, UUID> owners = new HashMap<>();
	private final Map<String, CorridorState> corridors = new HashMap<>();
	private final Map<String, Quarantine> quarantined = new LinkedHashMap<>();

	/** Returns false when any requested exclusive resource is owned or quarantined. */
	public boolean tryAcquire(Iterable<String> resources, UUID tripId) {
		return tryAcquire(resources, List.of(), tripId);
	}

	/**
	 * Atomically acquires exclusive resources and directional corridor leases.
	 * Returns false without modifying state if any resource conflicts or is
	 * quarantined.
	 */
	public boolean tryAcquire(Iterable<String> exclusiveResources, Iterable<CorridorRequest> corridorRequests,
			UUID tripId) {
		Objects.requireNonNull(exclusiveResources, "exclusiveResources");
		Objects.requireNonNull(corridorRequests, "corridorRequests");
		Objects.requireNonNull(tripId, "tripId");
		for (String resource : exclusiveResources) {
			if (!canAcquireExclusive(resource, tripId)) {
				return false;
			}
		}
		for (CorridorRequest request : corridorRequests) {
			if (!isCorridorCompatible(request.resource(), request.direction(), tripId)) {
				return false;
			}
		}
		for (String resource : exclusiveResources) {
			owners.put(resource, tripId);
		}
		for (CorridorRequest request : corridorRequests) {
			CorridorState state = corridors.computeIfAbsent(request.resource(),
					ignored -> new CorridorState(request.direction()));
			state.direction = request.direction();
			state.owners.add(tripId);
		}
		return true;
	}

	public boolean tryAcquireCorridor(String resource, String direction, UUID tripId) {
		return tryAcquire(List.of(), List.of(new CorridorRequest(resource, direction)), tripId);
	}

	public boolean canAcquireExclusive(String resource, UUID tripId) {
		if (quarantined.containsKey(resource)) {
			return false;
		}
		UUID owner = owners.get(resource);
		if (owner != null && !owner.equals(tripId)) {
			return false;
		}
		CorridorState corridor = corridors.get(resource);
		return corridor == null || (corridor.owners.size() == 1 && corridor.owners.contains(tripId));
	}

	public boolean isCorridorCompatible(String resource, String direction, UUID tripId) {
		if (quarantined.containsKey(resource)) {
			return false;
		}
		UUID exclusiveOwner = owners.get(resource);
		if (exclusiveOwner != null && !exclusiveOwner.equals(tripId)) {
			return false;
		}
		CorridorState state = corridors.get(resource);
		if (state == null || state.owners.isEmpty()) {
			return true;
		}
		if (state.direction.equals(direction)) {
			return true;
		}
		return state.owners.size() == 1 && state.owners.contains(tripId);
	}

	public UUID ownerOf(String resource) {
		return owners.get(resource);
	}

	public Set<UUID> corridorOwners(String resource) {
		CorridorState state = corridors.get(resource);
		return state == null ? Set.of() : Set.copyOf(state.owners);
	}

	public String corridorDirection(String resource) {
		CorridorState state = corridors.get(resource);
		return state == null ? null : state.direction;
	}

	public void release(String resource, UUID tripId) {
		owners.remove(resource, tripId);
		CorridorState state = corridors.get(resource);
		if (state != null) {
			state.owners.remove(tripId);
			if (state.owners.isEmpty()) {
				corridors.remove(resource);
			}
		}
	}

	public void releaseAll(UUID tripId) {
		owners.values().removeIf(tripId::equals);
		corridors.entrySet().removeIf(entry -> {
			entry.getValue().owners.remove(tripId);
			return entry.getValue().owners.isEmpty();
		});
	}

	/**
	 * Moves every resource owned by the trip into quarantine. Returns the number
	 * of quarantined resources. Releases never clear quarantine.
	 */
	public int quarantineAll(UUID tripId, UUID cartUuid, String reason) {
		Objects.requireNonNull(cartUuid, "cartUuid");
		Set<String> owned = new LinkedHashSet<>();
		for (Map.Entry<String, UUID> entry : owners.entrySet()) {
			if (entry.getValue().equals(tripId)) {
				owned.add(entry.getKey());
			}
		}
		for (Map.Entry<String, CorridorState> entry : List.copyOf(corridors.entrySet())) {
			CorridorState state = entry.getValue();
			if (state.owners.remove(tripId)) {
				owned.add(entry.getKey());
				if (state.owners.isEmpty()) {
					corridors.remove(entry.getKey());
				}
			}
		}
		for (String resource : owned) {
			owners.remove(resource, tripId);
			quarantined.put(resource, new Quarantine(tripId, cartUuid, reason));
		}
		return owned.size();
	}

	public int reconcile(UUID cartUuid) {
		int before = quarantined.size();
		quarantined.entrySet().removeIf(entry -> entry.getValue().cartUuid().equals(cartUuid));
		return before - quarantined.size();
	}

	public boolean isQuarantined(String resource) {
		return quarantined.containsKey(resource);
	}

	public List<QuarantinedResource> quarantinedResources() {
		List<QuarantinedResource> result = new ArrayList<>();
		quarantined.forEach((resource, quarantine) -> result.add(new QuarantinedResource(resource, quarantine)));
		return List.copyOf(result);
	}

	public boolean isFree(String resource) {
		return !owners.containsKey(resource) && !corridors.containsKey(resource) && !quarantined.containsKey(resource);
	}

	public int ownedCount(UUID tripId) {
		int count = 0;
		for (UUID owner : owners.values()) {
			if (owner.equals(tripId)) {
				count++;
			}
		}
		for (CorridorState state : corridors.values()) {
			if (state.owners.contains(tripId)) {
				count++;
			}
		}
		return count;
	}

	public Map<String, UUID> snapshot() {
		Map<String, UUID> snap = new HashMap<>(owners);
		for (Map.Entry<String, CorridorState> entry : corridors.entrySet()) {
			if (!entry.getValue().owners.isEmpty()) {
				snap.putIfAbsent(entry.getKey(), entry.getValue().owners.iterator().next());
			}
		}
		return Map.copyOf(snap);
	}
}
