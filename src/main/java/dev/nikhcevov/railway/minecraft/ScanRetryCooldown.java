package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.domain.StationId;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-station cooldown between automatic scans. A failed scan (bad geometry,
 * missing chunks, exhausted ticket budget) must not be restarted on every
 * button press or route check; a manually forced scan bypasses this cooldown.
 *
 * <p>All access is on the server thread. The cooldown is in-memory only: it is
 * cleared by a successful scan and never persisted across restarts.
 */
public final class ScanRetryCooldown {
	/** 200 ticks: long enough to stop button spam, short enough to allow a fix. */
	public static final long DEFAULT_COOLDOWN_TICKS = 200L;

	private final long cooldownTicks;
	private final Map<StationId, Long> nextAllowedTick = new HashMap<>();

	public ScanRetryCooldown() {
		this(DEFAULT_COOLDOWN_TICKS);
	}

	ScanRetryCooldown(long cooldownTicks) {
		if (cooldownTicks < 0) {
			throw new IllegalArgumentException("cooldownTicks must not be negative");
		}
		this.cooldownTicks = cooldownTicks;
	}

	/** True when an automatic scan may start for this station at {@code tick}. */
	public boolean allows(StationId station, long tick) {
		Long next = nextAllowedTick.get(station);
		return next == null || tick >= next;
	}

	/** Starts the cooldown after an automatic scan was launched. */
	public void recordAttempt(StationId station, long tick) {
		nextAllowedTick.put(station, tick + cooldownTicks);
	}

	/** Extends the cooldown after a scan failed, measured from the failure tick. */
	public void recordFailure(StationId station, long tick) {
		recordAttempt(station, tick);
	}

	/** A successful scan, a manual rescan or an invalidation clears the cooldown. */
	public void clear(StationId station) {
		nextAllowedTick.remove(station);
	}

	public void clear() {
		nextAllowedTick.clear();
	}
}
