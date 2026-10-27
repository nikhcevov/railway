package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.network.GraphRailView;
import dev.nikhcevov.railway.network.GraphScanner;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.persistence.Config;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tick-batched graph scan jobs. Graphs are derived caches and are never persisted.
 *
 * <p>A scan that reaches an unloaded candidate pauses instead of failing. Only
 * the chunks of the immediate unloaded frontier are ticketed, and only after a
 * strict level-storage probe proves they exist; the whole route is never held
 * loaded. Tickets are released on completion, failure, invalidation and shutdown.
 */
public final class ScanService {
	/**
	 * Upper bound on scan tickets, independent of {@code maxManagedChunkTickets}.
	 * A frontier only touches the chunks around its blocked positions, so a small
	 * cap is sufficient; it also guarantees that scans cannot starve trip windows.
	 */
	public static final int MAX_SCAN_TICKETS = 32;

	/**
	 * Station snapshot used to recognise other anchored stations that share a
	 * scanned component. The snapshot is read only when a scan completes.
	 */
	@FunctionalInterface
	public interface StationSource {
		Collection<Station> stations();
	}

	/**
	 * One diagnostics row. {@code station} is the station being described;
	 * {@code coveredBy} is non-null when its graph comes from another station's
	 * job, and {@code covered} lists the stations this job itself covers.
	 */
	public record Status(String jobId, StationId station, int rails, int switches, boolean running,
			boolean waiting, int pendingChunks, boolean complete, Failure failure, RailGraph graph,
			long startedTick, long revision, StationId coveredBy, List<StationId> covered) {
		public Status {
			covered = covered == null ? List.of() : List.copyOf(covered);
		}
	}

	/** A station that reuses another station's completed component graph. */
	private record Alias(StationId owner, Station station) {
	}

	private static final class Job {
		final String id;
		final StationId station;
		final ServerLevel level;
		final ScanView view;
		final int railsPerTick;
		final long budgetNanos;
		final int ticketBudget;
		final long startedTick;
		final long revision;
		GraphScanner scanner;
		RailGraph graph;
		Failure failure;

		Job(String id, StationId station, ServerLevel level, ScanView view, int railsPerTick,
				long budgetNanos, int ticketBudget, long startedTick, long revision) {
			this.id = id;
			this.station = station;
			this.level = level;
			this.view = view;
			this.railsPerTick = railsPerTick;
			this.budgetNanos = budgetNanos;
			this.ticketBudget = ticketBudget;
			this.startedTick = startedTick;
			this.revision = revision;
		}
	}

	/**
	 * Rail view for one scan. It answers {@link GraphRailView.Probe.Empty} when the
	 * candidate chunk is known to be absent from level storage, because a chunk
	 * that was never saved cannot contain player-built rail. Existing unloaded
	 * chunks are left as {@code Unloaded} so {@link GraphScanner} waits for the
	 * ticket that {@link ScanService} acquires for them.
	 */
	private static final class ScanView implements GraphRailView {
		private final ServerLevel level;
		private final RailWorld world;
		private final Logger logger;
		/** Server-thread-confined tri-state storage result. */
		private final Map<ChunkWindow.Key, Boolean> onDisk = new HashMap<>();
		private final Set<ChunkWindow.Key> probes = ConcurrentHashMap.newKeySet();
		private boolean closed;

		ScanView(ServerLevel level, Logger logger) {
			this.level = level;
			this.world = new RailWorld(level);
			this.logger = logger;
		}

		@Override
		public Probe graphProbe(BlockLocation pos) {
			Probe probe = world.graphProbe(pos);
			if (!(probe instanceof Probe.Unloaded)) {
				return probe;
			}
			ChunkWindow.Key key = ChunkWindow.Key.of(pos);
			Boolean exists = onDisk.get(key);
			if (Boolean.FALSE.equals(exists)) {
				return GraphRailView.empty();
			}
			if (exists == null) {
				probeStorage(key);
			}
			return probe;
		}

		void probeStorage(ChunkWindow.Key key) {
			if (closed || !probes.add(key)) {
				return;
			}
			LevelStorageProbe.exists(level, new ChunkPos(key.x(), key.z()), exists -> {
				try {
					level.getServer().execute(() -> {
						probes.remove(key);
						if (!closed) {
							onDisk.put(key, exists);
						}
					});
				} catch (RuntimeException e) {
					probes.remove(key);
					logger.warn("Cannot schedule the scan chunk probe result: {}", e.getMessage());
				}
			});
		}

		Boolean storageState(ChunkWindow.Key key) {
			return onDisk.get(key);
		}

		void close() {
			closed = true;
		}
	}

