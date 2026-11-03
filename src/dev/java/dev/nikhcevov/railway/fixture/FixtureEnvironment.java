package dev.nikhcevov.railway.fixture;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Dev-only gate for the acceptance fixture commands.
 *
 * <p>The surface is registered only in a Fabric development environment or
 * when {@code -Drailway.fixtures=true} is passed explicitly. This package lives
 * in the {@code src/dev} source set, which is added only to the dev server runs;
 * production jars and sources jars exclude it. See {@code docs/fixtures.md}.
 */
public final class FixtureEnvironment {
	public static final String ENABLE_PROPERTY = "railway.fixtures";

	private FixtureEnvironment() {
	}

	public static boolean enabled() {
		return FabricLoader.getInstance().isDevelopmentEnvironment()
				|| Boolean.getBoolean(ENABLE_PROPERTY);
	}
}
