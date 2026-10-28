package dev.nikhcevov.railway.fixture;

import dev.nikhcevov.railway.diagnostics.DiagnosticsReport;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.network.RailGraph;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.entity.EntityTypeTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only build/verify driver. Build is synchronous; verify is a tick-driven
 * job because chunk tickets need server ticks to load the unloaded frontier.
 *
 * <p>Verification deliberately invalidates any graph left by a startup scan,
 * waits until the destination chunk is actually unloaded, then launches one
 * normal {@link ScanService} job. That makes the critical acceptance property
 * observable: the scan itself must load the destination chunks and the route
 * must plan from the resulting component.
 */
public final class FixtureService {
	private static final Logger LOGGER = LoggerFactory.getLogger("railway-fixtures");
	private static final long UNLOAD_WAIT_TICKS = 1_200;
	private static final long SCAN_WAIT_TICKS = 2_400;
	private static final long RECOVERY_WAIT_TICKS = 1_200;

	private static VerifyJob active;

	private FixtureService() {
	}

	public record BuildOutcome(LongRouteScenario.BuildResult result, Path report, List<String> failures) {
	}

	public static boolean isVerifyRunning() {
		return active != null;
	}

	/** Clears a job that straddled a server stop; the next server starts fresh. */
	public static void reset() {
		active = null;
	}

	public static Path reportsDir(MinecraftServer server) {
		String configured = System.getProperty("railway.fixtures.dir");
		Path dir = configured == null || configured.isBlank()
				? server.getServerDirectory().resolve("fixtures")
				: Path.of(configured);
		if (!dir.isAbsolute()) {
			dir = server.getServerDirectory().resolve(dir);
		}
		return dir;
	}

	public static Path reportPath(MinecraftServer server, String scenario, String kind) {
		return reportsDir(server).resolve(scenario + "-" + kind + ".json");
	}

	public static BuildOutcome build(Railway railway, ServerLevel level) {
		if (active != null) {
			throw new FixtureException("a fixture verification is already running");
		}
		LongRouteScenario.BuildResult result = LongRouteScenario.build(railway, level);
		List<String> failures = new ArrayList<>(LongRouteScenario.verify(railway, level, true));
		FixtureReport report = buildReport(railway, level, result, failures);
		Path path = writeReport(railway.server(), report);
		return new BuildOutcome(result, path, List.copyOf(failures));
	}

	public static void startVerify(Railway railway, ServerLevel level) {
		if (active != null) {
			throw new FixtureException("a fixture verification is already running");
		}
		List<String> stateFailures = LongRouteScenario.verify(railway, level, false);
		if (!stateFailures.isEmpty()) {
			throw new FixtureException("fixture is not built: " + stateFailures.getFirst());
		}
		active = new VerifyJob(railway, level);
	}

	public static void tick(MinecraftServer server) {
		VerifyJob job = active;
		if (job == null) {
			return;
		}
		job.tick(server);
		if (job.done) {
			active = null;
		}
	}

	public static List<String> listLines(MinecraftServer server) {
		Path verify = reportPath(server, LongRouteScenario.SCENARIO, "verify");
		Path build = reportPath(server, LongRouteScenario.SCENARIO, "build");
		return List.of(
				LongRouteScenario.SCENARIO + ": long route, 512 blocks, two stations, unloaded destination",
				"  home dock " + LongRouteLayout.DEFAULT.homeDock().shortString()
						+ ", destination dock " + LongRouteLayout.DEFAULT.destinationDock().shortString(),
				"  build report " + (Files.isRegularFile(build) ? build : "not written"),
				"  verify report " + (Files.isRegularFile(verify) ? verify : "not written")
						+ (isVerifyRunning() ? " (verify running)" : ""),
				"wide-frontier: not built by this fixture; see docs/fixtures.md for the geometry blocker",
				"reports directory " + reportsDir(server));
	}

	private static FixtureReport buildReport(Railway railway, ServerLevel level,
			LongRouteScenario.BuildResult result, List<String> failures) {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		FixtureReport.Expected expected = expected();
		FixtureReport.Actual actual = new FixtureReport.Actual(
				result.rails(), 2, 2, "not scanned", -1, -1, false,
				level.isLoaded(RailWorld.pos(layout.destinationDock())), false);
		return new FixtureReport(
				LongRouteScenario.SCENARIO,
				"build",
				FixtureMetadata.read(railway.server()),
				RailWorld.dimension(level),
				level.getSeed(),
				layout.origin(),
				expected,
				actual,
				null,
				recovery(railway, level),
				failures.isEmpty(),
				failures);
	}