	private final Map<StationId, Job> jobs = new LinkedHashMap<>();
	/** Stations that reuse another station's graph, mapped to the owning station. */
	private final Map<StationId, Alias> aliases = new LinkedHashMap<>();
	/** Startup stations waiting for their turn; removed once covered by a scan. */
	private final Map<StationId, Station> startupPending = new LinkedHashMap<>();
	private final Logger logger;
	private final ChunkTicketManager tickets;
	private final StationSource stationSource;
	private final ScanRetryCooldown retry = new ScanRetryCooldown();
	private Config startupConfig = Config.defaults();
	private long startupRevision;
	private StationId startupActive;
	private int startupStarted;
	private int startupCompleted;
	private int startupCovered;
	private int startupFailed;
	private int startupSkipped;
	private boolean startupSummaryPending;
	private long nextJobId = 1;

	public ScanService(Logger logger, ChunkTicketManager tickets, StationSource stationSource) {
		this.logger = logger;
		this.tickets = tickets;
		this.stationSource = stationSource;
	}

	public sealed interface StartResult {
		record Started(String jobId) implements StartResult {
		}

		record Rejected(Failure failure) implements StartResult {
		}
	}

	public StartResult start(Station station, ServerLevel level, Config config, long tick, long revision) {
		if (station.isProvisional()) {
			return new StartResult.Rejected(
					Failure.detail(FailureCode.STATION_INCOMPLETE, station.displayName()));
		}
		Job existing = jobs.get(station.id());
		if (existing != null && !existing.scanner.isFinished()) {
			return new StartResult.Rejected(Failure.detail(FailureCode.SCAN_RUNNING, station.id().value()));
		}
		// A real scan always supersedes a shared alias: the station now owns a
		// job, and until that job completes it must not report the old graph.
		aliases.remove(station.id());
		startupPending.remove(station.id());
		String jobId = "scan-" + nextJobId++;
		ScanView view = new ScanView(level, logger);
		Job job = new Job(jobId, station.id(), level, view, config.scanRailsPerTick(),
				Math.max(1, config.scanTimeBudgetMs()) * 1_000_000L,
				Math.min(config.maxManagedChunkTickets(), MAX_SCAN_TICKETS), tick, revision);
		job.scanner = new GraphScanner(
				station.dimension(),
				station.dock(),
				view,
				config.maxRailsPerScan(),
				revision
		);
		Job replaced = jobs.put(station.id(), job);
		if (replaced != null) {
			discard(replaced);
		}
		return new StartResult.Started(jobId);
	}

	/**
	 * Starts a background scan when an automatic consumer (a trip launch or a
	 * route check) needs a graph that does not exist yet. Unlike the manual
	 * command this honours {@link ScanRetryCooldown}, so a failed geometry scan
	 * is not restarted on every button press.
	 *
	 * <p>The caller must only use this when {@link #graph(StationId)} is null;
	 * a running job is never duplicated.
	 *
	 * @return {@link StartResult.Started} when a new scan was launched, otherwise
	 *         the failure to report to the caller
	 */
	public StartResult autoStart(Station station, ServerLevel level, Config config, long tick, long revision) {
		StationId id = station.id();
		if (graph(id) != null) {
			// A shared component graph already answers this station; never start
			// a second scan just because it has no job of its own.
			return new StartResult.Rejected(Failure.detail(FailureCode.SCAN_RUNNING,
					id.value() + " (graph already available)"));
		}
		if (isRunning(id)) {
			return new StartResult.Rejected(Failure.detail(FailureCode.SCAN_RUNNING, id.value()));
		}
		if (!retry.allows(id, tick)) {
			Failure previous = failure(id);
			return new StartResult.Rejected(previous != null ? previous
					: Failure.detail(FailureCode.SCAN_INCOMPLETE, id.value()));
		}
		StartResult result = start(station, level, config, tick, revision);
		if (result instanceof StartResult.Started) {
			retry.recordAttempt(id, tick);
			logger.info("Started automatic scan for station {} at tick {}", id.value(), tick);
		}
		return result;
	}

	/**
	 * Failure reported when an automatic scan was just launched for this attempt.
	 * The scan runs in the background; the player only needs to try again later.
	 */
	public static Failure autoStartedFailure(StationId station) {
		return Failure.detail(FailureCode.SCAN_RUNNING,
				station.value() + " (scan started automatically)");
	}

