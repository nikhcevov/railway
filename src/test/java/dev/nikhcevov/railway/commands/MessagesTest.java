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
	void stationInfoReportsSignUnitsAndMainArrival() {
		assertEquals("Sign units: 3", Messages.format(Messages.Lang.EN, "station.info.units", 3));
		assertEquals("Единиц табличек: 3", Messages.format(Messages.Lang.RU, "station.info.units", 3));
		assertEquals("Main arrival: 4 65 1",
				Messages.format(Messages.Lang.EN, "station.info.main", "4 65 1"));
		assertEquals("Main arrival: 4 65 1 (m flag)",
				Messages.format(Messages.Lang.EN, "station.info.main_marker", "4 65 1"));
		assertEquals("Главное прибытие: не задано",
				Messages.format(Messages.Lang.RU, "station.info.main_missing"));
	}

	@Test
	void failureTextContainsLocalizedBaseAndCoordinates() {
		Failure failure = Failure.at(FailureCode.NO_PATH, new BlockLocation(4, -60, 7));

		assertEquals("No path between these stations on the current network. (4 -60 7)",
				Messages.failureText(Messages.Lang.EN, failure));
		assertEquals("Между станциями нет пути в текущей сети. (4 -60 7)",
				Messages.failureText(Messages.Lang.RU, failure));
	}

	@Test
	void failureTextInterpolatesStationNames() {
		Failure failure = Failure.at(FailureCode.STATION_DISABLED, new BlockLocation(0, 64, 0), "Home");

		assertEquals("Station Home is disabled. (0 64 0)",
				Messages.failureText(Messages.Lang.EN, failure));
		assertEquals("Станция Home выключена. (0 64 0)",
				Messages.failureText(Messages.Lang.RU, failure));
	}

	@Test
	void failureTextHidesInternalCodesAndLocalizesSignDetails() {
		Failure failure = Failure.detail(FailureCode.SIGN_GEOMETRY, "NO_RAIL");

		assertEquals("Invalid sign unit: no rail was found on either side of the sign.",
				Messages.failureText(Messages.Lang.EN, failure));
		assertEquals("Неверная геометрия таблички: рядом с табличкой не найден рельс.",
				Messages.failureText(Messages.Lang.RU, failure));
		assertEquals("Internal Railway error.",
				Messages.failureText(Messages.Lang.EN, Failure.of(FailureCode.INTERNAL)));
	}

	@Test
	void unknownKeyFallsBackToTheKeyItself() {
		assertEquals("does.not.exist", Messages.format(Messages.Lang.EN, "does.not.exist"));
		assertEquals("does.not.exist", Messages.format(Messages.Lang.RU, "does.not.exist"));
		assertEquals("does.not.exist", Messages.format(Messages.Lang.RU, "does.not.exist", "ignored"));
	}
}
