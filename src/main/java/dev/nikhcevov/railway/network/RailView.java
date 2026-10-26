package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

/** Adapter contract for reading rail blocks. Implementations run on the server thread. */
public interface RailView {
	RailProbe probe(BlockLocation pos);
}