	/**
	 * Queues startup scans and starts the first one. Only one startup scan runs at
	 * a time; when it succeeds, every anchored station whose dock is on the same
	 * component reuses its graph instead of scanning again. Provisional stations
	 * are skipped, and a station whose dimension is not loaded is logged and
	 * skipped.
	 */
	public void queueStartup(Collection<Station> stations, Config config, MinecraftServer server, long revision) {
		startupConfig = config;
		startupRevision = revision;
		int queued = 0;
		for (Station station : stations) {
			if (!station.isAnchored()) {
				continue;
			}
			if (graph(station.id()) != null || isRunning(station.id())) {
				continue;
			}
			if (RailWorld.level(server, station.dimension()) == null) {
				logger.warn("Cannot auto-scan station {}: dimension {} is not loaded",
						station.id().value(), station.dimension());
				startupSkipped++;
				continue;
			}
			startupPending.put(station.id(), station);
			queued++;
		}
		if (queued > 0) {
			startupSummaryPending = true;
			logger.info("Startup scan queue: {} station(s); one scan per connected component", queued);
		}
		pumpStartup(server, server.getTickCount());
	}

	private void pumpStartup(MinecraftServer server, long tick) {
		if (startupActive != null) {
			Job active = jobs.get(startupActive);
			if (active != null && !active.scanner.isFinished()) {
				return;
			}
			startupActive = null;
		}
		Iterator<Station> iterator = startupPending.values().iterator();
		while (iterator.hasNext()) {
			Station station = iterator.next();
			iterator.remove();
			if (!station.isAnchored()) {
				continue;
			}
			if (graph(station.id()) != null || isRunning(station.id())) {
				// Covered by a scan that finished while this station waited, or a
				// real job (manual/on-demand) is already running for it.
				continue;
			}
			ServerLevel level = RailWorld.level(server, station.dimension());
			if (level == null) {
				logger.warn("Cannot auto-scan station {}: dimension {} is not loaded",
						station.id().value(), station.dimension());
				startupSkipped++;
				continue;
			}
			StartResult result = autoStart(station, level, startupConfig, tick, startupRevision);
			if (result instanceof StartResult.Started) {
				startupActive = station.id();
				startupStarted++;
				return;
			}
			if (result instanceof StartResult.Rejected rejected) {
				logger.warn("Automatic startup scan for station {} did not start: {} {}",
						station.id().value(), rejected.failure().code(), rejected.failure().detail());
				startupSkipped++;
			}
		}
		finishStartupSummary();
	}

	private void finishStartupSummary() {
		if (!startupSummaryPending || startupActive != null) {
			return;
		}
		logger.info("Startup scans finished: {} started, {} completed, {} station(s) covered by shared "
						+ "components, {} failed, {} skipped",
				startupStarted, startupCompleted, startupCovered, startupFailed, startupSkipped);
		startupSummaryPending = false;
		startupStarted = 0;
		startupCompleted = 0;
		startupCovered = 0;
		startupFailed = 0;
		startupSkipped = 0;
	}

	public void tick(MinecraftServer server) {
		long tick = server.getTickCount();
		for (Job job : List.copyOf(jobs.values())) {
			if (job.scanner.isFinished()) {
				continue;
			}
			long deadline = System.nanoTime() + job.budgetNanos;
			job.scanner.advance(job.railsPerTick, deadline);
			if (!job.scanner.isFinished()) {
				reconcileScanChunks(job);
			}
			if (job.scanner.isFinished()) {
				complete(job, tick);
			}
		}
		pumpStartup(server, tick);
	}

	/**
	 * Requests loading tickets for the unloaded frontier chunks that exist on
	 * disk, and releases tickets for chunks that left the frontier. Missing
	 * chunks are not ticketed; {@link ScanView} reports them as empty instead.
	 */
	private void reconcileScanChunks(Job job) {
		if (job.ticketBudget <= 0 && job.scanner.isWaiting()) {
			job.scanner.abort(Failure.detail(FailureCode.CHUNKS_NOT_READY,
					"maxManagedChunkTickets is 0; the scan cannot load unloaded chunks"));
			return;
		}
		Set<ChunkWindow.Key> desired = new LinkedHashSet<>();
		for (BlockLocation pos : job.scanner.pendingPositions()) {
			ChunkWindow.Key key = ChunkWindow.Key.of(pos);
			if (!job.level.getChunkSource().hasChunk(key.x(), key.z())) {
				Boolean onDisk = job.view.storageState(key);
				if (Boolean.FALSE.equals(onDisk)) {
					continue;
				}
				if (onDisk == null) {
					job.view.probeStorage(key);
					continue;
				}
			}
			// Keep a chunk that just loaded ticketed until the scanner actually
			// re-probes it, so a ticket release cannot unload it under the scan.
			desired.add(key);
		}
		ChunkTicketManager.Update update = tickets.updateScan(job.level, job.id, desired, job.ticketBudget);
		if (update instanceof ChunkTicketManager.Update.BudgetExceeded exceeded) {
			job.scanner.abort(Failure.detail(FailureCode.CHUNKS_NOT_READY,
					"scan chunk ticket budget " + exceeded.budget() + " exceeded (needs "
							+ exceeded.requested() + ")"));
		}
	}

