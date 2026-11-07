package dev.nikhcevov.railway.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureJournalTest {
	@TempDir
	Path tempDir;

	@Test
	void keepsNewestEntriesAndFindsThemByRef() throws Exception {
		Path file = tempDir.resolve("failures.jsonl");
		FailureJournal journal = new FailureJournal(file, 3, 1L << 20);

		journal.append("WARN", "event=a code=NO_RAIL detail=\"no rail\" ref=aaaaaaaa");
		journal.append("INFO", "event=b code=NO_PATH detail=\"no path\" ref=bbbbbbbb");
		journal.append("ERROR", "event=c code=INTERNAL detail=\"boom\" ref=cccccccc");
		journal.append("WARN", "event=d code=SCAN_FAILED detail=\"failed\" ref=dddddddd");

		assertEquals(3, journal.size());
		List<FailureJournal.Entry> newest = journal.recent(2);
		assertEquals(2, newest.size());
		assertTrue(newest.get(0).line().contains("event=d"));
		assertTrue(newest.get(1).line().contains("event=c"));

		assertTrue(journal.find("dddddddd").isPresent());
		assertTrue(journal.find("bbbbbbbb").isPresent());
		assertFalse(journal.find("aaaaaaaa").isPresent());

		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		assertEquals(4, lines.size());
		assertTrue(lines.get(0).startsWith("{\"time\":\""), lines.get(0));
		assertTrue(lines.get(0).contains("\"level\":\"WARN\""), lines.get(0));
		assertTrue(lines.get(0).contains("\"line\":\"event=a code=NO_RAIL"), lines.get(0));
	}

	@Test
	void rotatesTheFileBeforeItGrowsPastTheLimit() throws Exception {
		Path file = tempDir.resolve("failures.jsonl");
		FailureJournal journal = new FailureJournal(file, 64, 200);

		for (int i = 0; i < 10; i++) {
			journal.append("WARN", "event=fail code=INTERNAL detail=\"rotation\" ref=" + i);
		}

		assertTrue(Files.exists(file), "current journal file");
		assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".1")), "first rotation");
		assertTrue(Files.size(file) <= 200, "current file stays bounded");
		assertEquals(10, journal.size(), "memory ring is not limited by file rotation");
	}

	@Test
	void swallowsIoErrorsInsteadOfFailingGameplay() {
		Path directoryAsFile = tempDir.resolve("not-a-file");
		assertTrue(directoryAsFile.toFile().mkdir());
		FailureJournal journal = new FailureJournal(directoryAsFile, 8, 1L << 20);

		journal.append("WARN", "event=x code=INTERNAL detail=\"io\" ref=eeeeeeee");

		assertEquals(1, journal.size());
	}
}
