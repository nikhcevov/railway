package dev.nikhcevov.railway.recovery;

/**
 * Pure reconciliation policy for an interrupted trip checkpoint. It decides what
 * recovery may do with the recorded service cart without knowing anything about
 * Minecraft, which keeps the destructive decisions unit-testable.
 *
 * <p>Recovery never resumes a trip and never removes a cart it does not own: an
 * unmanaged cart is always preserved and only the checkpoint is dropped.
 */
public final class RecoveryPolicy {
	/** What is known about the recorded service cart right now. */
	public enum Cart {
		/** The cart UUID is not in any loaded level. */
		ABSENT,
		/** The managed cart is loaded and empty. */
		EMPTY,
		/** The managed cart is loaded and still carries a passenger. */
		OCCUPIED,
		/** The entity behind the UUID exists but is not a Railway service cart. */
		UNMANAGED
	}

	/** What is known about the checkpoint chunk. */
	public enum Chunk {
		/** The chunk is loaded and its entities are ticking: absence is confirmed. */
		LOADED,
		/** The chunk is not loaded yet; it may still contain the cart. */
		UNLOADED,
		/** The chunk does not exist in level storage: the cart cannot exist. */
		MISSING,
		/** The checkpoint has no recorded position (legacy checkpoint). */
		UNKNOWN
	}

	public enum Action {
		/** Safely remove the empty managed cart, then drop the checkpoint. */
		DISCARD_CART,
		/** Stop the occupied managed cart and keep the checkpoint until it is empty. */
		STOP_CART,
		/** Delete only the checkpoint; never touch an entity. */
		DROP_CHECKPOINT,
		/** Hold a bounded, non-generating recovery ticket for the recorded chunk. */
		REQUEST_TICKET,
		/** Wait for the chunk to load naturally; no ticket available. */
		WAIT_FOR_CHUNK
	}

	private RecoveryPolicy() {
	}

	/**
	 * @param deadlineExpired the bounded recovery window for this checkpoint is over
	 */
	public static Action decide(Cart cart, Chunk chunk, boolean deadlineExpired) {
		if (cart == Cart.UNMANAGED) {
			return Action.DROP_CHECKPOINT;
		}
		return switch (cart) {
			case EMPTY -> Action.DISCARD_CART;
			case OCCUPIED -> Action.STOP_CART;
			case ABSENT -> {
				if (deadlineExpired) {
					yield Action.DROP_CHECKPOINT;
				}
				yield switch (chunk) {
					// A loaded chunk is authoritative for entity presence; a chunk
					// that is not in storage cannot hide a saved cart.
					case LOADED, MISSING, UNKNOWN -> Action.DROP_CHECKPOINT;
					case UNLOADED -> Action.REQUEST_TICKET;
				};
			}
			case UNMANAGED -> Action.DROP_CHECKPOINT;
		};
	}

	/**
	 * Only a checkpoint that is still waiting for its chunk blocks new passenger
	 * trips. A located cart (stopped with its passenger or awaiting removal) never
	 * deadlocks boarding.
	 */
	public static boolean blocksNewTrips(Action action) {
		return action == Action.REQUEST_TICKET || action == Action.WAIT_FOR_CHUNK;
	}
}
