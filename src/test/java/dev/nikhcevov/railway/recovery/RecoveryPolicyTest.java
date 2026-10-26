package dev.nikhcevov.railway.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryPolicyTest {
	@Test
	void anEmptyManagedCartIsDiscardedWhateverTheChunkStateIs() {
		for (RecoveryPolicy.Chunk chunk : RecoveryPolicy.Chunk.values()) {
			assertEquals(RecoveryPolicy.Action.DISCARD_CART,
					RecoveryPolicy.decide(RecoveryPolicy.Cart.EMPTY, chunk, false),
					"chunk " + chunk);
			assertEquals(RecoveryPolicy.Action.DISCARD_CART,
					RecoveryPolicy.decide(RecoveryPolicy.Cart.EMPTY, chunk, true),
					"expired chunk " + chunk);
		}
	}

	@Test
	void anOccupiedManagedCartIsOnlyStopped() {
		for (RecoveryPolicy.Chunk chunk : RecoveryPolicy.Chunk.values()) {
			assertEquals(RecoveryPolicy.Action.STOP_CART,
					RecoveryPolicy.decide(RecoveryPolicy.Cart.OCCUPIED, chunk, false));
			assertEquals(RecoveryPolicy.Action.STOP_CART,
					RecoveryPolicy.decide(RecoveryPolicy.Cart.OCCUPIED, chunk, true),
					"a passenger is never force-ejected by the deadline");
		}
	}

	@Test
	void anUnmanagedCartIsNeverTouchedByRecovery() {
		for (RecoveryPolicy.Chunk chunk : RecoveryPolicy.Chunk.values()) {
			assertEquals(RecoveryPolicy.Action.DROP_CHECKPOINT,
					RecoveryPolicy.decide(RecoveryPolicy.Cart.UNMANAGED, chunk, false));
			assertEquals(RecoveryPolicy.Action.DROP_CHECKPOINT,
					RecoveryPolicy.decide(RecoveryPolicy.Cart.UNMANAGED, chunk, true));
		}
	}

	@Test
	void anAbsentCartInAConfirmedChunkDropsTheCheckpoint() {
		assertEquals(RecoveryPolicy.Action.DROP_CHECKPOINT,
				RecoveryPolicy.decide(RecoveryPolicy.Cart.ABSENT, RecoveryPolicy.Chunk.LOADED, false));
		assertEquals(RecoveryPolicy.Action.DROP_CHECKPOINT,
				RecoveryPolicy.decide(RecoveryPolicy.Cart.ABSENT, RecoveryPolicy.Chunk.MISSING, false));
		assertEquals(RecoveryPolicy.Action.DROP_CHECKPOINT,
				RecoveryPolicy.decide(RecoveryPolicy.Cart.ABSENT, RecoveryPolicy.Chunk.UNKNOWN, false),
				"a positionless checkpoint cannot hide a cart");
	}

	@Test
	void anAbsentCartInAnUnloadedChunkRequestsABoundedTicket() {
		assertEquals(RecoveryPolicy.Action.REQUEST_TICKET,
				RecoveryPolicy.decide(RecoveryPolicy.Cart.ABSENT, RecoveryPolicy.Chunk.UNLOADED, false));
	}

	@Test
	void theDeadlineAlwaysCleansAnUnresolvedCheckpoint() {
		assertEquals(RecoveryPolicy.Action.DROP_CHECKPOINT,
				RecoveryPolicy.decide(RecoveryPolicy.Cart.ABSENT, RecoveryPolicy.Chunk.UNLOADED, true));
	}

	@Test
	void onlyAwaitingChunkStatesBlockNewTrips() {
		assertTrue(RecoveryPolicy.blocksNewTrips(RecoveryPolicy.Action.REQUEST_TICKET));
		assertTrue(RecoveryPolicy.blocksNewTrips(RecoveryPolicy.Action.WAIT_FOR_CHUNK));
		assertFalse(RecoveryPolicy.blocksNewTrips(RecoveryPolicy.Action.DISCARD_CART));
		assertFalse(RecoveryPolicy.blocksNewTrips(RecoveryPolicy.Action.STOP_CART));
		assertFalse(RecoveryPolicy.blocksNewTrips(RecoveryPolicy.Action.DROP_CHECKPOINT));
	}
}
