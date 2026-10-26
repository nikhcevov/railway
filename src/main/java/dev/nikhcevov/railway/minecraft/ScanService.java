package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.network.LineScan;
import dev.nikhcevov.railway.network.StraightLineScanner;
import dev.nikhcevov.railway.persistence.Config;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tick-batched scan jobs. Completed lines are derived caches and are never persisted. */
public final class ScanService {
	public record Status(String jobId, StationId station, int rails, boolean running, boolean complete,
			Failure failure, LineScan line, long startedTick, long revision) {
	}

	private static final class Job {
		final String id;
		final StationId station;
		final StraightLineScanner scanner;
		final int railsPerTick;
		final long budgetNanos;
		final long startedTick;
		final long revision;
		LineScan line;
		Failure failure;

		Job(String id, StationId station, StraightLineScanner scanner, int railsPerTick, long budgetNanos,
				long startedTick, long revision) {
			this.id = id;
			this.station = station;
			this.scanner = scanner;
			this.railsPerTick = railsPerTick;
			this.budgetNanos = budgetNanos;
			this.startedTick = startedTick;
			this.revision = revision;
		}
	}

	private final Map<StationId, Job> jobs = new LinkedHashMap<>();
	private final Logger logger;
	private long nextJobId = 1;

	public ScanService(Logger logger) {
		this.logger = logger;
	}

	public sealed interface StartResult {
		record Started(String jobId) implements StartResult {
		}

		record Rejected(Failure failure) implements StartResult {
		}
	}

	public StartResult start(Station station, ServerLevel level, Config config, long tick, long revision) {
		Job existing = jobs.get(station.id());
		if (existing != null && !existing.scanner.isFinished()) {
			return new StartResult.Rejected(Failure.detail(FailureCode.SCAN_RUNNING, station.id().value()));
		}
		String jobId = "scan-" + nextJobId++;
		StraightLineScanner scanner = new StraightLineScanner(
				station.dimension(),
				station.dock(),
				new RailWorld(level),
				config.maxRailsPerScan()
		);
		Job job = new Job(jobId, station.id(), scanner, config.scanRailsPerTick(),
				Math.max(1, config.scanTimeBudgetMs()) * 1_000_000L, tick, revision);
		jobs.put(station.id(), job);
		return new StartResult.Started(jobId);
	}

	public void tick(MinecraftServer server) {
		for (Job job : jobs.values()) {
			if (job.scanner.isFinished()) {
				continue;
			}
			long deadline = System.nanoTime() + job.budgetNanos;
			job.scanner.advance(job.railsPerTick, deadline);
			if (job.scanner.isFinished()) {
				if (job.scanner.isFailed()) {
					job.failure = job.scanner.failure();
					logger.warn("Scan {} for station {} failed: {} at {}", job.id, job.station,
							job.failure.code(), job.failure.location() == null ? "-" : job.failure.location().shortString());
				} else {
					job.line = job.scanner.result();
					logger.info("Scan {} for station {} complete: {} rails", job.id, job.station, job.line.size());
				}
			}
		}
	}

	public Optional<Status> status(StationId station) {
		Job job = jobs.get(station);
		if (job == null) {
			return Optional.empty();
		}
		return Optional.of(new Status(job.id, job.station, job.scanner.railCount(), !job.scanner.isFinished(),
				job.line != null, job.failure, job.line, job.startedTick, job.revision));
	}

	public List<Status> all() {
		List<Status> result = new ArrayList<>();
		for (StationId id : jobs.keySet()) {
			status(id).ifPresent(result::add);
		}
		return result;
	}

	public LineScan line(StationId station) {
		Job job = jobs.get(station);
		return job == null ? null : job.line;
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
		jobs.remove(station);
	}

	public void invalidate(BlockLocation location) {
		// A block change invalidates every completed line that contains it. Lines are
		// cheap derived caches; route dispatch revalidates the full route anyway.
		List<StationId> affected = new ArrayList<>();
		for (Job job : jobs.values()) {
			if (job.line != null && job.line.contains(location)) {
				affected.add(job.station);
			}
		}
		affected.forEach(jobs::remove);
	}

	public void clear() {
		jobs.clear();
	}
}
