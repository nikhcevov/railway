package dev.nikhcevov.railway.dispatch;

import dev.nikhcevov.railway.domain.BlockLocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkWindowTest {
	@Test
	void windowStartsAtCurrentChunkAndCoversBrakingDistance() {
		ChunkWindow.Window window = ChunkWindow.compute(line(0, 100), 0, 40.0, 128);

		assertEquals(List.of(key(0), key(1), key(2)), window.chunks());
		assertFalse(window.overBudget());
	}

	@Test
	void windowStartsAtTheCartStepNotAtTheRouteStart() {
		ChunkWindow.Window window = ChunkWindow.compute(line(0, 100), 50, 40.0, 128);

		assertEquals(List.of(key(3), key(4), key(5)), window.chunks());
	}

	@Test
	void windowStopsAtTheRouteEnd() {
		ChunkWindow.Window window = ChunkWindow.compute(line(0, 100), 98, 40.0, 128);

		assertEquals(List.of(key(6)), window.chunks());
		assertFalse(window.overBudget());
	}

	@Test
	void chunksAreDeduplicatedWhenTheRouteStaysInOneChunk() {
		List<BlockLocation> route = new ArrayList<>();
		for (int z = 0; z < 8; z++) {
			route.add(new BlockLocation(3, 64, z));
		}

		ChunkWindow.Window window = ChunkWindow.compute(route, 0, 40.0, 128);

		assertEquals(List.of(key(0)), window.chunks());
	}

	@Test
	void budgetCapsTheWindowAndReportsOverflow() {
		ChunkWindow.Window window = ChunkWindow.compute(line(0, 100), 0, 40.0, 2);

		assertEquals(List.of(key(0), key(1)), window.chunks());
		assertTrue(window.overBudget());
	}

	@Test
	void exactBudgetIsNotAnOverflow() {
		ChunkWindow.Window window = ChunkWindow.compute(line(0, 100), 0, 40.0, 3);

		assertEquals(List.of(key(0), key(1), key(2)), window.chunks());
		assertFalse(window.overBudget());
	}

	@Test
	void zeroBudgetDisablesTicketsInsteadOfFailing() {
		ChunkWindow.Window window = ChunkWindow.compute(line(0, 100), 0, 40.0, 0);

		assertTrue(window.chunks().isEmpty());
		assertFalse(window.overBudget());
	}

	@Test
	void negativeCoordinatesUseFloorDivision() {
		assertEquals(new ChunkWindow.Key(-1, -2), ChunkWindow.Key.of(new BlockLocation(-1, 64, -17)));
		assertEquals(new ChunkWindow.Key(-1, -1), ChunkWindow.Key.of(new BlockLocation(-16, 64, -16)));
	}

	@Test
	void stepIndexIsClampedToTheRoute() {
		assertEquals(List.of(key(0)), ChunkWindow.compute(line(0, 0), 50, 40.0, 128).chunks());
		assertEquals(List.of(key(0), key(1), key(2)),
				ChunkWindow.compute(line(0, 100), -5, 40.0, 128).chunks());
	}

	private static ChunkWindow.Key key(int chunkX) {
		return new ChunkWindow.Key(chunkX, 0);
	}

	private static List<BlockLocation> line(int from, int to) {
		List<BlockLocation> route = new ArrayList<>(to - from + 1);
		for (int x = from; x <= to; x++) {
			route.add(new BlockLocation(x, 64, 0));
		}
		return route;
	}
}
