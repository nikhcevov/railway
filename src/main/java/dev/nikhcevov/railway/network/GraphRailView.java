package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;

/** Rail lookup for the stage-2 graph scanner. */
public interface GraphRailView {
	sealed interface Probe {
		record Unloaded() implements Probe {
		}

		record Empty() implements Probe {
		}

		record Rail(RailShapeType shape, RailKind kind) implements Probe {
		}
	}

	Probe graphProbe(BlockLocation pos);

	static Probe empty() {
		return new Probe.Empty();
	}

	static Probe unloaded() {
		return new Probe.Unloaded();
	}

	static Probe rail(RailShapeType shape, RailKind kind) {
		return new Probe.Rail(shape, kind);
	}
}
