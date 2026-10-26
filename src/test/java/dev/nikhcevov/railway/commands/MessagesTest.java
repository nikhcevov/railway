package dev.nikhcevov.railway.commands;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {
	@Test
	void formatsSuccessKeyWithPlaceholdersInEnglish() {
		assertEquals("Station alpha renamed to beta.",
				Messages.format(Messages.Lang.EN, "station.renamed", "alpha", "beta"));
		assertEquals("Button bound: alpha -> beta.",
				Messages.format(Messages.Lang.EN, "button.bound", "alpha", "beta"));
	}

	@Test
	void formatsSuccessKeyWithPlaceholdersInRussian() {
		assertEquals("Станция alpha переименована в beta.",
				Messages.format(Messages.Lang.RU, "station.renamed", "alpha", "beta"));
		assertEquals("Кнопка привязана: alpha -> beta.",
				Messages.format(Messages.Lang.RU, "button.bound", "alpha", "beta"));
	}

	@Test
	void failureTextContainsLocalizedBaseCoordinatesAndCode() {
		Failure failure = Failure.at(FailureCode.NO_PATH, new BlockLocation(4, -60, 7));

		assertEquals("No path between these stations on the current network. (4 -60 7) [NO_PATH]",
				Messages.failureText(Messages.Lang.EN, failure));
		assertEquals("Между станциями нет пути в текущей сети. (4 -60 7) [NO_PATH]",
				Messages.failureText(Messages.Lang.RU, failure));
	}

	@Test
	void failureTextInterpolatesTheDetailPlaceholder() {
		Failure failure = Failure.at(FailureCode.STATION_NOT_FOUND, new BlockLocation(0, 64, 0), "home");

		assertEquals("Station home not found. (0 64 0) [STATION_NOT_FOUND]",
				Messages.failureText(Messages.Lang.EN, failure));
		assertEquals("Станция home не найдена. (0 64 0) [STATION_NOT_FOUND]",
				Messages.failureText(Messages.Lang.RU, failure));
	}

	@Test
	void failureTextWithoutLocationStillAppendsTheCode() {
		Failure failure = Failure.of(FailureCode.INTERNAL);

		assertEquals("Internal Railway error. [INTERNAL]", Messages.failureText(Messages.Lang.EN, failure));
		assertEquals("Внутренняя ошибка Railway. [INTERNAL]", Messages.failureText(Messages.Lang.RU, failure));
	}

	@Test
	void unknownKeyFallsBackToTheKeyItself() {
		assertEquals("does.not.exist", Messages.format(Messages.Lang.EN, "does.not.exist"));
		assertEquals("does.not.exist", Messages.format(Messages.Lang.RU, "does.not.exist"));
		assertEquals("does.not.exist", Messages.format(Messages.Lang.RU, "does.not.exist", "ignored"));
	}
}
