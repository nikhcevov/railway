package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.domain.StationId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanRetryCooldownTest {
	private static final StationId HOME = new StationId("home");
	private static final StationId MINE = new StationId("mine");

	@Test
	void allowsTheFirstAutomaticScan() {
		ScanRetryCooldown cooldown = new ScanRetryCooldown(200);

		assertTrue(cooldown.allows(HOME, 0));
	}

	@Test
	void blocksRetriesUntilTheAttemptCooldownElapses() {
		ScanRetryCooldown cooldown = new ScanRetryCooldown(200);
		cooldown.recordAttempt(HOME, 1_000);

		assertFalse(cooldown.allows(HOME, 1_000));
		assertFalse(cooldown.allows(HOME, 1_199));
		assertTrue(cooldown.allows(HOME, 1_200));
	}

	@Test
	void aFailureExtendsTheCooldownFromTheFailureTick() {
		ScanRetryCooldown cooldown = new ScanRetryCooldown(200);
		cooldown.recordAttempt(HOME, 1_000);
		cooldown.recordFailure(HOME, 1_050);

		assertFalse(cooldown.allows(HOME, 1_249));
		assertTrue(cooldown.allows(HOME, 1_250));
	}

	@Test
	void stationsHaveIndependentCooldowns() {
		ScanRetryCooldown cooldown = new ScanRetryCooldown(200);
		cooldown.recordAttempt(HOME, 40);

		assertFalse(cooldown.allows(HOME, 41));
		assertTrue(cooldown.allows(MINE, 41));
	}

	@Test
	void aSuccessfulScanClearsTheCooldown() {
		ScanRetryCooldown cooldown = new ScanRetryCooldown(200);
		cooldown.recordAttempt(HOME, 40);
		cooldown.clear(HOME);

		assertTrue(cooldown.allows(HOME, 40));
	}

	@Test
	void clearResetsEveryStation() {
		ScanRetryCooldown cooldown = new ScanRetryCooldown(200);
		cooldown.recordAttempt(HOME, 40);
		cooldown.recordAttempt(MINE, 40);
		cooldown.clear();

		assertTrue(cooldown.allows(HOME, 40));
		assertTrue(cooldown.allows(MINE, 40));
	}

	@Test
	void rejectsNegativeCooldown() {
		assertThrows(IllegalArgumentException.class, () -> new ScanRetryCooldown(-1));
	}
}
