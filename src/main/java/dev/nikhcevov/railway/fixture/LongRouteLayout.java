package dev.nikhcevov.railway.fixture;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry of the {@code long-route-unloaded} acceptance fixture.
 *
 * <p>The fixture owns a fixed absolute area in the overworld. Keeping the
 * calculation here, independent of Minecraft classes, makes the coordinates
 * unit-testable and prevents the build and verify paths from drifting apart.
 *
 * <p>The rail line runs east along {@link #railZ()} at {@link #railY()}. A
 * seven-block-wide stone platform is laid at {@link #baseY()}; everything above
 * it is cleared so terrain and structures cannot interfere with the rail graph.
 * Each station has the same physical shape: rail on the platform, a wall sign
 * and stone button on the north face of a stone pillar, and a safe exit pad one
 * block behind the pillar.
 */
public record LongRouteLayout(int originX, int baseY, int railZ, int routeLength) {
	/** Fixed origin used by the fixture report and documentation. */
	public static final LongRouteLayout DEFAULT = new LongRouteLayout(10_000, 64, 0, 512);
	/** The current scenario requires more than 256 blocks between docks. */
	public static final int MIN_ROUTE_LENGTH = 257;

	public LongRouteLayout {
		if (routeLength < MIN_ROUTE_LENGTH) {
			throw new IllegalArgumentException("routeLength must exceed 256: " + routeLength);
		}
	}

	public int railY() {
		return baseY + 1;
	}

	public int railCount() {
		return routeLength + 1;
	}

	public int homeDockX() {
		return originX;
	}

	public int destinationDockX() {
		return originX + routeLength;
	}

	public int homeChunkX() {
		return homeDockX() >> 4;
	}

	public int destinationChunkX() {
		return destinationDockX() >> 4;
	}

	public int homeChunkZ() {
		return railZ >> 4;
	}

	public int destinationChunkZ() {
		return railZ >> 4;
	}

	/** Chunk columns covered by the rail line, home first. */
	public int chunkCount() {
		return destinationChunkX() - homeChunkX() + 1;
	}

	public int corridorMinZ() {
		return railZ - 1;
	}

	public int corridorMaxZ() {
		return railZ + 4;
	}

	public int signZ() {
		return railZ + 1;
	}

	public int supportZ() {
		return railZ + 2;
	}

	public int exitZ() {
		return railZ + 3;
	}

	public int signY() {
		return railY() + 2;
	}

	public int buttonY() {
		return railY() + 1;
	}

	public BlockLocation origin() {
		return new BlockLocation(originX, baseY, railZ);
	}

	public BlockLocation rail(int x) {
		return new BlockLocation(x, railY(), railZ);
	}

	public BlockLocation homeDock() {
		return rail(homeDockX());
	}

	public BlockLocation destinationDock() {
		return rail(destinationDockX());
	}

	public BlockLocation homeSign() {
		return new BlockLocation(homeDockX(), signY(), signZ());
	}

	public BlockLocation destinationSign() {
		return new BlockLocation(destinationDockX(), signY(), signZ());
	}

	public BlockLocation homeButton() {
		return new BlockLocation(homeDockX(), buttonY(), signZ());
	}

	public BlockLocation destinationButton() {
		return new BlockLocation(destinationDockX(), buttonY(), signZ());
	}

	public BlockLocation homeSupport() {
		return new BlockLocation(homeDockX(), signY(), supportZ());
	}

	public BlockLocation destinationSupport() {
		return new BlockLocation(destinationDockX(), signY(), supportZ());
	}

	public BlockLocation homeButtonSupport() {
		return new BlockLocation(homeDockX(), buttonY(), supportZ());
	}

	public BlockLocation destinationButtonSupport() {
		return new BlockLocation(destinationDockX(), buttonY(), supportZ());
	}

	public BlockLocation homeExit() {
		return new BlockLocation(homeDockX(), railY(), exitZ());
	}

	public BlockLocation destinationExit() {
		return new BlockLocation(destinationDockX(), railY(), exitZ());
	}

	/** Every rail x coordinate, home dock first. */
	public List<BlockLocation> rails() {
		List<BlockLocation> positions = new ArrayList<>(railCount());
		for (int x = homeDockX(); x <= destinationDockX(); x++) {
			positions.add(rail(x));
		}
		return List.copyOf(positions);
	}
}