	private void complete(Job job, long tick) {
		tickets.releaseScan(job.id);
		job.view.close();
		boolean startupJob = job.station.equals(startupActive);
		if (job.scanner.isFailed()) {
			job.failure = job.scanner.failure();
			// A failed component must not leave stations aliased to a graph that
			// no longer exists; they can start a real scan on demand.
			removeAliasesOwnedBy(job.station);
			// Keep a failed geometry scan from being restarted by every button
			// press; a manual `/rail network scan` still bypasses the cooldown.
			retry.recordFailure(job.station, tick);
			logger.warn("Scan {} for station {} failed: {} at {}{}", job.id, job.station,
					job.failure.code(), job.failure.location() == null ? "-" : job.failure.location().shortString(),
					job.failure.detail() == null ? "" : ": " + job.failure.detail());
			if (startupJob) {
				startupFailed++;
				startupActive = null;
			}
		} else {
			job.graph = job.scanner.graph();
			retry.clear(job.station);
			int pendingBefore = startupPending.size();
			int newlyCovered = coverStations(job);
			int pendingCovered = pendingBefore - startupPending.size();
			if (pendingCovered > 0 && startupSummaryPending) {
				startupCovered += pendingCovered;
			}
			logger.info("Scan {} for station {} complete: {} rails, {} switches{}",
					job.id, job.station, job.graph.rails().size(), job.graph.switches().size(),
					newlyCovered == 0 ? "" : "; covers " + newlyCovered
							+ " station(s) on the same component");
			if (startupJob) {
				startupCompleted++;
				startupActive = null;
			}
		}
	}

	/**
	 * Marks every known anchored station whose dock is on this graph as covered
	 * by the job's graph. Existing aliases of this job are revalidated against the
	 * new graph, so a rescan can never leave a station pointing at a component
	 * that no longer contains its dock.
	 *
	 * @return number of stations newly aliased to this job
	 */
	private int coverStations(Job job) {
		RailGraph graph = job.graph;
		if (graph == null) {
			return 0;
		}
		Map<StationId, Station> candidates = new LinkedHashMap<>();
		for (Station station : stationSource.stations()) {
			if (station.isAnchored() && station.dimension().equals(graph.dimension())) {
				candidates.put(station.id(), station);
			}
		}
		for (Station station : startupPending.values()) {
			if (station.isAnchored() && station.dimension().equals(graph.dimension())) {
				candidates.put(station.id(), station);
			}
		}

		Iterator<Map.Entry<StationId, Alias>> existing = aliases.entrySet().iterator();
		while (existing.hasNext()) {
			Map.Entry<StationId, Alias> entry = existing.next();
			Alias alias = entry.getValue();
			if (!alias.owner().equals(job.station)) {
				continue;
			}
			Station station = candidates.get(entry.getKey());
			if (station == null || graph.rail(station.dock()) == null) {
				existing.remove();
			}
		}

		int newlyCovered = 0;
		for (Map.Entry<StationId, Station> entry : candidates.entrySet()) {
			StationId id = entry.getKey();
			Station station = entry.getValue();
			if (id.equals(job.station) || graph.rail(station.dock()) == null) {
				continue;
			}
			Job own = jobs.get(id);
			if (own != null) {
				if (own.graph != null || !own.scanner.isFinished()) {
					continue;
				}
				// A failed scan is superseded by a working component graph.
				jobs.remove(id);
				retry.clear(id);
			}
			Alias previous = aliases.put(id, new Alias(job.station, station));
			startupPending.remove(id);
			if (previous == null || !previous.owner().equals(job.station)) {
				newlyCovered++;
			}
		}
		return newlyCovered;
	}

	private void removeAliasesOwnedBy(StationId owner) {
		aliases.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
	}

	private void discard(Job job) {
		tickets.releaseScan(job.id);
		job.view.close();
	}

	public Optional<Status> status(StationId station) {
		Job job = jobs.get(station);
		if (job != null && (job.graph != null || !job.scanner.isFinished())) {
			return Optional.of(status(job, station, null));
		}
		StationId owner = liveOwner(station);
		if (owner != null) {
			Job ownerJob = jobs.get(owner);
			if (ownerJob != null) {
				return Optional.of(status(ownerJob, station, owner));
			}
		}
		return job == null ? Optional.empty() : Optional.of(status(job, station, null));
	}

