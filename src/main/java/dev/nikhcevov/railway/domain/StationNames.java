package dev.nikhcevov.railway.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Name identity rules for stations. Station names are player-facing labels that are
 * unique per dimension; comparison trims leading/trailing whitespace, collapses internal
 * whitespace and ignores case. The first registered spelling is the display name.
 *
 * <p>Sign-created stations use a hidden identifier derived deterministically from the
 * dimension and normalized name. The identifier is never shown on a sign.
 */
public final class StationNames {
	private static final char SEPARATOR = '\u0000';
	private static final int HASH_CHARS = 20;
	private static final int MAX_ATTEMPTS = 1_000_000;

	private StationNames() {
	}

	/** Trims and collapses internal whitespace, preserving spelling. Blank input maps to {@code ""}. */
	public static String normalize(String name) {
		if (name == null) {
			return "";
		}
		String trimmed = name.strip();
		StringBuilder normalized = new StringBuilder(trimmed.length());
		boolean pendingSpace = false;
		for (int i = 0; i < trimmed.length(); ) {
			int codePoint = trimmed.codePointAt(i);
			i += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
				pendingSpace = !normalized.isEmpty();
				continue;
			}
			if (pendingSpace) {
				normalized.append(' ');
				pendingSpace = false;
			}
			normalized.appendCodePoint(codePoint);
		}
		return normalized.toString();
	}

	public static boolean isValid(String name) {
		return !normalize(name).isEmpty();
	}

	/** Case-insensitive identity key for a station name within one dimension. */
	public static String lookupKey(String dimension, String name) {
		Objects.requireNonNull(dimension, "dimension");
		String normalized = normalize(name);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("station name must not be blank");
		}
		return dimension.strip().toLowerCase(Locale.ROOT) + SEPARATOR + normalized.toLowerCase(Locale.ROOT);
	}

	public static boolean matches(Station station, String dimension, String name) {
		Objects.requireNonNull(station, "station");
		if (dimension == null || !isValid(name)) {
			return false;
		}
		return lookupKey(station.dimension(), station.displayName()).equals(lookupKey(dimension, name));
	}

	public static List<Station> find(Collection<Station> stations, String dimension, String name) {
		Objects.requireNonNull(stations, "stations");
		if (dimension == null || !isValid(name)) {
			return List.of();
		}
		List<Station> matches = new ArrayList<>();
		for (Station station : stations) {
			if (matches(station, dimension, name)) {
				matches.add(station);
			}
		}
		return List.copyOf(matches);
	}

	/** The single station with this name, or empty when absent or ambiguous. */
	public static Optional<Station> unique(Collection<Station> stations, String dimension, String name) {
		Station found = null;
		for (Station station : stations) {
			if (matches(station, dimension, name)) {
				if (found != null) {
					return Optional.empty();
				}
				found = station;
			}
		}
		return Optional.ofNullable(found);
	}

	public static boolean isAmbiguous(Collection<Station> stations, String dimension, String name) {
		return find(stations, dimension, name).size() > 1;
	}

	public static boolean isAvailable(Collection<Station> stations, String dimension, String name) {
		return find(stations, dimension, name).isEmpty();
	}

	/** Display spelling of the first station registered under this identity. */
	public static Optional<String> displaySpelling(Collection<Station> stations, String dimension, String name) {
		List<Station> matches = find(stations, dimension, name);
		return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst().displayName());
	}

	/** Hidden deterministic identifier for a sign-created station with no collisions recorded. */
	public static StationId generatedId(String dimension, String name) {
		return generatedId(dimension, name, List.of());
	}

	/**
	 * Hidden deterministic identifier for {@code dimension + normalized name}. If the
	 * base identifier is already held by a different station identity, the next
	 * deterministic suffix is used. Regenerating for the same identity returns the same id.
	 */
	public static StationId generatedId(String dimension, String name, Collection<Station> existing) {
		Objects.requireNonNull(existing, "existing");
		String key = lookupKey(dimension, name);
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			StationId candidate = new StationId(candidateValue(key, attempt));
			Station holder = null;
			for (Station station : existing) {
				if (station.id().equals(candidate)) {
					holder = station;
					break;
				}
			}
			if (holder == null || lookupKey(holder.dimension(), holder.displayName()).equals(key)) {
				return candidate;
			}
		}
		throw new IllegalStateException("cannot generate a unique station id for " + key);
	}

	/** Variant used when the caller owns the collision check. */
	public static StationId generatedId(String dimension, String name, Predicate<StationId> taken) {
		Objects.requireNonNull(taken, "taken");
		String key = lookupKey(dimension, name);
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			StationId candidate = new StationId(candidateValue(key, attempt));
			if (!taken.test(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("cannot generate a unique station id for " + key);
	}

	private static String candidateValue(String lookupKey, int attempt) {
		byte[] digest = digest(lookupKey + SEPARATOR + attempt);
		StringBuilder value = new StringBuilder(HASH_CHARS + 6);
		for (int i = 0; i < HASH_CHARS / 2; i++) {
			int unsigned = digest[i] & 0xFF;
			if (unsigned < 0x10) {
				value.append('0');
			}
			value.append(Integer.toHexString(unsigned));
		}
		if (attempt > 0) {
			value.append('-').append(Integer.toHexString(attempt));
		}
		return value.toString();
	}

	private static byte[] digest(String seed) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
