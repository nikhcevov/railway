package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

import java.util.HashMap;
import java.util.Map;

/** In-memory {@link RailView} for scanner tests. Unset blocks probe as empty. */
final class FakeRailView implements RailView {
	private final Map<BlockLocation, RailProbe> probes = new HashMap<>();
	private int probeCount;

	FakeRailView put(BlockLocation pos, RailProbe probe) {
		probes.put(pos, probe);
		return this;
	}

	int probeCount() {
		return probeCount;
	}

	@Override
	public RailProbe probe(BlockLocation pos) {
		probeCount++;
		return probes.getOrDefault(pos, RailProbe.empty());
	}
}
