package dev.nikhcevov.railway.fixture;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Version metadata embedded in every fixture report. */
public final class FixtureMetadata {
	private static final String BUILD_INFO = "/railway-build.properties";

	private FixtureMetadata() {
	}

	public static FixtureReport.Metadata read(MinecraftServer server) {
		return new FixtureReport.Metadata(
				modVersion("railway"),
				commit(),
				server.getServerVersion(),
				modVersion("fabricloader"),
				modVersion("fabric-api"));
	}

	private static String modVersion(String id) {
		return FabricLoader.getInstance().getModContainer(id)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	private static String commit() {
		String override = System.getProperty("railway.commit");
		if (override != null && !override.isBlank()) {
			return override.trim();
		}
		Properties properties = new Properties();
		try (InputStream stream = FixtureMetadata.class.getResourceAsStream(BUILD_INFO)) {
			if (stream != null) {
				properties.load(stream);
			}
		} catch (IOException ignored) {
			// A report with "unknown" is still useful; never fail verification for metadata.
		}
		return properties.getProperty("commit", "unknown");
	}
}
