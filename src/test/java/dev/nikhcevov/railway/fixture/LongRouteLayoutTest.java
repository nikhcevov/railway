package dev.nikhcevov.railway.fixture;

import dev.nikhcevov.railway.domain.BlockLocation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRouteLayoutTest {
	@Test
	void defaultLayoutExceedsTheUnloadedRouteTarget() {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;

		assertEquals(10_000, layout.homeDockX());
		assertEquals(10_512, layout.destinationDockX());
		assertEquals(512, layout.routeLength());
		assertEquals(513, layout.railCount());
		assertEquals(32, layout.destinationChunkX() - layout.homeChunkX());
		assertEquals(33, layout.chunkCount());
		assertEquals(new BlockLocation(10_000, 64, 0), layout.origin());
		assertEquals(new BlockLocation(10_000, 65, 0), layout.homeDock());
		assertEquals(new BlockLocation(10_512, 65, 0), layout.destinationDock());
	}

	@Test
	void stationGeometryMatchesTheDocumentedPillarAndExit() {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;

		assertEquals(new BlockLocation(10_000, 67, 1), layout.homeSign());
		assertEquals(new BlockLocation(10_000, 66, 1), layout.homeButton());
		assertEquals(new BlockLocation(10_000, 67, 2), layout.homeSupport());
		assertEquals(new BlockLocation(10_000, 66, 2), layout.homeButtonSupport());
		assertEquals(new BlockLocation(10_000, 65, 3), layout.homeExit());
		assertEquals(new BlockLocation(10_512, 65, 3), layout.destinationExit());
	}

	@Test
	void railListCoversBothDocksWithoutCurves() {
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		List<BlockLocation> rails = layout.rails();

		assertEquals(513, rails.size());
		assertEquals(layout.homeDock(), rails.getFirst());
		assertEquals(layout.destinationDock(), rails.getLast());
		assertTrue(rails.stream().allMatch(rail -> rail.y() == 65 && rail.z() == 0));
	}

	@Test
	void rejectsARouteThatDoesNotExceed256Blocks() {
		assertThrows(IllegalArgumentException.class, () -> new LongRouteLayout(0, 64, 0, 256));
	}
}
