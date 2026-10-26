package dev.nikhcevov.railway.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server configuration. Units are blocks, blocks/second, blocks/second^2, ticks
 * and milliseconds as documented in the spec (section 13).
 */
public record Config(
		int configVersion,
		int boardingRadius,
		int boardingDelayTicks,
		int playerCooldownTicks,
		int maxActiveTrips,
		double maxSpeed,
		double acceleration,
		double braking,
		double safetyMargin,
		int maxRailsPerScan,
		int scanRailsPerTick,
		int scanTimeBudgetMs,
		int maxRouteLength,
		int maxManagedChunkTickets,
		int maxTripTicks
) {
	public static final int CURRENT_VERSION = 1;
	/** Stage 3 target and the safe ceiling recognized by the managed-cart mixin. */
	public static final double TARGET_SPEED_CAP = 12.0;

	private static final Set<String> KNOWN_KEYS = Set.of(
			"configVersion", "boardingRadius", "boardingDelayTicks", "playerCooldownTicks",
			"maxActiveTrips", "maxSpeed", "acceleration", "braking", "safetyMargin",
			"maxRailsPerScan", "scanRailsPerTick", "scanTimeBudgetMs", "maxRouteLength",
			"maxManagedChunkTickets", "maxTripTicks"
	);

	public static Config defaults() {
		return new Config(CURRENT_VERSION, 4, 10, 20, 8, 12.0, 4.0, 6.0, 2.0,
				50_000, 250, 2, 10_000, 128, 36_000);
	}

	public double effectiveSpeed() {
		return Math.min(maxSpeed, TARGET_SPEED_CAP);
	}

	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (configVersion != CURRENT_VERSION) {
			errors.add("configVersion: expected " + CURRENT_VERSION + ", got " + configVersion);
		}
		check(errors, "boardingRadius", boardingRadius, 1, 16);
		check(errors, "boardingDelayTicks", boardingDelayTicks, 0, 200);
		check(errors, "playerCooldownTicks", playerCooldownTicks, 0, 1_200);
		check(errors, "maxActiveTrips", maxActiveTrips, 1, 64);
		checkDouble(errors, "maxSpeed", maxSpeed, 1.0, 30.0);
		checkDouble(errors, "acceleration", acceleration, 0.1, 20.0);
		checkDouble(errors, "braking", braking, 0.1, 20.0);
		checkDouble(errors, "safetyMargin", safetyMargin, 0.0, 10.0);
		check(errors, "maxRailsPerScan", maxRailsPerScan, 1, 1_000_000);
		check(errors, "scanRailsPerTick", scanRailsPerTick, 1, 10_000);
		check(errors, "scanTimeBudgetMs", scanTimeBudgetMs, 1, 50);
		check(errors, "maxRouteLength", maxRouteLength, 1, 1_000_000);
		check(errors, "maxManagedChunkTickets", maxManagedChunkTickets, 0, 4_096);
		check(errors, "maxTripTicks", maxTripTicks, 20, 1_000_000);
		return errors;
	}

	private static void check(List<String> errors, String key, int value, int min, int max) {
		if (value < min || value > max) {
			errors.add(key + ": expected " + min + ".." + max + ", got " + value);
		}
	}

	private static void checkDouble(List<String> errors, String key, double value, double min, double max) {
		if (!Double.isFinite(value) || value < min || value > max) {
			errors.add(key + ": expected " + min + ".." + max + ", got " + value);
		}
	}

	public sealed interface LoadResult {
		record Loaded(Config config, List<String> warnings) implements LoadResult {
		}

		record Invalid(List<String> errors, List<String> warnings) implements LoadResult {
		}
	}

	/** Loads or creates {@code config/railway.json}. Invalid files are never rewritten. */
	public static LoadResult load(Path file) {
		List<String> warnings = new ArrayList<>();
		if (!Files.exists(file)) {
			Config config = defaults();
			try {
				write(file, config);
				warnings.add("created default config: " + file);
				return new LoadResult.Loaded(config, warnings);
			} catch (IOException e) {
				return new LoadResult.Invalid(List.of("cannot write default config: " + e.getMessage()), warnings);
			}
		}

		JsonObject object;
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(text);
			if (!parsed.isJsonObject()) {
				return new LoadResult.Invalid(List.of("config root must be a JSON object"), warnings);
			}
			object = parsed.getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			return new LoadResult.Invalid(List.of("cannot read config: " + e.getMessage()), warnings);
		}

		Set<String> unknown = new TreeSet<>(object.keySet());
		unknown.removeAll(KNOWN_KEYS);
		for (String key : unknown) {
			warnings.add("unknown config key ignored: " + key);
		}

		Config defaults = defaults();
		try {
			Config config = new Config(
					intValue(object, "configVersion", defaults.configVersion()),
					intValue(object, "boardingRadius", defaults.boardingRadius()),
					intValue(object, "boardingDelayTicks", defaults.boardingDelayTicks()),
					intValue(object, "playerCooldownTicks", defaults.playerCooldownTicks()),
					intValue(object, "maxActiveTrips", defaults.maxActiveTrips()),
					doubleValue(object, "maxSpeed", defaults.maxSpeed()),
					doubleValue(object, "acceleration", defaults.acceleration()),
					doubleValue(object, "braking", defaults.braking()),
					doubleValue(object, "safetyMargin", defaults.safetyMargin()),
					intValue(object, "maxRailsPerScan", defaults.maxRailsPerScan()),
					intValue(object, "scanRailsPerTick", defaults.scanRailsPerTick()),
					intValue(object, "scanTimeBudgetMs", defaults.scanTimeBudgetMs()),
					intValue(object, "maxRouteLength", defaults.maxRouteLength()),
					intValue(object, "maxManagedChunkTickets", defaults.maxManagedChunkTickets()),
					intValue(object, "maxTripTicks", defaults.maxTripTicks())
			);
			List<String> errors = config.validate();
			if (!errors.isEmpty()) {
				return new LoadResult.Invalid(errors, warnings);
			}
			return new LoadResult.Loaded(config, warnings);
		} catch (RuntimeException e) {
			return new LoadResult.Invalid(List.of("invalid config value: " + e.getMessage()), warnings);
		}
	}

	private static int intValue(JsonObject object, String key, int fallback) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
		double raw = primitive.getAsDouble();
		int value = primitive.getAsInt();
		if (raw != value) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
		return value;
	}

	private static double doubleValue(JsonObject object, String key, double fallback) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new IllegalArgumentException(key + " must be a number");
		}
		return primitive.getAsDouble();
	}

	private static void write(Path file, Config config) throws IOException {
		Files.createDirectories(file.getParent());
		JsonObject object = new JsonObject();
		object.addProperty("configVersion", config.configVersion());
		object.addProperty("boardingRadius", config.boardingRadius());
		object.addProperty("boardingDelayTicks", config.boardingDelayTicks());
		object.addProperty("playerCooldownTicks", config.playerCooldownTicks());
		object.addProperty("maxActiveTrips", config.maxActiveTrips());
		object.addProperty("maxSpeed", config.maxSpeed());
		object.addProperty("acceleration", config.acceleration());
		object.addProperty("braking", config.braking());
		object.addProperty("safetyMargin", config.safetyMargin());
		object.addProperty("maxRailsPerScan", config.maxRailsPerScan());
		object.addProperty("scanRailsPerTick", config.scanRailsPerTick());
		object.addProperty("scanTimeBudgetMs", config.scanTimeBudgetMs());
		object.addProperty("maxRouteLength", config.maxRouteLength());
		object.addProperty("maxManagedChunkTickets", config.maxManagedChunkTickets());
		object.addProperty("maxTripTicks", config.maxTripTicks());
		Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, gson.toJson(object), StandardCharsets.UTF_8);
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
