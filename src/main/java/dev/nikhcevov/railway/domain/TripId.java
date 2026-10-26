package dev.nikhcevov.railway.domain;

import java.util.Objects;
import java.util.UUID;

public record TripId(UUID value) {
	public TripId {
		Objects.requireNonNull(value, "value");
	}

	public static TripId random() {
		return new TripId(UUID.randomUUID());
	}

	public static TripId parse(String value) {
		return new TripId(UUID.fromString(value));
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
