package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.TripId;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns every chunk ticket Railway creates for trips. Tickets are server-only,
 * non-persistent, entity-ticking simulation tickets for the local route window
 * only. A caller may only ticket chunks that the scanned route already proved to
 * exist; the manager itself never forces chunks and never requests generation.
 *
 * <p>All access is on the server thread. A ticket expiration doubles as a
 * safety valve: if an owner stops refreshing (for example because of a crash),
 * the ticket disappears without leaving an orphan.
 */
public final class ChunkTicketManager {
	/**
	 * Radius that makes the centre chunk entity-ticking: full-chunk level 33 minus
	 * the entity-ticking level 31. A higher level would load without ticking.
	 */
	private static final int ENTITY_TICKING_RADIUS = ChunkLevel.byStatus(FullChunkStatus.FULL)
			- ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
	private static final long TICKET_TIMEOUT_TICKS = 200L;
	/** Refresh every second; the timeout allows ten missed refreshes. */
	private static final long REFRESH_INTERVAL_TICKS = 20L;

	/** Non-persistent: no {@link TicketType#FLAG_PERSIST}. */
	private static final TicketType TRIP_TICKET = new TicketType(
			TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING
					| TicketType.FLAG_SIMULATION
					| TicketType.FLAG_CAN_EXPIRE_IF_UNLOADED
	);

	public sealed interface Update {
		/** {@code enabled} is false when the configured budget disables tickets. */
		record Applied(boolean enabled, int tickets, int pending) implements Update {
		}

		/** The full window cannot fit into the shared budget; no partial acquire happens. */
		record BudgetExceeded(int requested, int budget) implements Update {
		}
	}

	public record TripStats(
			TripId tripId,
			int budget,
			int windowChunks,
			int tickets,
			int pendingChunks,
			long acquisitions,
			long releases,
			long readinessChecks
	) {
	}

	private static final class TripState {
		final ServerLevel level;
		final Set<ChunkWindow.Key> held = new LinkedHashSet<>();
		List<ChunkWindow.Key> window = List.of();
		int budget;
		long acquisitions;
		long releases;
		long readinessChecks;
		long lastRefreshTick = Long.MIN_VALUE;

		TripState(ServerLevel level) {
			this.level = level;
		}
	}

	private final Logger logger;
	private final Map<TripId, TripState> trips = new LinkedHashMap<>();
	private final Map<ChunkWindow.Key, Integer> references = new HashMap<>();

