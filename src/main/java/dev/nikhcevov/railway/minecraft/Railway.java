package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.persistence.Config;
import dev.nikhcevov.railway.persistence.JsonStateStore;
import dev.nikhcevov.railway.persistence.StateStore;
import dev.nikhcevov.railway.persistence.WorldState;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
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
	private TripService trips;
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

		scans = new ScanService(LOGGER);
		trips = new TripService(this, LOGGER);
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

		if (stateLoaded && !state.trips().isEmpty()) {
			LOGGER.warn("Recovering {} interrupted trip checkpoint(s); they will not continue", state.trips().size());
			state.setTrips(List.of());
			if (!disabled) {
				saveState();
			}
		}
		if (disabled) {
			LOGGER.error("{}", Messages.format(Messages.Lang.EN, "system.disabled", disabledReason));
		}
	}

	public void shutdown() {
		if (trips != null) {
			trips.shutdown();
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

	public boolean disabled() {
		return disabled;
	}

	public String disabledReason() {
		return disabledReason;
	}

	public boolean stateLoaded() {
		return stateLoaded;
	}

	public void saveState() {
		if (store == null || !stateLoaded || disabled) {
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
			if (stateLoaded && !stateFailed && !state.trips().isEmpty()) {
				state.setTrips(List.of());
				saveState();
			}
			return null;
		}
		Config.LoadResult.Invalid invalid = (Config.LoadResult.Invalid) result;
		return invalid.errors();
	}
}
