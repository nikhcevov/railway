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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns every chunk ticket Railway creates for trips, recovery and scans. Tickets
 * are server-only and non-persistent. Trip tickets are entity-ticking simulation
 * tickets for the local route window only; scan tickets are loading tickets for
 * the immediate unloaded frontier of a scan. A caller may only ticket chunks that
 * already exist in level storage; the manager itself never probes, forces chunks
 * or requests generation.
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

	/**
	 * One-shot ticket used by crash recovery to read the checkpoint chunk. It is
	 * refreshed while recovery retries, expires on its own, and is never persisted.
	 * Recovery only requests it for a chunk confirmed to exist in level storage, so
	 * it cannot generate terrain.
	 */
	private static final TicketType RECOVERY_TICKET = new TicketType(
			TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING
					| TicketType.FLAG_SIMULATION
					| TicketType.FLAG_CAN_EXPIRE_IF_UNLOADED
	);

	/**
	 * Loading-only ticket for the unloaded frontier of a scan. No simulation flag:
	 * a scan reads block states and never needs chunk ticking. Radius {@code 0}
	 * loads exactly the requested chunk to full status.
	 */
	private static final TicketType SCAN_TICKET = new TicketType(
			TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING
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

	/** One recovery ticket held for one checkpoint. */
	private static final class RecoveryState {
		final ServerLevel level;
		final Set<ChunkWindow.Key> held = new LinkedHashSet<>();
		long acquisitions;
		long releases;

		RecoveryState(ServerLevel level) {
			this.level = level;
		}
	}

	public record RecoveryStats(
			int checkpoints,
			int tickets,
			long acquisitions,
			long releases
	) {
	}

	/** Tickets one scan job holds for its current unloaded frontier. */
	private static final class ScanState {
		final ServerLevel level;
		final Set<ChunkWindow.Key> held = new LinkedHashSet<>();
		long acquisitions;
		long releases;
		long lastRefreshTick = Long.MIN_VALUE;

		ScanState(ServerLevel level) {
			this.level = level;
		}
	}

	public record ScanStats(
			int jobs,
			int tickets,
			long acquisitions,
			long releases
	) {
	}

	private final Logger logger;
	private final Map<TripId, TripState> trips = new LinkedHashMap<>();
	private final Map<ChunkWindow.Key, Integer> references = new HashMap<>();
	private final Map<TripId, RecoveryState> recovery = new LinkedHashMap<>();
	private final Map<ChunkWindow.Key, Integer> recoveryReferences = new HashMap<>();
	private final Map<String, ScanState> scans = new LinkedHashMap<>();
	private final Map<ChunkWindow.Key, Integer> scanReferences = new HashMap<>();

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

		// Distinct chunks after reconciliation across trips, recovery and scans:
		// current references minus this trip's holdings, plus every desired chunk
		// this trip would newly hold.
		int projected = projectedTripTicketCount(state.held, desiredSet);
		if (desired.size() > budget || projected > budget) {
			logger.warn("Trip {} needs {} chunk ticket(s), shared budget is {} ({} in use)",
					tripId, desired.size(), budget, managedChunkCount());
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
		releaseAllRecovery();
		releaseAllScans();
	}

	// ------------------------------------------------------------------ scans

	/**
	 * Reconciles a scan job's tickets with the chunks of its current unloaded
	 * frontier. The caller must pass only chunks confirmed present in level
	 * storage and not loaded yet; this method only makes existing chunks readable.
	 *
	 * @param budget shared managed-ticket limit, {@code <= 0} disables tickets
	 * @return how many frontier chunks the job currently holds
	 */
	public Update updateScan(ServerLevel level, String owner, Set<ChunkWindow.Key> desired, int budget) {
		if (budget <= 0) {
			releaseScan(owner);
			return new Update.Applied(false, 0, 0);
		}
		Set<ChunkWindow.Key> desiredSet = new LinkedHashSet<>(desired);
		if (desiredSet.isEmpty()) {
			releaseScan(owner);
			return new Update.Applied(true, 0, 0);
		}
		// Budget rejection must not create an empty scan state that later shows up
		// in diagnostics as an owner.
		ScanState existing = scans.get(owner);
		Set<ChunkWindow.Key> held = existing == null || existing.level != level ? Set.of() : existing.held;
		int projected = projectedScanTicketCount(held, desiredSet);
		if (desiredSet.size() > budget || projected > budget) {
			logger.warn("Scan {} needs {} chunk ticket(s), shared budget is {} ({} in use)",
					owner, desiredSet.size(), budget, managedChunkCount());
			return new Update.BudgetExceeded(Math.max(desiredSet.size(), projected), budget);
		}
		ScanState state = scanStateFor(level, owner);

		for (ChunkWindow.Key key : List.copyOf(state.held)) {
			if (!desiredSet.contains(key)) {
				releaseScanReference(level, key);
				state.held.remove(key);
				state.releases++;
			}
		}

		long now = level.getServer().getTickCount();
		boolean refresh = now - state.lastRefreshTick >= REFRESH_INTERVAL_TICKS
				|| state.lastRefreshTick == Long.MIN_VALUE;
		for (ChunkWindow.Key key : desiredSet) {
			if (state.held.contains(key)) {
				if (refresh) {
					refreshScan(level, key);
				}
			} else {
				acquireScan(level, key);
				state.held.add(key);
				state.acquisitions++;
			}
		}
		if (refresh) {
			state.lastRefreshTick = now;
		}
		return new Update.Applied(true, state.held.size(), 0);
	}

	/** Releases every ticket owned by a scan job. Safe when it holds none. */
	public void releaseScan(String owner) {
		ScanState state = scans.remove(owner);
		if (state == null) {
			return;
		}
		for (ChunkWindow.Key key : state.held) {
			releaseScanReference(state.level, key);
		}
		state.held.clear();
	}

	public void releaseAllScans() {
		for (String owner : List.copyOf(scans.keySet())) {
			releaseScan(owner);
		}
		scanReferences.clear();
	}

	/** Number of distinct chunks currently held for scan frontiers. */
	public int scanTicketCount() {
		return scanReferences.size();
	}

	/** Chunks one scan owner currently holds; 0 when it holds none. */
	public int scanTicketCount(String owner) {
		ScanState state = scans.get(owner);
		return state == null ? 0 : state.held.size();
	}

	public ScanStats scanStats() {
		int tickets = 0;
		long acquisitions = 0;
		long releases = 0;
		for (ScanState state : scans.values()) {
			tickets += state.held.size();
			acquisitions += state.acquisitions;
			releases += state.releases;
		}
		return new ScanStats(scans.size(), tickets, acquisitions, releases);
	}

	/** Distinct chunks held across trips, recovery and scans. */
	public int managedChunkCount() {
		return unionKeys().size();
	}

	/**
	 * Distinct chunks that would be held after a trip release/reacquire step.
	 * A chunk stays counted while another owner still references it, so the
	 * shared budget counts each chunk once.
	 */
	private int projectedTripTicketCount(Set<ChunkWindow.Key> held, Set<ChunkWindow.Key> desired) {
		Set<ChunkWindow.Key> union = unionKeys();
		for (ChunkWindow.Key key : held) {
			if (references.getOrDefault(key, 0) == 1
					&& !recoveryReferences.containsKey(key)
					&& !scanReferences.containsKey(key)) {
				union.remove(key);
			}
		}
		union.addAll(desired);
		return union.size();
	}

	/** Same projection for a scan job releasing/reacquiring its own frontier. */
	private int projectedScanTicketCount(Set<ChunkWindow.Key> held, Set<ChunkWindow.Key> desired) {
		Set<ChunkWindow.Key> union = unionKeys();
		for (ChunkWindow.Key key : held) {
			if (scanReferences.getOrDefault(key, 0) == 1
					&& !references.containsKey(key)
					&& !recoveryReferences.containsKey(key)) {
				union.remove(key);
			}
		}
		union.addAll(desired);
		return union.size();
	}

	private Set<ChunkWindow.Key> unionKeys() {
		Set<ChunkWindow.Key> union = new HashSet<>();
		union.addAll(references.keySet());
		union.addAll(recoveryReferences.keySet());
		union.addAll(scanReferences.keySet());
		return union;
	}

	private ScanState scanStateFor(ServerLevel level, String owner) {
		ScanState state = scans.get(owner);
		if (state != null && state.level != level) {
			logger.warn("Scan {} changed level; dropping its old chunk tickets", owner);
			releaseScan(owner);
			state = null;
		}
		if (state == null) {
			state = new ScanState(level);
			scans.put(owner, state);
		}
		return state;
	}

	private void acquireScan(ServerLevel level, ChunkWindow.Key key) {
		level.getChunkSource().addTicketWithRadius(SCAN_TICKET, new ChunkPos(key.x(), key.z()), 0);
		scanReferences.merge(key, 1, Integer::sum);
	}

	private void refreshScan(ServerLevel level, ChunkWindow.Key key) {
		level.getChunkSource().addTicketWithRadius(SCAN_TICKET, new ChunkPos(key.x(), key.z()), 0);
	}

	private void releaseScanReference(ServerLevel level, ChunkWindow.Key key) {
		Integer count = scanReferences.get(key);
		if (count == null) {
			return;
		}
		if (count <= 1) {
			scanReferences.remove(key);
			level.getChunkSource().removeTicketWithRadius(SCAN_TICKET, new ChunkPos(key.x(), key.z()), 0);
		} else {
			scanReferences.put(key, count - 1);
		}
	}

	// --------------------------------------------------------------- recovery

	/**
	 * Requests the one chunk a recovery checkpoint needs. The caller must have
	 * confirmed that the chunk already exists in level storage; this method only
	 * makes an existing chunk readable, it never asks the generator for terrain.
	 *
	 * @param budget shared recovery ticket limit, {@code <= 0} disables recovery tickets
	 * @return whether the checkpoint holds (or already held) its ticket
	 */
	public boolean acquireRecovery(ServerLevel level, TripId tripId, ChunkWindow.Key key, int budget) {
		if (budget <= 0) {
			return false;
		}
		RecoveryState state = recovery.get(tripId);
		if (state != null && state.level != level) {
			releaseRecovery(tripId);
			state = null;
		}
		if (state == null) {
			if (recoveryReferences.size() >= budget) {
				logger.warn("Recovery checkpoint {} needs a ticket, recovery budget is {}", tripId, budget);
				return false;
			}
			state = new RecoveryState(level);
			recovery.put(tripId, state);
		}
		if (state.held.contains(key)) {
			level.getChunkSource().addTicketWithRadius(RECOVERY_TICKET,
					new ChunkPos(key.x(), key.z()), ENTITY_TICKING_RADIUS);
			return true;
		}
		if (recoveryReferences.size() >= budget) {
			logger.warn("Recovery checkpoint {} needs a ticket, recovery budget is {}", tripId, budget);
			return false;
		}
		level.getChunkSource().addTicketWithRadius(RECOVERY_TICKET,
				new ChunkPos(key.x(), key.z()), ENTITY_TICKING_RADIUS);
		recoveryReferences.merge(key, 1, Integer::sum);
		state.held.add(key);
		state.acquisitions++;
		return true;
	}

	/** Releases the recovery ticket of one checkpoint. Safe when none is held. */
	public void releaseRecovery(TripId tripId) {
		RecoveryState state = recovery.remove(tripId);
		if (state == null) {
			return;
		}
		for (ChunkWindow.Key key : state.held) {
			Integer count = recoveryReferences.get(key);
			if (count == null) {
				continue;
			}
			if (count <= 1) {
				recoveryReferences.remove(key);
				state.level.getChunkSource().removeTicketWithRadius(RECOVERY_TICKET,
						new ChunkPos(key.x(), key.z()), ENTITY_TICKING_RADIUS);
			} else {
				recoveryReferences.put(key, count - 1);
			}
			state.releases++;
		}
		state.held.clear();
	}

	public void releaseAllRecovery() {
		for (TripId tripId : List.copyOf(recovery.keySet())) {
			releaseRecovery(tripId);
		}
		recoveryReferences.clear();
	}

	public RecoveryStats recoveryStats() {
		int tickets = 0;
		long acquisitions = 0;
		long releases = 0;
		for (RecoveryState state : recovery.values()) {
			tickets += state.held.size();
			acquisitions += state.acquisitions;
			releases += state.releases;
		}
		return new RecoveryStats(recovery.size(), tickets, acquisitions, releases);
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
