package dev.nikhcevov.railway.dispatch;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Exclusive resource ownership. Stage 1 reserves one whole connected line per trip;
 * every acquisition is all-or-nothing.
 */
public final class ReservationManager {
	private final Map<String, UUID> owners = new HashMap<>();

	/** Returns false when any requested resource is already owned by another trip. */
	public boolean tryAcquire(Iterable<String> resources, UUID tripId) {
		Objects.requireNonNull(resources, "resources");
		Objects.requireNonNull(tripId, "tripId");
		for (String resource : resources) {
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

	public boolean isFree(String resource) {
		return !owners.containsKey(resource);
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
