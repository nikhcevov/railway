package dev.nikhcevov.railway.domain;

/** Horizontal rail axis. Slopes and curves are not represented in the 1.0 MVP network. */
public enum RailAxis {
	X,
	Z;

	public RailAxis other() {
		return this == X ? Z : X;
	}
}
