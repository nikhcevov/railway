package dev.nikhcevov.railway.network;

/** Tri-state rail lookup: no rail, rail, or chunk not loaded. */
public sealed interface RailProbe {
	record Unloaded() implements RailProbe {
	}

	record Empty() implements RailProbe {
	}

	record Rail(RailInfo info) implements RailProbe {
	}

	static RailProbe empty() {
		return new Empty();
	}

	static RailProbe unloaded() {
		return new Unloaded();
	}

	static RailProbe rail(RailInfo info) {
		return new Rail(info);
	}
}
