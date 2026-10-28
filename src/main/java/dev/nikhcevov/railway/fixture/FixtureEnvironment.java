package dev.nikhcevov.railway.fixture;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Dev-only gate for the acceptance fixture commands.
 *
 * <p>The surface is registered only in a Fabric development environment or
 * when {@code -Drailway.fixtures=true} is passed explicitly. A production jar
 * therefore carries the classes but never registers {@code /railfixture}.
 * The whole {@code dev.nikhcevov.railway.fixture} package and its single
 * registration block in {@code RailwayMod} are meant to be deleted before a
 * release; see {@code docs/fixtures.md}.
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