	private static FixtureReport.Expected expected() {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		return new FixtureReport.Expected(layout.railCount(), 2, 2, layout.routeLength(),
				"one shared component");
	}

	private static FixtureReport.Recovery recovery(Railway railway, ServerLevel level) {
		DiagnosticsReport report = railway.diagnostics().snapshot();
		return new FixtureReport.Recovery(
				report.global().checkpoints(),
				report.trips().size(),
				report.quarantine().size(),
				managedCarts(level),
				report.global().recovery().pending(),
				report.global().recovery().blocking());
	}

	private static int managedCarts(ServerLevel level) {
		EntityTypeTest<Entity, Minecart> type = EntityTypeTest.forClass(Minecart.class);
		return level.getEntities(type,
				cart -> cart.entityTags().contains(PlayerActions.MANAGED_TAG)).size();
	}

	private static Path writeReport(MinecraftServer server, FixtureReport report) {
		Path dir = reportsDir(server);
		Path target = dir.resolve(report.fileName());
		try {
			Files.createDirectories(dir);
			Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.writeString(tmp, report.toJson() + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return target;
		} catch (IOException e) {
			throw new FixtureException("cannot write fixture report " + target + ": " + e.getMessage());
		}
	}

	private static final class VerifyJob {
		private enum Phase {
			WAIT_IDLE,
			SCANNING,
			WAIT_RECOVERY
		}

		private final Railway railway;
		private final ServerLevel level;
		private final LongRouteLayout layout = LongRouteLayout.DEFAULT;
		private final List<String> failures = new ArrayList<>();
		private Phase phase = Phase.WAIT_IDLE;
		private long phaseTick;
		private boolean invalidated;
		private boolean done;
		private boolean destinationLoadedBeforeScan;
		private boolean destinationLoadedAfterScan;
		private boolean destinationRailScanned;
		private String jobId = "";
		private String component = "";
		private int forwardLength = -1;
		private int reverseLength = -1;
		private ScanService.Status scanStatus;

		VerifyJob(Railway railway, ServerLevel level) {
			this.railway = railway;
			this.level = level;
			this.phaseTick = railway.server().getTickCount();
		}

		void tick(MinecraftServer server) {
			if (done) {
				return;
			}
			long tick = server.getTickCount();
			switch (phase) {
				case WAIT_IDLE -> tickWaitIdle(server, tick);
				case SCANNING -> tickScanning(server, tick);
				case WAIT_RECOVERY -> tickWaitRecovery(server, tick);
			}
		}

		private void tickWaitIdle(MinecraftServer server, long tick) {
			ScanService scans = railway.scans();
			if (scans.isRunning(new StationId(LongRouteScenario.HOME_ID))
					|| scans.isRunning(new StationId(LongRouteScenario.MINE_ID))) {
				return;
			}
			if (!invalidated) {
				scans.invalidate(new StationId(LongRouteScenario.HOME_ID));
				scans.invalidate(new StationId(LongRouteScenario.MINE_ID));
				invalidated = true;
			}
			if (level.isLoaded(RailWorld.pos(layout.destinationDock()))) {
				if (elapsed(tick) > UNLOAD_WAIT_TICKS) {
					failures.add("destination chunk did not unload within " + UNLOAD_WAIT_TICKS
							+ " ticks; use simulation-distance 4 and move players away from the fixture");
					finish(server);
				}
				return;
			}
			destinationLoadedBeforeScan = false;
			Station home = railway.state().station(new StationId(LongRouteScenario.HOME_ID)).orElse(null);
			if (home == null) {
				failures.add("home fixture station disappeared before verify");
				finish(server);
				return;
			}
			ScanService.StartResult result = scans.start(home, level, railway.config(), tick,
					railway.state().revision());
			if (result instanceof ScanService.StartResult.Started started) {
				jobId = started.jobId();
				phase = Phase.SCANNING;
				phaseTick = tick;
				return;
			}
			ScanService.StartResult.Rejected rejected = (ScanService.StartResult.Rejected) result;
			if (rejected.failure().code() == FailureCode.SCAN_RUNNING) {
				return;
			}
			failures.add("scan did not start: " + FixtureReport.failureText(rejected.failure()));
			finish(server);
		}

		private void tickScanning(MinecraftServer server, long tick) {
			ScanService scans = railway.scans();
			if (scans.isRunning(new StationId(LongRouteScenario.HOME_ID))
					|| scans.isRunning(new StationId(LongRouteScenario.MINE_ID))) {
				if (elapsed(tick) > SCAN_WAIT_TICKS) {
					scanStatus = scans.status(new StationId(LongRouteScenario.HOME_ID)).orElse(scanStatus);
					failures.add("scan did not finish within " + SCAN_WAIT_TICKS + " ticks");
					finish(server);
				}
				return;
			}
			RailGraph graph = scans.graph(new StationId(LongRouteScenario.HOME_ID));
			if (graph == null) {
				graph = scans.graph(new StationId(LongRouteScenario.MINE_ID));
			}
			scanStatus = scans.status(new StationId(LongRouteScenario.HOME_ID))
					.orElse(scans.status(new StationId(LongRouteScenario.MINE_ID)).orElse(null));
			if (graph == null) {
				Failure failure = scans.failure(new StationId(LongRouteScenario.HOME_ID));
				if (failure == null) {
					failure = scans.failure(new StationId(LongRouteScenario.MINE_ID));
				}
				failures.add("scan produced no graph"
						+ (failure == null ? "" : ": " + FixtureReport.failureText(failure)));
				finish(server);
				return;
			}
			destinationLoadedAfterScan = level.isLoaded(RailWorld.pos(layout.destinationDock()));
			destinationRailScanned = graph.rail(layout.destinationDock()) != null;
			failures.addAll(LongRouteScenario.verify(railway, level, false));
			LongRouteScenario.verifyGraph(graph, failures);
			LongRouteScenario.RouteResult route = LongRouteScenario.verifyRoute(railway, level, graph);
			component = route.component();
			forwardLength = route.forwardLength();
			reverseLength = route.reverseLength();
			failures.addAll(route.failures());
			phase = Phase.WAIT_RECOVERY;
			phaseTick = tick;
		}

		private void tickWaitRecovery(MinecraftServer server, long tick) {
			DiagnosticsReport snapshot = railway.diagnostics().snapshot();
			boolean clean = snapshot.global().checkpoints() == 0
					&& snapshot.trips().isEmpty()
					&& snapshot.quarantine().isEmpty()
					&& snapshot.global().recovery().pending() == 0
					&& snapshot.global().recovery().blocking() == 0
					&& managedCarts(level) == 0;
			if (!clean) {
				if (elapsed(tick) > RECOVERY_WAIT_TICKS) {
					failures.add("recovery did not settle within " + RECOVERY_WAIT_TICKS + " ticks");
					finish(server);
				}
				return;
			}
			finish(server);
		}

		private long elapsed(long tick) {
			return tick - phaseTick;
		}

		private void finish(MinecraftServer server) {
			if (done) {
				return;
			}
			done = true;
			FixtureReport report = report(server);
			try {
				Path path = writeReport(server, report);
				LOGGER.info("Fixture verify {}: {} failure(s), report {}", LongRouteScenario.SCENARIO,
						report.failures().size(), path);
				server.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						"Railway fixture verify " + (report.ok() ? "PASS" : "FAIL")
								+ " -> " + path));
			} catch (FixtureException e) {
				LOGGER.error("Cannot write fixture report: {}", e.getMessage());
				server.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						"Railway fixture verify report failed: " + e.getMessage()));
			}
		}

		private FixtureReport report(MinecraftServer server) {
			FixtureReport.Actual actual = new FixtureReport.Actual(
					scanStatus != null && scanStatus.graph() != null ? scanStatus.graph().rails().size() : -1,
					2, 2, component, forwardLength, reverseLength,
					destinationLoadedBeforeScan, destinationLoadedAfterScan, destinationRailScanned);
			FixtureReport.Scan scan = scanStatus == null
					? FixtureReport.scan(jobId, LongRouteScenario.HOME_ID, false, false, 0, -1, 0,
							false, null, null, List.of())
					: FixtureReport.scan(scanStatus.jobId(), scanStatus.station().value(),
							scanStatus.running(), scanStatus.waiting(), scanStatus.pendingChunks(),
							scanStatus.rails(), scanStatus.switches(), scanStatus.complete(),
							scanStatus.failure(),
							scanStatus.coveredBy() == null ? null : scanStatus.coveredBy().value(),
							scanStatus.covered().stream().map(StationId::value).toList());
			if (scanStatus == null) {
				failures.add("scan status is missing after verification");
			}
			return new FixtureReport(
					LongRouteScenario.SCENARIO,
					"verify",
					FixtureMetadata.read(server),
					RailWorld.dimension(level),
					level.getSeed(),
					layout.origin(),
					expected(),
					actual,
					scan,
					recovery(railway, level),
					failures.isEmpty(),
					List.copyOf(failures));
		}
	}
}
