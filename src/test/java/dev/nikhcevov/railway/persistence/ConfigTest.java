package dev.nikhcevov.railway.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
	@TempDir
	Path tempDir;

	private Path configFile() {
		return tempDir.resolve("config").resolve("railway.json");
	}

	private Path writeConfig(String json) throws IOException {
		Path file = configFile();
		Files.createDirectories(file.getParent());
		Files.writeString(file, json, StandardCharsets.UTF_8);
		return file;
	}

	private static Config.LoadResult.Loaded loaded(Config.LoadResult result) {
		return assertInstanceOf(Config.LoadResult.Loaded.class, result,
				() -> "expected Loaded, got " + describe(result));
	}

	private static Config.LoadResult.Invalid invalid(Config.LoadResult result) {
		return assertInstanceOf(Config.LoadResult.Invalid.class, result,
				() -> "expected Invalid, got " + describe(result));
	}

	private static String describe(Config.LoadResult result) {
		if (result instanceof Config.LoadResult.Loaded l) {
			return "Loaded" + l.warnings();
		}
		if (result instanceof Config.LoadResult.Invalid i) {
			return "Invalid" + i.errors() + i.warnings();
		}
		return String.valueOf(result);
	}

	private static Config withMaxSpeed(double maxSpeed) {
		Config d = Config.defaults();
		return new Config(
				d.configVersion(), d.boardingRadius(), d.boardingDelayTicks(), d.playerCooldownTicks(),
				d.maxActiveTrips(), maxSpeed, d.acceleration(), d.braking(), d.safetyMargin(),
				d.maxRailsPerScan(), d.scanRailsPerTick(), d.scanTimeBudgetMs(), d.maxRouteLength(),
				d.maxManagedChunkTickets(), d.maxTripTicks()
		);
	}

	@Test
	void createsDefaultsWhenFileIsAbsent() throws IOException {
		Path file = configFile();
		assertFalse(Files.exists(file));

		Config.LoadResult.Loaded result = loaded(Config.load(file));

		assertEquals(Config.defaults(), result.config());
		assertTrue(Files.exists(file), "load must create the config file");
		assertFalse(result.warnings().isEmpty(), "creating defaults is reported as a warning");
		assertTrue(result.warnings().getFirst().startsWith("created default config"),
				() -> "unexpected warnings: " + result.warnings());

		// Reloading proves the created file is valid JSON and roundtrips to the defaults.
		Config.LoadResult.Loaded reloaded = loaded(Config.load(file));
		assertEquals(Config.defaults(), reloaded.config());
	}

	@Test
	void defaultsMatchSpecSection13() {
		Config config = Config.defaults();

		assertEquals(1, config.configVersion());
		assertEquals(Config.CURRENT_VERSION, config.configVersion());
		assertEquals(4, config.boardingRadius());
		assertEquals(10, config.boardingDelayTicks());
		assertEquals(20, config.playerCooldownTicks());
		assertEquals(8, config.maxActiveTrips());
		assertEquals(12.0, config.maxSpeed());
		assertEquals(4.0, config.acceleration());
		assertEquals(6.0, config.braking());
		assertEquals(2.0, config.safetyMargin());
		assertEquals(50_000, config.maxRailsPerScan());
		assertEquals(250, config.scanRailsPerTick());
		assertEquals(2, config.scanTimeBudgetMs());
		assertEquals(10_000, config.maxRouteLength());
		assertEquals(128, config.maxManagedChunkTickets());
		assertEquals(36_000, config.maxTripTicks());

		assertEquals(List.of(), config.validate(), "defaults must validate cleanly");
	}

	@Test
	void effectiveSpeedClampsToStage3Target() {
		assertEquals(12.0, Config.defaults().effectiveSpeed(), "target speed is 12 b/s");
		assertEquals(6.5, withMaxSpeed(6.5).effectiveSpeed(), "slower configs are not raised to the cap");
	}

	@Test
	void partialFileFillsRemainingValuesFromDefaults() throws IOException {
		writeConfig("""
				{
				  "boardingRadius": 7,
				  "maxSpeed": 9.5
				}
				""");

		Config.LoadResult.Loaded result = loaded(Config.load(configFile()));
		Config config = result.config();

		assertEquals(7, config.boardingRadius());
		assertEquals(9.5, config.maxSpeed());
		Config defaults = Config.defaults();
		assertEquals(defaults.configVersion(), config.configVersion());
		assertEquals(defaults.boardingDelayTicks(), config.boardingDelayTicks());
		assertEquals(defaults.maxActiveTrips(), config.maxActiveTrips());
		assertEquals(defaults.scanRailsPerTick(), config.scanRailsPerTick());
		assertEquals(defaults.maxTripTicks(), config.maxTripTicks());
		assertEquals(List.of(), result.warnings());
	}

	@Test
	void outOfRangeValueIsInvalidAndFileIsNotRewritten() throws IOException {
		Path file = writeConfig("""
				{
				  "configVersion": 1,
				  "boardingRadius": 0
				}
				""");
		byte[] original = Files.readAllBytes(file);

		Config.LoadResult.Invalid result = invalid(Config.load(file));

		assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("boardingRadius:")),
				() -> "expected a boardingRadius error, got " + result.errors());
		assertArrayEquals(original, Files.readAllBytes(file), "invalid config must never be rewritten");
	}

	@Test
	void wrongConfigVersionIsInvalid() throws IOException {
		Path file = writeConfig("""
				{
				  "configVersion": 2
				}
				""");
		byte[] original = Files.readAllBytes(file);

		Config.LoadResult.Invalid result = invalid(Config.load(file));

		assertTrue(result.errors().stream().anyMatch(error -> error.startsWith("configVersion:")),
				() -> "expected a configVersion error, got " + result.errors());
		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	void unknownKeysWarnAndAreIgnored() throws IOException {
		writeConfig("""
				{
				  "configVersion": 1,
				  "unknownThing": 5,
				  "secondUnknown": true
				}
				""");

		Config.LoadResult.Loaded result = loaded(Config.load(configFile()));

		assertEquals(Config.defaults(), result.config(), "unknown keys must not influence known settings");
		assertTrue(result.warnings().contains("unknown config key ignored: unknownThing"), result.warnings()::toString);
		assertTrue(result.warnings().contains("unknown config key ignored: secondUnknown"), result.warnings()::toString);
	}

	@Test
	void nonJsonContentIsInvalid() throws IOException {
		Path file = writeConfig("this is definitely not json");
		byte[] original = Files.readAllBytes(file);

		Config.LoadResult.Invalid result = invalid(Config.load(file));

		assertFalse(result.errors().isEmpty());
		assertArrayEquals(original, Files.readAllBytes(file));
	}

	@Test
	void nonObjectJsonRootIsInvalid() throws IOException {
		writeConfig("[1, 2, 3]");

		Config.LoadResult.Invalid result = invalid(Config.load(configFile()));

		assertTrue(result.errors().stream().anyMatch(error -> error.contains("JSON object")),
				() -> "expected an object-root error, got " + result.errors());
	}
}
