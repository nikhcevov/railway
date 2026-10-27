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
import java.util.HashMap;
import java.util.HashSet;
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

	public record Status(String jobId, StationId station, int rails, int switches, boolean running,
			boolean waiting, int pendingChunks, boolean complete, Failure failure, RailGraph graph,
			long startedTick, long revision) {
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
	private final Logger logger;
	private final ChunkTicketManager tickets;
	private long nextJobId = 1;

	public ScanService(Logger logger, ChunkTicketManager tickets) {
		this.logger = logger;
		this.tickets = tickets;
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

	public void tick(MinecraftServer server) {
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
				complete(job);
			}
		}
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

	private void complete(Job job) {
		tickets.releaseScan(job.id);
		job.view.close();
		if (job.scanner.isFailed()) {
			job.failure = job.scanner.failure();
			logger.warn("Scan {} for station {} failed: {} at {}{}", job.id, job.station,
					job.failure.code(), job.failure.location() == null ? "-" : job.failure.location().shortString(),
					job.failure.detail() == null ? "" : ": " + job.failure.detail());
		} else {
			job.graph = job.scanner.graph();
			logger.info("Scan {} for station {} complete: {} rails, {} switches",
					job.id, job.station, job.graph.rails().size(), job.graph.switches().size());
		}
	}

	private void discard(Job job) {
		tickets.releaseScan(job.id);
		job.view.close();
	}

	public Optional<Status> status(StationId station) {
		Job job = jobs.get(station);
		if (job == null) {
			return Optional.empty();
		}
		RailGraph graph = job.graph;
		int pendingChunks = pendingChunks(job.scanner);
		return Optional.of(new Status(job.id, job.station, job.scanner.railCount(),
				graph == null ? 0 : graph.switches().size(), !job.scanner.isFinished(),
				job.scanner.isWaiting(), pendingChunks, graph != null,
				job.failure, graph, job.startedTick, job.revision));
	}

	private static int pendingChunks(GraphScanner scanner) {
		Set<ChunkWindow.Key> keys = new HashSet<>();
		for (BlockLocation pos : scanner.pendingPositions()) {
			keys.add(ChunkWindow.Key.of(pos));
		}
		return keys.size();
	}

	public List<Status> all() {
		List<Status> result = new ArrayList<>();
		for (StationId id : jobs.keySet()) {
			status(id).ifPresent(result::add);
		}
		return result;
	}

	public RailGraph graph(StationId station) {
		Job job = jobs.get(station);
		return job == null ? null : job.graph;
	}

	public Failure failure(StationId station) {
		Job job = jobs.get(station);
		return job == null ? null : job.failure;
	}

	public boolean isRunning(StationId station) {
		Job job = jobs.get(station);
		return job != null && !job.scanner.isFinished();
	}

	public void invalidate(StationId station) {
		Job job = jobs.remove(station);
		if (job != null) {
			discard(job);
		}
	}

	public void invalidate(BlockLocation location) {
		List<StationId> affected = new ArrayList<>();
		for (Job job : jobs.values()) {
			if (job.graph != null && job.graph.rail(location) != null) {
				affected.add(job.station);
			}
		}
		affected.forEach(this::invalidate);
	}

	public void clear() {
		for (Job job : jobs.values()) {
			discard(job);
		}
		jobs.clear();
	}
}
