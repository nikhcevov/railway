package dev.nikhcevov.railway.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Public station identifier: {@code [a-z0-9_-]{1,32}}. */
public record StationId(String value) {
	private static final Pattern PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

	public StationId {
		Objects.requireNonNull(value, "value");
		if (!PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("invalid station id: " + value);
		}
	}

	public static boolean isValid(String value) {
		return value != null && PATTERN.matcher(value).matches();
	}

	@Override
	public String toString() {
		return value;
	}
}
