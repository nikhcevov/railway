package dev.nikhcevov.railway.sign;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure parser for the three text lines of a Railway service sign.
 *
 * <p>Line 1 is the {@code [Railway]} marker (case-insensitive), line 2 is the
 * {@code from} station name, and line 3 is the {@code to} station name. A blank
 * line 3 is a station-only anchor, represented by an empty {@code to} name.
 *
 * <p>Display labels are preserved; only surrounding whitespace is stripped and
 * internal whitespace is collapsed. No Minecraft classes are involved and the
 * parser has no side effects.
 */
public final class SignFormat {
	public static final String MARKER = "[Railway]";
	public static final int LINE_COUNT = 3;

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final List<String> UNSUPPORTED_MARKERS = List.of("[go]", "[station]");

	private SignFormat() {
	}

	/** Parses exactly three sign text lines in front-to-back order. */
	public static Result parse(List<String> lines) {
		if (lines == null || lines.size() != LINE_COUNT) {
			return new Rejected(Error.LINE_COUNT, "expected " + LINE_COUNT + " sign lines");
		}
		return parse(lines.get(0), lines.get(1), lines.get(2));
	}

	/** Parses the three raw sign text lines. Null lines are treated as blank. */
	public static Result parse(String markerLine, String fromLine, String toLine) {
		String marker = normalize(markerLine);
		if (marker.isEmpty()) {
			return new Rejected(Error.MISSING_MARKER, "line 1 must be " + MARKER);
		}
		if (!marker.equalsIgnoreCase(MARKER)) {
			for (String unsupported : UNSUPPORTED_MARKERS) {
				if (marker.equalsIgnoreCase(unsupported)) {
					return new Rejected(Error.UNSUPPORTED_MARKER, marker);
				}
			}
			return new Rejected(Error.INVALID_MARKER, marker);
		}

		String from = normalize(fromLine);
		if (from.isEmpty()) {
			return new Rejected(Error.MISSING_FROM, "line 2 must name the source station");
		}

		return new Parsed(from, normalize(toLine));
	}

	/** Strips surrounding whitespace and collapses internal runs to one space. */
	public static String normalize(String value) {
		if (value == null) {
			return "";
		}
		return WHITESPACE.matcher(value.strip()).replaceAll(" ");
	}

	/**
	 * Returns whether a line is a known service marker, current or retired. Callers use
	 * this to distinguish an intentionally edited service sign from an ordinary sign.
	 */
	public static boolean isKnownMarkerLine(String line) {
		String normalized = normalize(line);
		if (normalized.equalsIgnoreCase(MARKER)) {
			return true;
		}
		for (String unsupported : UNSUPPORTED_MARKERS) {
			if (normalized.equalsIgnoreCase(unsupported)) {
				return true;
			}
		}
		return false;
	}

	/** Outcome of parsing one service sign. */
	public sealed interface Result permits Parsed, Rejected {
	}

	/**
	 * Valid sign text.
	 *
	 * @param from normalized display name of the source station
	 * @param to   normalized display name of the destination, empty for a
	 *             station-only anchor
	 */
	public record Parsed(String from, String to) implements Result {
		public Parsed {
			Objects.requireNonNull(from, "from");
			Objects.requireNonNull(to, "to");
			if (from.isEmpty()) {
				throw new IllegalArgumentException("from must not be blank");
			}
		}

		public boolean stationOnly() {
			return to.isEmpty();
		}
	}

	/** Rejected sign text with a stable error code for user messaging. */
	public record Rejected(Error error, String detail) implements Result {
		public Rejected {
			Objects.requireNonNull(error, "error");
			Objects.requireNonNull(detail, "detail");
		}
	}

	/** Typed parse failure. */
	public enum Error {
		/** The sign did not provide exactly three lines. */
		LINE_COUNT,
		/** Line 1 was blank. */
		MISSING_MARKER,
		/** Line 1 used a retired marker such as {@code [go]} or {@code [station]}. */
		UNSUPPORTED_MARKER,
		/** Line 1 was not the {@code [Railway]} marker. */
		INVALID_MARKER,
		/** Line 2 was blank, so the source station is unknown. */
		MISSING_FROM
	}
}
