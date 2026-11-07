package dev.nikhcevov.railway.logging;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * One structured, greppable log line per event: {@code event=name key=value ...}.
 *
 * <p>Values containing whitespace, quotes, backslashes, or {@code =} are quoted
 * and escaped, so a line can always be split on whitespace outside quotes.
 * {@link #failure(Failure)} adds {@code code}, {@code pos}, {@code detail} and a
 * short {@code ref}; the same ref identifies the event in the failure journal
 * and in player chat.
 *
 * <p>Logging methods take the message as an SLF4J argument ({@code "{}"}), so
 * braces or percent signs inside a detail can never break formatting.
 */
public final class EventLog {
	/** Detail values are truncated to keep one event on one readable line. */
	private static final int MAX_VALUE_LENGTH = 200;

	private final StringBuilder line = new StringBuilder(96);
	private Failure failure;
	private Throwable cause;
	private String ref;

	private EventLog(String event) {
		line.append("event=").append(event);
	}

	public static EventLog event(String name) {
		return new EventLog(name);
	}

	public EventLog tick(long tick) {
		return field("tick", tick);
	}

	public EventLog scan(String jobId) {
		return field("scan", jobId);
	}

	public EventLog trip(TripId id) {
		return field("trip", id.value());
	}

	public EventLog station(StationId id, String displayName) {
		return field("station", id.value()).field("name", displayName);
	}

	public EventLog revision(long revision) {
		return field("rev", revision);
	}

	public EventLog phase(Object phase) {
		return field("phase", String.valueOf(phase));
	}

	public EventLog trigger(Object trigger) {
		return field("trigger", String.valueOf(trigger));
	}

	public EventLog field(String key, String value) {
		if (value != null && !value.isEmpty()) {
			line.append(' ').append(key).append('=').append(tokenize(value));
		}
		return this;
	}

	public EventLog field(String key, long value) {
		line.append(' ').append(key).append('=').append(value);
		return this;
	}

	public EventLog field(String key, UUID value) {
		return value == null ? this : field(key, value.toString());
	}

	public EventLog field(String key, BlockLocation value) {
		return value == null ? this : field(key, value.shortString());
	}

	/** Adds the failure code, offending position, detail, and its stable ref. */
	public EventLog failure(Failure value) {
		if (value == null) {
			return this;
		}
		failure = value;
		field("code", value.code().name());
		if (value.location() != null) {
			field("pos", value.location().shortString());
		}
		line.append(" detail=").append(quote(detail(value)));
		ref = value.ref();
		field("ref", ref);
		return this;
	}

	/**
	 * A logged failure never carries an empty detail: a code-only failure uses
	 * its readable code as the detail, while chat text stays untouched.
	 */
	private static String detail(Failure value) {
		if (value.detail() != null && !value.detail().isBlank()) {
			return value.detail();
		}
		return value.code().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
	}

	/** Attaches an exception that is not wrapped in a {@link Failure}. */
	public EventLog cause(Throwable error) {
		this.cause = error;
		return this;
	}

	public String line() {
		return line.toString();
	}

	public Failure failure() {
		return failure;
	}

	public String ref() {
		return ref;
	}

	public void debug(Logger logger) {
		logger.debug("{}", line());
	}

	public void info(Logger logger) {
		logger.info("{}", line());
	}

	public void warn(Logger logger) {
		FailureJournal.record("WARN", line());
		Throwable cause = cause();
		if (cause == null) {
			logger.warn("{}", line());
		} else {
			logger.warn("{}", line(), cause);
		}
	}

	public void error(Logger logger) {
		FailureJournal.record("ERROR", line());
		Throwable cause = cause();
		if (cause == null) {
			logger.error("{}", line());
		} else {
			logger.error("{}", line(), cause);
		}
	}

	private Throwable cause() {
		if (failure != null && failure.cause() != null) {
			return failure.cause();
		}
		return cause;
	}

	private static String tokenize(String value) {
		String bounded = bound(value);
		if (!needsQuotes(bounded)) {
			return bounded;
		}
		return quote(bounded);
	}

	private static String quote(String value) {
		String bounded = bound(value);
		StringBuilder quoted = new StringBuilder(bounded.length() + 2).append('"');
		for (int i = 0; i < bounded.length(); i++) {
			char c = bounded.charAt(i);
			switch (c) {
				case '"' -> quoted.append("\\\"");
				case '\\' -> quoted.append("\\\\");
				case '\n' -> quoted.append("\\n");
				case '\r' -> quoted.append("\\r");
				case '\t' -> quoted.append("\\t");
				default -> quoted.append(c);
			}
		}
		return quoted.append('"').toString();
	}

	private static boolean needsQuotes(String value) {
		if (value.isEmpty()) {
			return true;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isWhitespace(c) || c == '"' || c == '\\' || c == '=') {
				return true;
			}
		}
		return false;
	}

	private static String bound(String value) {
		if (value.length() <= MAX_VALUE_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_VALUE_LENGTH) + "...";
	}
}
