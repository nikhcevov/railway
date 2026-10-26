package dev.nikhcevov.railway.persistence;

import java.io.IOException;

/** Persistence contract for authoritative state. Derived data (scans/routes) is never stored. */
public interface StateStore {
	WorldState load() throws StateException;

	void save(WorldState state) throws IOException;

	final class StateException extends Exception {
		public StateException(String message) {
			super(message);
		}

		public StateException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