	private Status status(Job job, StationId station, StationId coveredBy) {
		RailGraph graph = job.graph;
		int pendingChunks = pendingChunks(job.scanner);
		return new Status(job.id, station, job.scanner.railCount(),
				graph == null ? 0 : graph.switches().size(), !job.scanner.isFinished(),
				job.scanner.isWaiting(), pendingChunks, graph != null,
				job.failure, graph, job.startedTick, job.revision, coveredBy,
				coveredBy == null && graph != null ? coveredStations(job.station) : List.of());
	}

	private static int pendingChunks(GraphScanner scanner) {
		Set<ChunkWindow.Key> keys = new HashSet<>();
		for (BlockLocation pos : scanner.pendingPositions()) {
			keys.add(ChunkWindow.Key.of(pos));
		}
		return keys.size();
	}

	private List<StationId> coveredStations(StationId owner) {
		List<StationId> result = new ArrayList<>();
		for (Map.Entry<StationId, Alias> entry : aliases.entrySet()) {
			if (entry.getValue().owner().equals(owner)) {
				result.add(entry.getKey());
			}
		}
		return result;
	}

	/** Real jobs only; covered stations are reported through their owner's row. */
	public List<Status> all() {
		List<Status> result = new ArrayList<>();
		for (Map.Entry<StationId, Job> entry : jobs.entrySet()) {
			result.add(status(entry.getValue(), entry.getKey(), null));
		}
		return result;
	}

	public RailGraph graph(StationId station) {
		Job job = jobs.get(station);
		if (job != null && job.graph != null) {
			return job.graph;
		}
		StationId owner = liveOwner(station);
		if (owner == null) {
			return null;
		}
		Job ownerJob = jobs.get(owner);
		return ownerJob == null ? null : ownerJob.graph;
	}

	/**
	 * Resolves a live alias. Missing owners and failed jobs invalidate the alias
	 * instead of returning a stale component graph.
	 */
	private StationId liveOwner(StationId station) {
		Alias alias = aliases.get(station);
		if (alias == null) {
			return null;
		}
		Job ownerJob = jobs.get(alias.owner());
		if (ownerJob == null || (ownerJob.scanner.isFinished() && ownerJob.graph == null)) {
			aliases.remove(station);
			return null;
		}
		return alias.owner();
	}

	public Failure failure(StationId station) {
		Job job = jobs.get(station);
		if (job != null && job.graph == null && job.failure != null) {
			return job.failure;
		}
		StationId owner = liveOwner(station);
		if (owner != null) {
			Job ownerJob = jobs.get(owner);
			return ownerJob == null ? null : ownerJob.failure;
		}
		return null;
	}

	public boolean isRunning(StationId station) {
		Job job = jobs.get(station);
		if (job != null && !job.scanner.isFinished()) {
			return true;
		}
		StationId owner = liveOwner(station);
		if (owner != null) {
			Job ownerJob = jobs.get(owner);
			return ownerJob != null && !ownerJob.scanner.isFinished();
		}
		return false;
	}

	/**
	 * Invalidating any station that uses a shared graph removes the graph for the
	 * owner and for every other station aliased to it, so no station can keep a
	 * stale component.
	 */
	public void invalidate(StationId station) {
		Alias alias = aliases.remove(station);
		if (alias != null) {
			Job own = jobs.remove(station);
			if (own != null) {
				discard(own);
			}
			retry.clear(station);
			invalidateShared(alias.owner());
			return;
		}
		invalidateShared(station);
	}

	private void invalidateShared(StationId owner) {
		Job job = jobs.remove(owner);
		if (job != null) {
			discard(job);
		}
		retry.clear(owner);
		removeAliasesOwnedBy(owner);
		startupPending.remove(owner);
		if (owner.equals(startupActive)) {
			startupActive = null;
		}
	}

	public void invalidate(BlockLocation location) {
		Set<StationId> affected = new LinkedHashSet<>();
		for (Job job : jobs.values()) {
			if (job.graph != null && job.graph.rail(location) != null) {
				affected.add(job.station);
			}
		}
		for (StationId owner : affected) {
			invalidateShared(owner);
		}
	}

	public void clear() {
		for (Job job : jobs.values()) {
			discard(job);
		}
		jobs.clear();
		aliases.clear();
		startupPending.clear();
		startupActive = null;
		startupSummaryPending = false;
		startupStarted = 0;
		startupCompleted = 0;
		startupCovered = 0;
		startupFailed = 0;
		startupSkipped = 0;
		retry.clear();
	}
}
