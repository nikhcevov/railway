package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.diagnostics.DiagnosticsService;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.persistence.Config;
import dev.nikhcevov.railway.persistence.JsonStateStore;
import dev.nikhcevov.railway.persistence.StateStore;
import dev.nikhcevov.railway.persistence.WorldState;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Composition root. Holds the authoritative config/state and both runtime services. */
public final class Railway {
	private static final Logger LOGGER = LoggerFactory.getLogger("railway");
	private static Railway instance;

	private final MinecraftServer server;
	private Config config;
	private WorldState state;
	private StateStore store;
	private ScanService scans;
	private ChunkTicketManager tickets;
	private TripService trips;
	private RecoveryService recovery;
	private DiagnosticsService diagnostics;
	private SignRegistrationService signs;
	private boolean disabled;
	private String disabledReason;
	private boolean stateLoaded;
	private boolean configDisabled;
	private boolean stateFailed;
	private String stateFailureReason;

	private Railway(MinecraftServer server) {
		this.server = server;
	}

	public static void start(MinecraftServer server) {
		if (instance != null) {
			instance.shutdown();
		}
		Railway railway = new Railway(server);
		railway.initialize();
		instance = railway;
	}

	public static void stop() {
		if (instance != null) {
			instance.shutdown();
			instance = null;
		}
	}

	public static Railway get() {
		Railway railway = instance;
		if (railway == null) {
			throw new IllegalStateException("Railway is not started");
		}
		return railway;
	}

	public static boolean isStarted() {
		return instance != null;
	}

	private void initialize() {
		Path configFile = FabricLoader.getInstance().getConfigDir().resolve("railway.json");
		Config.LoadResult configResult = Config.load(configFile);
		if (configResult instanceof Config.LoadResult.Loaded loaded) {
			config = loaded.config();
			for (String warning : loaded.warnings()) {
				LOGGER.warn("{}", Messages.format(Messages.Lang.EN, "system.config_warning", warning));
			}
		} else {
			Config.LoadResult.Invalid invalid = (Config.LoadResult.Invalid) configResult;
			configDisabled = true;
			disabled = true;
			disabledReason = String.join("; ", invalid.errors());
			for (String error : invalid.errors()) {
				LOGGER.error("Invalid Railway config: {}", error);
			}
			for (String warning : invalid.warnings()) {
				LOGGER.warn("Railway config warning: {}", warning);
			}
			config = Config.defaults();
		}

		tickets = new ChunkTicketManager(LOGGER);
		scans = new ScanService(LOGGER, tickets);
		trips = new TripService(this, LOGGER, tickets);
		signs = new SignRegistrationService(this, LOGGER);
		store = new JsonStateStore(server.getWorldPath(LevelResource.ROOT));
		try {
			state = store.load();
			stateLoaded = true;
		} catch (StateStore.StateException e) {
			stateFailed = true;
			stateFailureReason = e.getMessage();
			disabled = true;
			disabledReason = e.getMessage();
			LOGGER.error("Railway state disabled: {}", e.getMessage());
			state = new WorldState();
		}

		// Checkpoints are never discarded here: recovery reconciles them against
		// the loaded world and blocks new trips only until it is done.
		recovery = new RecoveryService(this, LOGGER, tickets);
		if (stateLoaded) {
			recovery.begin();
		}
		diagnostics = new DiagnosticsService(this);
		// Graphs are derived caches and are never persisted, so the first tick
		// after a restart must rebuild them without an admin command. Start the
		// jobs after recovery.begin() so ticket accounting sees the checkpoints;
		// duplicate starts are rejected by ScanService.
		startStartupScans();
		if (disabled) {
			LOGGER.error("{}", Messages.format(Messages.Lang.EN, "system.disabled", disabledReason));
		}
	}

	/** One background scan per anchored station, when the server becomes ready. */
	private void startStartupScans() {
		if (disabled || !stateLoaded) {
			return;
		}
		startScansFor(state.stations());
	}

	/**
	 * Starts one background scan per anchored station whose dimension is loaded.
	 * Provisional stations and a disabled Railway are skipped, a missing level or
	 * a rejected start is only logged, and an already running job is never
	 * duplicated. Startup calls this once with every persisted station.
	 */
	public void startScansFor(Collection<Station> stations) {
		if (disabled) {
			return;
		}
		long tick = server.getTickCount();
		long revision = state.revision();
		for (Station station : stations) {
			if (!station.isAnchored()) {
				continue;
			}
			ServerLevel level = RailWorld.level(server, station.dimension());
			if (level == null) {
				LOGGER.warn("Cannot auto-scan station {}: dimension {} is not loaded",
						station.id().value(), station.dimension());
				continue;
			}
			ScanService.StartResult result = scans.autoStart(station, level, config, tick, revision);
			if (result instanceof ScanService.StartResult.Rejected rejected) {
				LOGGER.warn("Automatic scan for station {} did not start: {} {}", station.id().value(),
						rejected.failure().code(), rejected.failure().detail());
			}
		}
	}

	public void shutdown() {
		if (recovery != null) {
			recovery.shutdown();
		}
		if (trips != null) {
			trips.shutdown();
		}
		if (tickets != null) {
			tickets.releaseAll();
		}
		if (scans != null) {
			scans.clear();
		}
		saveState();
	}

	public MinecraftServer server() {
		return server;
	}

	public Config config() {
		return config;
	}

	public WorldState state() {
		return state;
	}

	public ScanService scans() {
		return scans;
	}

	public TripService trips() {
		return trips;
	}

	public RecoveryService recovery() {
		if (recovery == null) {
			throw new IllegalStateException("Railway recovery is not initialized");
		}
		return recovery;
	}

	public ChunkTicketManager tickets() {
		return tickets;
	}

	public DiagnosticsService diagnostics() {
		if (diagnostics == null) {
			throw new IllegalStateException("Railway diagnostics are not initialized");
		}
		return diagnostics;
	}

	public SignRegistrationService signs() {
		return signs;
	}

	public boolean disabled() {
		return disabled;
	}

	/**
	 * OP level 2 (gamemaster) may always register signs. The optional
	 * {@code builders} config list grants the same right to specific player UUIDs.
	 */
	public boolean isBuilder(ServerPlayer player) {
		if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			return true;
		}
		String uuid = player.getUUID().toString();
		for (String builder : config.builders()) {
			if (builder.equalsIgnoreCase(uuid)) {
				return true;
			}
		}
		return false;
	}

	public String disabledReason() {
		return disabledReason;
	}

	public boolean stateLoaded() {
		return stateLoaded;
	}

	public void saveState() {
		// Recovery cleans checkpoints even on a config-disabled server; only a state
		// that failed to load must never be overwritten.
		if (store == null || !stateLoaded) {
			return;
		}
		try {
			store.save(state);
		} catch (IOException e) {
			LOGGER.error("Cannot save Railway state: {}", e.getMessage(), e);
		}
	}

	/** Applies a validated config for new trips. Returns null on success, errors otherwise. */
	public List<String> reloadConfig() {
		Config.LoadResult result = Config.load(FabricLoader.getInstance().getConfigDir().resolve("railway.json"));
		if (result instanceof Config.LoadResult.Loaded loaded) {
			config = loaded.config();
			configDisabled = false;
			disabled = stateFailed;
			disabledReason = stateFailed ? stateFailureReason : null;
			for (String warning : loaded.warnings()) {
				LOGGER.warn("Railway config warning: {}", warning);
			}
			return null;
		}
		Config.LoadResult.Invalid invalid = (Config.LoadResult.Invalid) result;
		return invalid.errors();
	}
}
