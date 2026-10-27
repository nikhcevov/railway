package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.dispatch.ChunkWindow;
import dev.nikhcevov.railway.domain.BlockLocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanTicketSelectionTest {
	private static final int CAP = ScanService.MAX_SCAN_TICKETS;

	@Test
	void frontierWiderThanTheCapSelectsExactlyTheCap() {
		Set<ChunkWindow.Key> pending = frontier(40);

		ScanTicketSelection.Selection selection =
				ScanTicketSelection.select(Set.of(), pending, Set.of(), CAP);

		assertFalse(selection.overflow());
		assertEquals(CAP, selection.keys().size());
		assertTrue(pending.containsAll(selection.keys()));
	}

	@Test
	void selectionIsDeterministicRegardlessOfSetIterationOrder() {
		Set<ChunkWindow.Key> ordered = frontier(40);
		Set<ChunkWindow.Key> reversed = new LinkedHashSet<>();
		List<ChunkWindow.Key> keys = new ArrayList<>(ordered);
		for (int i = keys.size() - 1; i >= 0; i--) {
			reversed.add(keys.get(i));
		}

		List<ChunkWindow.Key> first = ScanTicketSelection.select(Set.of(), ordered, Set.of(), CAP)
				.keys();
		List<ChunkWindow.Key> second = ScanTicketSelection.select(Set.of(), reversed, Set.of(), CAP)
				.keys();

		assertEquals(first, second);
		assertEquals(first, ScanTicketSelection.select(Set.of(), ordered, Set.of(), CAP).keys());
		// Lowest coordinates first, so a later subset continues where this one ended.
		assertEquals(new ChunkWindow.Key(1_000, 0), first.getFirst());
	}

	@Test
	void heldChunksAreKeptEvenWhenTheySortAfterNewChunks() {
		Set<ChunkWindow.Key> pending = frontier(40);
		Set<ChunkWindow.Key> held = new LinkedHashSet<>();
		held.add(new ChunkWindow.Key(2_000, 7));
		held.add(new ChunkWindow.Key(1_500, 3));
		pending.addAll(held);

		ScanTicketSelection.Selection selection =
				ScanTicketSelection.select(held, pending, Set.of(), CAP);

		assertFalse(selection.overflow());
		assertEquals(CAP, selection.keys().size());
		assertEquals(List.of(new ChunkWindow.Key(1_500, 3), new ChunkWindow.Key(2_000, 7)),
				selection.keys().subList(0, 2));
	}

	@Test
	void loadedChunksAreSelectedBeforeStoredUnloadedOnes() {
		Set<ChunkWindow.Key> pending = frontier(40);
		Set<ChunkWindow.Key> loaded = Set.of(new ChunkWindow.Key(9_000, 4),
				new ChunkWindow.Key(9_001, 4));
		pending.addAll(loaded);

		ScanTicketSelection.Selection selection =
				ScanTicketSelection.select(Set.of(), pending, loaded, CAP);

		assertEquals(CAP, selection.keys().size());
		assertTrue(selection.keys().containsAll(loaded));
		assertEquals(new ChunkWindow.Key(9_000, 4), selection.keys().get(0));
		assertEquals(new ChunkWindow.Key(9_001, 4), selection.keys().get(1));
	}

	@Test
	void everyPendingChunkIsSelectedAcrossSuccessiveSubsets() {
		Set<ChunkWindow.Key> pending = frontier(90);
		Set<ChunkWindow.Key> remaining = new LinkedHashSet<>(pending);
		Set<ChunkWindow.Key> selected = new LinkedHashSet<>();
		int rounds = 0;

		while (!remaining.isEmpty()) {
			ScanTicketSelection.Selection selection =
					ScanTicketSelection.select(Set.of(), remaining, Set.of(), CAP);
			assertFalse(selection.overflow());
			assertFalse(selection.keys().isEmpty());
			assertTrue(selection.keys().size() <= CAP);
			// Simulate the scanner re-probing the loaded subset: those chunks leave
			// the pending frontier, which frees the budget for the next subset.
			assertSubset(remaining, selection.keys());
			selected.addAll(selection.keys());
			remaining.removeAll(selection.keys());
			rounds++;
			assertTrue(rounds <= 10, "selection did not make progress");
		}

		assertEquals(pending, selected);
		assertEquals(3, rounds);
	}

	@Test
	void heldChunksAreReselectedUntilTheScannerReprobesThem() {
		Set<ChunkWindow.Key> pending = frontier(40);
		ScanTicketSelection.Selection first =
				ScanTicketSelection.select(Set.of(), pending, Set.of(), CAP);

		// The chunks load but the scanner has not re-probed them yet: they are held
		// and must stay selected instead of being released and reloaded.
		ScanTicketSelection.Selection second =
				ScanTicketSelection.select(Set.copyOf(first.keys()), pending, Set.copyOf(first.keys()), CAP);

		assertEquals(first.keys(), second.keys());
	}

	@Test
	void zeroBudgetSelectsNothing() {
		ScanTicketSelection.Selection selection =
				ScanTicketSelection.select(Set.of(), frontier(4), Set.of(), 0);

		assertTrue(selection.keys().isEmpty());
		assertFalse(selection.overflow());
	}

	@Test
	void heldPendingChunksOverTheBudgetReportOverflowWithoutAPartialSubset() {
		Set<ChunkWindow.Key> pending = frontier(4);

		ScanTicketSelection.Selection selection =
				ScanTicketSelection.select(pending, pending, Set.of(), 2);

		assertTrue(selection.overflow());
		assertTrue(selection.keys().isEmpty());
	}

	@Test
	void keysOutsideThePendingFrontierAreIgnored() {
		Set<ChunkWindow.Key> pending = Set.of(new ChunkWindow.Key(3, 3));
		Set<ChunkWindow.Key> held = Set.of(new ChunkWindow.Key(3, 3), new ChunkWindow.Key(4, 4));
		Set<ChunkWindow.Key> loaded = Set.of(new ChunkWindow.Key(4, 4), new ChunkWindow.Key(5, 5));

		ScanTicketSelection.Selection selection =
				ScanTicketSelection.select(held, pending, loaded, CAP);

		assertEquals(List.of(new ChunkWindow.Key(3, 3)), selection.keys());
	}

	@Test
	void classifySeparatesLoadedStoredMissingAndUnprobedChunks() {
		BlockLocation loaded = block(0, 0);
		BlockLocation stored = block(16, 0);
		BlockLocation missing = block(32, 0);
		BlockLocation unknown = block(48, 0);
		BlockLocation heldUnknown = block(64, 0);
		Set<ChunkWindow.Key> held = Set.of(ChunkWindow.Key.of(heldUnknown));

		ScanTicketSelection.Frontier frontier = ScanTicketSelection.classify(
				List.of(loaded, stored, missing, unknown, heldUnknown), held,
				key -> key.equals(ChunkWindow.Key.of(loaded)),
				key -> key.equals(ChunkWindow.Key.of(stored)) ? Boolean.TRUE
						: key.equals(ChunkWindow.Key.of(missing)) ? Boolean.FALSE : null);

		assertEquals(Set.of(ChunkWindow.Key.of(loaded), ChunkWindow.Key.of(stored),
				ChunkWindow.Key.of(heldUnknown)), frontier.pending());
		assertEquals(Set.of(ChunkWindow.Key.of(loaded)), frontier.loaded());
		assertEquals(Set.of(ChunkWindow.Key.of(unknown)), frontier.toProbe());
	}

	@Test
	void missingChunksNeverReachTheSelection() {
		BlockLocation missing = block(16, 0);
		ScanTicketSelection.Frontier frontier = ScanTicketSelection.classify(
				List.of(missing), Set.of(), key -> false, key -> Boolean.FALSE);

		assertTrue(frontier.pending().isEmpty());
		assertTrue(frontier.toProbe().isEmpty());
		assertTrue(ScanTicketSelection.select(Set.of(), frontier.pending(), frontier.loaded(), CAP)
				.keys().isEmpty());
	}

	private static void assertSubset(Set<ChunkWindow.Key> remaining,
			List<ChunkWindow.Key> keys) {
		assertTrue(remaining.containsAll(keys), "selection returned a chunk outside the frontier");
	}

	private static Set<ChunkWindow.Key> frontier(int size) {
		Set<ChunkWindow.Key> keys = new LinkedHashSet<>();
		for (int i = 0; i < size; i++) {
			// Spread over x and z so the coordinate order is not just insertion order.
			keys.add(new ChunkWindow.Key(1_000 + i % 7, i / 7));
		}
		assertEquals(size, keys.size());
		return keys;
	}

	private static BlockLocation block(int x, int z) {
		return new BlockLocation(x, 64, z);
	}
}
