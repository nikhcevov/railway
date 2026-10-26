package dev.nikhcevov.railway.sign;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure parser for the text lines of a Railway service sign.
 *
 * <p>Line 1 is the {@code [Railway]} marker (case-insensitive), line 2 is the
 * {@code from} station name, line 3 is the {@code to} station name, and the
 * optional line 4 carries flags. A blank line 3 is a station-only anchor,
 * represented by an empty {@code to} name. A blank or missing line 4 means no
 * flags; the only known flag is {@code m}, which marks the sign as the station's
 * main arrival point.
 *
 * <p>Display labels are preserved; only surrounding whitespace is stripped and
 * internal whitespace is collapsed. No Minecraft classes are involved and the
 * parser has no side effects.
 */
public final class SignFormat {
	public static final String MARKER = "[Railway]";
	/** Minimum sign line count, kept for three-line compatibility. */
	public static final int LINE_COUNT = 3;
	/** Maximum sign line count, including the optional flags line. */
	public static final int MAX_LINE_COUNT = 4;
	/** The only recognized flag: make this unit the station's main arrival point. */
	public static final String MAIN_FLAG = "m";

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final List<String> UNSUPPORTED_MARKERS = List.of("[go]", "[station]");

	private SignFormat() {
	}

	/**
	 * Parses three or four sign text lines in front-to-back order. A fourth line
	 * is the optional flags line.
	 */
	public static Result parse(List<String> lines) {
		if (lines == null || lines.size() < LINE_COUNT || lines.size() > MAX_LINE_COUNT) {
			return new Rejected(Error.LINE_COUNT,
					"expected " + LINE_COUNT + " or " + MAX_LINE_COUNT + " sign lines");
		}
		return parse(lines.get(0), lines.get(1), lines.get(2),
				lines.size() == MAX_LINE_COUNT ? lines.get(3) : null);
	}

	/** Parses the three raw sign text lines; no flags line means no flags. */
	public static Result parse(String markerLine, String fromLine, String toLine) {
		return parse(markerLine, fromLine, toLine, null);
	}

	/**
	 * Parses the three raw sign text lines plus the optional flags line. Null lines
	 * are treated as blank.
	 */
	public static Result parse(String markerLine, String fromLine, String toLine, String flagsLine) {
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

		String flags = normalize(flagsLine);
		boolean main = false;
		if (!flags.isEmpty()) {
			String[] tokens = flags.split(" ");
			if (tokens.length > 1) {
				return new Rejected(Error.INVALID_FLAGS,
						"expected at most one flag but got \"" + flags + "\"");
			}
			if (!tokens[0].equalsIgnoreCase(MAIN_FLAG)) {
				return new Rejected(Error.INVALID_FLAGS, "unknown flag \"" + tokens[0] + "\"");
			}
			main = true;
		}

		return new Parsed(from, normalize(toLine), main);
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
	 * @param main whether the optional {@code m} flag marks this unit as the
	 *             station's main arrival point
	 */
	public record Parsed(String from, String to, boolean main) implements Result {
		public Parsed {
			Objects.requireNonNull(from, "from");
			Objects.requireNonNull(to, "to");
			if (from.isEmpty()) {
				throw new IllegalArgumentException("from must not be blank");
			}
		}

		/** Three-line compatibility constructor: no flags, so {@code main} is false. */
		public Parsed(String from, String to) {
			this(from, to, false);
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
		/** The sign did not provide three or four lines. */
		LINE_COUNT,
		/** Line 1 was blank. */
		MISSING_MARKER,
		/** Line 1 used a retired marker such as {@code [go]} or {@code [station]}. */
		UNSUPPORTED_MARKER,
		/** Line 1 was not the {@code [Railway]} marker. */
		INVALID_MARKER,
		/** Line 2 was blank, so the source station is unknown. */
		MISSING_FROM,
		/** Line 4 had an unknown or extra flag; only {@code m} is known. */
		INVALID_FLAGS
	}
}
