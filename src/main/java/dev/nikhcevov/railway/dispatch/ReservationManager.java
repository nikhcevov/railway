package dev.nikhcevov.railway.dispatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Exclusive route-resource ownership. Acquisition is all-or-nothing and never
 * waits. Resources whose occupants cannot be verified leave ownership and enter
 * quarantine until an explicit reconciliation by service-cart UUID.
 */
public final class ReservationManager {
	public record Quarantine(UUID tripId, UUID cartUuid, String reason) {
	}

	public record QuarantinedResource(String resource, Quarantine quarantine) {
	}

	private final Map<String, UUID> owners = new HashMap<>();
	private final Map<String, Quarantine> quarantined = new LinkedHashMap<>();

	/** Returns false when any requested resource is owned or quarantined. */
	public boolean tryAcquire(Iterable<String> resources, UUID tripId) {
		Objects.requireNonNull(resources, "resources");
		Objects.requireNonNull(tripId, "tripId");
		for (String resource : resources) {
			if (quarantined.containsKey(resource)) {
				return false;
			}
			UUID owner = owners.get(resource);
			if (owner != null && !owner.equals(tripId)) {
				return false;
			}
		}
		for (String resource : resources) {
			owners.put(resource, tripId);
		}
		return true;
	}

	public void release(String resource, UUID tripId) {
		owners.remove(resource, tripId);
	}

	public void releaseAll(UUID tripId) {
		owners.values().removeIf(tripId::equals);
	}

	/**
	 * Moves every resource owned by the trip into quarantine. Returns the number
	 * of quarantined resources. Releases never clear quarantine.
	 */
	public int quarantineAll(UUID tripId, UUID cartUuid, String reason) {
		Objects.requireNonNull(cartUuid, "cartUuid");
		List<String> owned = new ArrayList<>();
		for (Map.Entry<String, UUID> entry : owners.entrySet()) {
			if (entry.getValue().equals(tripId)) {
				owned.add(entry.getKey());
			}
		}
		for (String resource : owned) {
			owners.remove(resource);
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
		return !owners.containsKey(resource) && !quarantined.containsKey(resource);
	}

	public int ownedCount(UUID tripId) {
		int count = 0;
		for (UUID owner : owners.values()) {
			if (owner.equals(tripId)) {
				count++;
			}
		}
		return count;
	}

	public Map<String, UUID> snapshot() {
		return Map.copyOf(owners);
	}
}
