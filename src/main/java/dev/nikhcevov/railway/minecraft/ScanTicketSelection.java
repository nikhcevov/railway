package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure, deterministic selection of the frontier chunks one scan job tickets in a
 * single tick.
 *
 * <p>A scan never needs the whole unloaded frontier at once: it reads the loaded
 * chunks, releases them once the scanner has re-probed them, and moves on to the
 * next subset. This helper bounds that subset by the per-job ticket cap while
 * preferring chunks that are already ticketed (a release before the scanner
 * re-probes them would unload and reload the same chunk) and chunks that are
 * already loaded (same churn, without any loading benefit).
 *
 * <p>Selection is a pure function of its inputs, ordered by chunk coordinate, so
 * a frontier larger than the cap cannot make a scan fail: consecutive ticks walk
 * the frontier in a stable order and every pending chunk is eventually selected.
 */
public final class ScanTicketSelection {
	/** Coordinate order: independent of hash iteration and of discovery order. */
	private static final Comparator<ChunkWindow.Key> ORDER =
			Comparator.comparingInt(ChunkWindow.Key::x).thenComparingInt(ChunkWindow.Key::z);

	/**
	 * Frontier of one scan, split by what a ticket would achieve.
	 *
	 * @param pending chunks the job may ticket: loaded chunks and chunks proven to
	 *                exist in level storage; missing chunks are excluded because
	 *                {@link ScanView} reports them as empty instead
	 * @param loaded  subset of {@code pending} whose chunk is already loaded
	 * @param toProbe chunks of unknown storage state; the caller must run a
	 *                read-only storage probe and decide on a later tick
	 */
	public record Frontier(Set<ChunkWindow.Key> pending, Set<ChunkWindow.Key> loaded,
			Set<ChunkWindow.Key> toProbe) {
		public Frontier {
			pending = new LinkedHashSet<>(pending);
			loaded = new LinkedHashSet<>(loaded);
			toProbe = new LinkedHashSet<>(toProbe);
		}
	}

	/**
	 * @param keys    chunks to ticket, at most {@code budget}, in priority order
	 * @param overflow the job already holds more pending chunks than its budget;
	 *                {@code keys} is then empty and the caller must fail safely
	 *                instead of acquiring a partial or oversized subset
	 */
	public record Selection(List<ChunkWindow.Key> keys, boolean overflow) {
		public Selection {
			keys = List.copyOf(keys);
		}
	}

	private ScanTicketSelection() {
	}

	/**
	 * Splits the scanner's pending block positions into ticketable frontier chunks.
	 *
	 * @param held          chunks the job currently holds tickets for; they stay
	 *                      pending without another probe because a ticket already
	 *                      proved the chunk exists
	 * @param isLoaded      whether the chunk is currently loaded
	 * @param storageState  {@code TRUE}/{@code FALSE} for a completed storage probe,
	 *                      {@code null} while unknown
	 */
	public static Frontier classify(Collection<BlockLocation> positions, Set<ChunkWindow.Key> held,
			Predicate<ChunkWindow.Key> isLoaded, Function<ChunkWindow.Key, Boolean> storageState) {
		Set<ChunkWindow.Key> pending = new LinkedHashSet<>();
		Set<ChunkWindow.Key> loaded = new LinkedHashSet<>();
		Set<ChunkWindow.Key> toProbe = new LinkedHashSet<>();
		for (BlockLocation pos : positions) {
			ChunkWindow.Key key = ChunkWindow.Key.of(pos);
			if (isLoaded.test(key)) {
				pending.add(key);
				loaded.add(key);
				continue;
			}
			if (held.contains(key)) {
				// Already ticketed last tick: keep it instead of releasing a chunk
				// that is still loading, and save the redundant probe.
				pending.add(key);
				continue;
			}
			Boolean onDisk = storageState.apply(key);
			if (Boolean.FALSE.equals(onDisk)) {
				// Never saved: cannot contain player-built rail, so it counts as
				// empty and must not be ticketed (that would generate terrain).
				continue;
			}
			if (onDisk == null) {
				toProbe.add(key);
				continue;
			}
			pending.add(key);
		}
		return new Frontier(pending, loaded, toProbe);
	}

	/**
	 * Chooses at most {@code budget} chunks to ticket.
	 *
	 * <p>Priority: chunks already held and still pending (release would churn),
	 * then loaded chunks, then stored unloaded chunks. Each group is ordered by
	 * chunk coordinate, so a stuck subset is never reselected at random and the
	 * remaining frontier advances deterministically.
	 *
	 * @param budget per-job ticket cap, {@code <= 0} disables tickets
	 */
	public static Selection select(Set<ChunkWindow.Key> held, Set<ChunkWindow.Key> pending,
			Set<ChunkWindow.Key> loaded, int budget) {
		if (budget <= 0 || pending.isEmpty()) {
			return new Selection(List.of(), false);
		}
		List<ChunkWindow.Key> retained = sortedMatching(pending, held::contains);
		if (retained.size() > budget) {
			// Cannot happen while the job is the only writer of its own tickets; a
			// smaller subset would silently drop a chunk the scanner has not
			// re-probed yet, so report the invariant violation instead.
			return new Selection(List.of(), true);
		}
		List<ChunkWindow.Key> keys = new ArrayList<>(Math.min(budget, pending.size()));
		keys.addAll(retained);
		fill(keys, pending, key -> loaded.contains(key) && !held.contains(key), budget);
		fill(keys, pending, key -> !loaded.contains(key) && !held.contains(key), budget);
		return new Selection(keys, false);
	}

	private static void fill(List<ChunkWindow.Key> keys, Set<ChunkWindow.Key> pending,
			Predicate<ChunkWindow.Key> filter, int budget) {
		for (ChunkWindow.Key key : sortedMatching(pending, filter)) {
			if (keys.size() >= budget) {
				return;
			}
			keys.add(key);
		}
	}

	private static List<ChunkWindow.Key> sortedMatching(Set<ChunkWindow.Key> pending,
			Predicate<ChunkWindow.Key> filter) {
		return pending.stream().filter(filter).sorted(ORDER).toList();
	}
}
