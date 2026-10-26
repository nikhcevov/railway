package dev.nikhcevov.railway.dispatch;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure calculation of the local chunk window a trip must keep ready: the chunk
 * containing the cart plus every chunk covered by the braking/safety distance
 * ahead. It never covers the whole route and never exceeds the ticket budget.
 */
public final class ChunkWindow {
	public static final int CHUNK_SIZE = 16;

	/** Packed chunk coordinate independent of Minecraft types. */
	public record Key(int x, int z) {
		public static Key of(BlockLocation pos) {
			return new Key(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE));
		}

		public long pack() {
			return ((long) x << 32) | (z & 0xFFFF_FFFFL);
		}
	}

	/**
	 * @param chunks    distinct chunks in route order
	 * @param overBudget true when the route window needs more chunks than the budget;
	 *                   the returned list is then capped at the budget
	 */
	public record Window(List<Key> chunks, boolean overBudget) {
		public Window {
			chunks = List.copyOf(chunks);
		}
	}

	private ChunkWindow() {
	}

	/**
	 * Computes the window starting at {@code stepIndex}. A zero budget means
	 * managed tickets are disabled: the result is empty and not an overflow.
	 */
	public static Window compute(List<BlockLocation> route, int stepIndex, double windowDistance, int budget) {
		if (budget <= 0 || route.isEmpty()) {
			return new Window(List.of(), false);
		}
		int index = Math.max(0, Math.min(stepIndex, route.size() - 1));
		Set<Key> chunks = new LinkedHashSet<>();
		chunks.add(Key.of(route.get(index)));
		double travelled = 0.0;
		for (int i = index + 1; i < route.size() && travelled < windowDistance; i++) {
			BlockLocation previous = route.get(i - 1);
			BlockLocation next = route.get(i);
			travelled += Math.max(1.0, Math.hypot(next.x() - previous.x(), next.z() - previous.z()));
			Key key = Key.of(next);
			if (chunks.contains(key)) {
				continue;
			}
			if (chunks.size() >= budget) {
				return new Window(new ArrayList<>(chunks), true);
			}
			chunks.add(key);
		}
		return new Window(new ArrayList<>(chunks), false);
	}
}
