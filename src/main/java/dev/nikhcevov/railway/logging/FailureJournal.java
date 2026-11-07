package dev.nikhcevov.railway.logging;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Recent WARN/ERROR events, kept in memory and appended to
 * {@code <world>/railway/failures.jsonl}.
 *
 * <p>Each entry is the exact rendered log line plus its wall-clock time and
 * level, so an admin can read the last failure from chat and the full history
 * from disk. The file rotates at a fixed size; the in-memory ring is bounded.
 * I/O errors never propagate into game code: memory keeps working and the next
 * append retries.
 */
public final class FailureJournal {
	public static final int DEFAULT_CAPACITY = 256;
	public static final long DEFAULT_MAX_BYTES = 1L << 20;

	private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
	private static volatile FailureJournal active;

	private final Path file;
	private final int capacity;
	private final long maxBytes;
	private final Deque<Entry> entries = new ArrayDeque<>();

	public record Entry(Instant time, String level, String line) {
		@Override
		public String toString() {
			return "[" + time + "] " + level + " " + line;
		}
	}

	public FailureJournal(Path file, int capacity, long maxBytes) {
		this.file = file;
		this.capacity = Math.max(1, capacity);
		this.maxBytes = Math.max(1, maxBytes);
	}

	/** Installs the process-wide journal used by {@link EventLog}. */
	public static FailureJournal install(Path file) {
		FailureJournal journal = new FailureJournal(file, DEFAULT_CAPACITY, DEFAULT_MAX_BYTES);
		active = journal;
		return journal;
	}

	/** Current journal, or {@code null} before {@link #install(Path)}. */
	public static FailureJournal active() {
		return active;
	}

	/** Records an event when a journal is installed; never throws. */
	public static void record(String level, String line) {
		FailureJournal journal = active;
		if (journal != null) {
			journal.append(level, line);
		}
	}

	public synchronized void append(String level, String line) {
		Entry entry = new Entry(Instant.now(), level, line);
		entries.addFirst(entry);
		while (entries.size() > capacity) {
			entries.removeLast();
		}
		write(entry);
	}

	/** Newest first. */
	public synchronized List<Entry> recent(int count) {
		int limit = Math.max(0, Math.min(count, entries.size()));
		List<Entry> result = new ArrayList<>(limit);
		for (Entry entry : entries) {
			if (result.size() >= limit) {
				break;
			}
			result.add(entry);
		}
		return result;
	}

	/** Newest first. */
	public synchronized Optional<Entry> find(String ref) {
		if (ref == null || ref.isBlank()) {
			return Optional.empty();
		}
		String needle = " ref=" + ref;
		for (Entry entry : entries) {
			if (entry.line().contains(needle)) {
				return Optional.of(entry);
			}
		}
		return Optional.empty();
	}

	public synchronized int size() {
		return entries.size();
	}

	public Path file() {
		return file;
	}

	private void write(Entry entry) {
		String json = "{\"time\":\"" + entry.time() + "\",\"level\":\"" + entry.level()
				+ "\",\"line\":" + GSON.toJson(entry.line()) + "}\n";
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			rotateIfNeeded(json.getBytes(StandardCharsets.UTF_8).length);
			Files.writeString(file, json, StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException ignored) {
			// The journal must never break gameplay; memory keeps the recent entries.
		}
	}

	private void rotateIfNeeded(long incoming) throws IOException {
		if (!Files.exists(file)) {
			return;
		}
		long size = Files.size(file);
		if (size + incoming <= maxBytes) {
			return;
		}
		Path first = file.resolveSibling(file.getFileName() + ".1");
		Path second = file.resolveSibling(file.getFileName() + ".2");
		Files.deleteIfExists(second);
		if (Files.exists(first)) {
			Files.move(first, second, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.move(file, first, StandardCopyOption.REPLACE_EXISTING);
	}
}
