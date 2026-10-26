package dev.nikhcevov.railway.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationManagerTest {
	private static final UUID ALPHA = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID BETA = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID GAMMA = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@Test
	void acquiresAllRequestedResources() {
		ReservationManager manager = new ReservationManager();

		assertTrue(manager.tryAcquire(List.of("line:a", "line:b"), ALPHA));

		assertFalse(manager.isFree("line:a"));
		assertFalse(manager.isFree("line:b"));
		assertTrue(manager.isFree("line:c"));
		assertEquals(2, manager.ownedCount(ALPHA));
		assertEquals(0, manager.ownedCount(BETA));
		assertEquals(ALPHA, manager.snapshot().get("line:a"));
		assertEquals(ALPHA, manager.snapshot().get("line:b"));
	}

	@Test
	void conflictingAcquisitionIsRejectedAtomically() {
		ReservationManager manager = new ReservationManager();
		assertTrue(manager.tryAcquire(List.of("line:a"), ALPHA));

		assertFalse(manager.tryAcquire(List.of("line:a", "line:c"), BETA));

		assertTrue(manager.isFree("line:c"), "the free resource must not be reserved by a failed acquisition");
		assertEquals(0, manager.ownedCount(BETA));
		assertEquals(1, manager.ownedCount(ALPHA));
		assertEquals(ALPHA, manager.snapshot().get("line:a"));
		assertEquals(1, manager.snapshot().size());
	}

	@Test
	void conflictIsDetectedRegardlessOfRequestOrder() {
		ReservationManager manager = new ReservationManager();
		assertTrue(manager.tryAcquire(List.of("line:b"), ALPHA));

		assertFalse(manager.tryAcquire(List.of("line:a", "line:b"), BETA));

		assertTrue(manager.isFree("line:a"));
		assertEquals(ALPHA, manager.snapshot().get("line:b"));
	}

	@Test
	void reacquireBySameOwnerIsIdempotent() {
		ReservationManager manager = new ReservationManager();
		assertTrue(manager.tryAcquire(List.of("line:a", "line:b"), ALPHA));

		assertTrue(manager.tryAcquire(List.of("line:a", "line:b"), ALPHA));
		assertEquals(2, manager.ownedCount(ALPHA));

		assertTrue(manager.tryAcquire(List.of("line:b"), ALPHA));
		assertEquals(2, manager.ownedCount(ALPHA));
		assertEquals(ALPHA, manager.snapshot().get("line:b"));
	}

	@Test
	void releaseOnlyAffectsTheOwningTrip() {
		ReservationManager manager = new ReservationManager();
		assertTrue(manager.tryAcquire(List.of("line:a"), ALPHA));

		manager.release("line:a", BETA);
		assertFalse(manager.isFree("line:a"), "a non-owner must not be able to release the resource");
		assertEquals(1, manager.ownedCount(ALPHA));

		manager.release("line:a", ALPHA);
		assertTrue(manager.isFree("line:a"));
		assertEquals(0, manager.ownedCount(ALPHA));
	}

	@Test
	void releaseAllFreesEveryResourceOfOneTripOnly() {
		ReservationManager manager = new ReservationManager();
		assertTrue(manager.tryAcquire(List.of("line:a", "line:b", "line:c"), ALPHA));
		assertTrue(manager.tryAcquire(List.of("line:d"), BETA));
		assertTrue(manager.tryAcquire(List.of("line:e"), GAMMA));

		manager.releaseAll(ALPHA);

		assertTrue(manager.isFree("line:a"));
		assertTrue(manager.isFree("line:b"));
		assertTrue(manager.isFree("line:c"));
		assertEquals(0, manager.ownedCount(ALPHA));
		assertFalse(manager.isFree("line:d"));
		assertEquals(1, manager.ownedCount(BETA));
		assertFalse(manager.isFree("line:e"));
		assertEquals(1, manager.ownedCount(GAMMA));
	}

	@Test
	void quarantinedResourcesBlockAcquisitionUntilReconciled() {
		ReservationManager manager = new ReservationManager();
		UUID cart = UUID.randomUUID();
		assertTrue(manager.tryAcquire(List.of("plat:a", "sec:a-b", "swz:x"), ALPHA));

		assertEquals(3, manager.quarantineAll(ALPHA, cart, "cart removal unverified"));

		assertFalse(manager.isFree("plat:a"));
		assertFalse(manager.isFree("sec:a-b"));
		assertTrue(manager.isQuarantined("swz:x"));
		assertEquals(0, manager.ownedCount(ALPHA));
		assertFalse(manager.tryAcquire(List.of("plat:a"), BETA));

		manager.releaseAll(ALPHA);
		assertTrue(manager.isQuarantined("plat:a"), "releaseAll must not clear quarantine");

		assertEquals(3, manager.reconcile(cart));
		assertTrue(manager.isFree("plat:a"));
		assertTrue(manager.tryAcquire(List.of("plat:a"), BETA));
	}

	@Test
	void quarantineOnlyMovesResourcesOwnedByTheFailingTrip() {
		ReservationManager manager = new ReservationManager();
		UUID firstCart = UUID.randomUUID();
		assertTrue(manager.tryAcquire(List.of("plat:a", "sec:1"), ALPHA));
		assertTrue(manager.tryAcquire(List.of("plat:b"), BETA));

		assertEquals(2, manager.quarantineAll(ALPHA, firstCart, "test"));
		assertFalse(manager.isFree("plat:b"), "another trip still owns its platform");
		assertFalse(manager.isQuarantined("plat:b"));
		assertEquals(1, manager.ownedCount(BETA));
		assertFalse(manager.isFree("plat:a"));
	}

	@Test
	void failingAcquisitionLeavesNoPartialOwnership() {
		ReservationManager manager = new ReservationManager();
		assertTrue(manager.tryAcquire(List.of("line:a", "line:b"), ALPHA));

		assertFalse(manager.tryAcquire(List.of("free:1", "line:b", "free:2"), BETA));

		assertTrue(manager.isFree("free:1"));
		assertTrue(manager.isFree("free:2"));
		assertEquals(0, manager.ownedCount(BETA));
		assertEquals(2, manager.ownedCount(ALPHA));
	}
}