	public ChunkTicketManager(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Reconciles a trip's held tickets with its desired window.
	 *
	 * @param provenRoute chunk keys covered by the scanned route; window keys outside
	 *                    this set are ignored so a ticket can never target an
	 *                    unscanned (possibly non-existent) chunk
	 * @param window      local window computed from the route at the current step
	 * @param budget      shared configured limit, {@code <= 0} disables tickets
	 */
	public Update update(ServerLevel level, TripId tripId, Set<ChunkWindow.Key> provenRoute,
			List<ChunkWindow.Key> window, int budget) {
		if (budget <= 0) {
			release(tripId);
			return new Update.Applied(false, 0, 0);
		}

		TripState state = stateFor(level, tripId);
		state.budget = budget;
		List<ChunkWindow.Key> desired = new ArrayList<>(window.size());
		Set<ChunkWindow.Key> desiredSet = new LinkedHashSet<>();
		for (ChunkWindow.Key key : window) {
			if (provenRoute.contains(key) && desiredSet.add(key)) {
				desired.add(key);
			}
		}
		if (desired.isEmpty()) {
			release(tripId);
			return new Update.Applied(true, 0, 0);
		}

		// Distinct chunks after reconciliation: current references minus this trip's
		// holdings, plus any desired chunk this trip would newly hold (including the
		// chunks that stay in the window and are refcounted only by this trip).
		int projected = references.size() - state.held.size();
		for (ChunkWindow.Key key : desired) {
			Integer count = references.get(key);
			if (count == null || (count == 1 && state.held.contains(key))) {
				projected++;
			}
		}
		if (desired.size() > budget || projected > budget) {
			logger.warn("Trip {} needs {} chunk ticket(s), shared budget is {} ({} in use)",
					tripId, desired.size(), budget, references.size());
			return new Update.BudgetExceeded(Math.max(desired.size(), projected), budget);
		}

		// Give back chunks that left the window before taking new ones, so the
		// shared count never transiently exceeds the budget.
		for (ChunkWindow.Key key : List.copyOf(state.held)) {
			if (!desiredSet.contains(key)) {
				releaseReference(level, key);
				state.held.remove(key);
				state.releases++;
			}
		}

		int pending = 0;
		long now = level.getServer().getTickCount();
		boolean refresh = now - state.lastRefreshTick >= REFRESH_INTERVAL_TICKS
				|| state.lastRefreshTick == Long.MIN_VALUE;
		for (ChunkWindow.Key key : desired) {
			if (state.held.contains(key)) {
				if (refresh) {
					refresh(level, key);
				}
			} else {
				acquire(level, key);
				state.held.add(key);
				state.acquisitions++;
			}
			if (!isChunkReady(level, key)) {
				pending++;
			}
		}
		if (refresh) {
			state.lastRefreshTick = now;
		}
		state.window = List.copyOf(desired);
		return new Update.Applied(true, state.held.size(), pending);
	}

	/** Releases every ticket owned by the trip. Safe when the trip holds none. */
	public void release(TripId tripId) {
		TripState state = trips.remove(tripId);
		if (state == null) {
			return;
		}
		for (ChunkWindow.Key key : state.held) {
			releaseReference(state.level, key);
		}
		state.held.clear();
		state.window = List.of();
	}

	public void releaseAll() {
		for (TripId tripId : List.copyOf(trips.keySet())) {
			release(tripId);
		}
		references.clear();
	}

	/** Number of distinct chunks currently held by Railway across all trips. */
	public int ticketCount() {
		return references.size();
	}

	public int tripTicketCount(TripId tripId) {
		TripState state = trips.get(tripId);
		return state == null ? 0 : state.held.size();
	}

	public List<ChunkWindow.Key> window(TripId tripId) {
		TripState state = trips.get(tripId);
		return state == null ? List.of() : state.window;
	}

	/** Window chunks that are not entity-ticking/ready yet. */
	public int pendingCount(TripId tripId) {
		TripState state = trips.get(tripId);
		if (state == null) {
			return 0;
		}
		int pending = 0;
		for (ChunkWindow.Key key : state.window) {
			if (!isChunkReady(state.level, key)) {
				pending++;
			}
		}
		return pending;
	}

	public Optional<TripStats> stats(TripId tripId) {
		TripState state = trips.get(tripId);
		if (state == null) {
			return Optional.empty();
		}
		return Optional.of(new TripStats(tripId, state.budget, state.window.size(), state.held.size(),
				pendingCount(tripId), state.acquisitions, state.releases, state.readinessChecks));
	}

	public long readinessChecks(TripId tripId) {
		TripState state = trips.get(tripId);
		return state == null ? 0L : state.readinessChecks;
	}

	/**
	 * Position readiness for motion gating: loaded, entity-ticking and with the
	 * entity section actually loaded. Counts the check for diagnostics.
	 */
	public boolean isPositionReady(TripId tripId, ServerLevel level, BlockLocation location) {
		TripState state = trips.get(tripId);
		if (state != null) {
			state.readinessChecks++;
		}
		BlockPos pos = RailWorld.pos(location);
		return level.isLoaded(pos)
				&& level.isPositionEntityTicking(pos)
				&& level.areEntitiesActuallyLoadedAndTicking(ChunkPos.containing(pos));
	}

	public static boolean isChunkReady(ServerLevel level, ChunkWindow.Key key) {
		ChunkPos pos = new ChunkPos(key.x(), key.z());
		return level.getChunkSource().hasChunk(key.x(), key.z())
				&& level.getChunkSource().isPositionTicking(pos.pack())
				&& level.areEntitiesActuallyLoadedAndTicking(pos);
	}

	private TripState stateFor(ServerLevel level, TripId tripId) {
		TripState state = trips.get(tripId);
		if (state != null && state.level != level) {
			logger.warn("Trip {} changed level; dropping its old chunk tickets", tripId);
			release(tripId);
			state = null;
		}
		if (state == null) {
			state = new TripState(level);
			trips.put(tripId, state);
		}
		return state;
	}

	private void acquire(ServerLevel level, ChunkWindow.Key key) {
		level.getChunkSource().addTicketWithRadius(TRIP_TICKET, new ChunkPos(key.x(), key.z()),
				ENTITY_TICKING_RADIUS);
		references.merge(key, 1, Integer::sum);
	}

	/**
	 * Re-adding an identical ticket refreshes its timeout in vanilla, so an owner
	 * only has to call {@link #update} once per tick to keep the window alive.
	 */
	private void refresh(ServerLevel level, ChunkWindow.Key key) {
		level.getChunkSource().addTicketWithRadius(TRIP_TICKET, new ChunkPos(key.x(), key.z()),
				ENTITY_TICKING_RADIUS);
	}

	private void releaseReference(ServerLevel level, ChunkWindow.Key key) {
		Integer count = references.get(key);
		if (count == null) {
			return;
		}
		if (count <= 1) {
			references.remove(key);
			level.getChunkSource().removeTicketWithRadius(TRIP_TICKET, new ChunkPos(key.x(), key.z()),
					ENTITY_TICKING_RADIUS);
		} else {
			references.put(key, count - 1);
		}
	}
}
