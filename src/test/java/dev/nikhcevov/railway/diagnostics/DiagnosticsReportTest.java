package dev.nikhcevov.railway.diagnostics;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.diagnostics.DiagnosticsReport.BindingSummary;
import dev.nikhcevov.railway.diagnostics.DiagnosticsReport.GraphSummary;
import dev.nikhcevov.railway.diagnostics.DiagnosticsReport.ScanSummary;
import dev.nikhcevov.railway.diagnostics.DiagnosticsReport.StationSummary;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsReportTest {
	private static final StationId ALPHA = new StationId("alpha");
	private static final StationId BETA = new StationId("beta");
	private static final TripId TRIP = TripId.parse("00000000-0000-0000-0000-000000000001");
	private static final UUID CART = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@Test
	void rendersGlobalTripsReservationsScansTicketsAndStations() {
		List<String> lines = report().render(Messages.Lang.EN);

		assertContains(lines, "Railway diagnostics");
		assertContains(lines, "Railway: enabled");
		assertContains(lines, "State: loaded, revision 7, checkpoints 1");
		assertContains(lines, "Limits: maxActiveTrips 8, maxManagedChunkTickets 128, "
				+ "maxRouteLength 10000, scan 250 rails/tick, 2 ms, maxRailsPerScan 50000");
		assertContains(lines, "Recovery: pending 1, blocking 1, tickets 1, checkpoints removed 2, "
				+ "carts discarded/stopped/preserved 3/4/5, probes 6, late chunks 7, deadlines 8");
		assertContains(lines, "Active trips (1):");
		assertContains(lines, "Trip " + TRIP + " | RUNNING | alpha -> beta | "
				+ "player 00000000-0000-0000-0000-000000000002 cart " + CART);
		assertContains(lines, "  rev 9 comp comp-1 len 42 | docks 1 64 2 -> 30 64 2 | "
				+ "tickets 2/4 pending 1 checks 5");
		assertContains(lines, "Reservations: owned 4, quarantined 1");
		assertContains(lines, "  quarantined rail:alpha:1,64,2: cart removed (trip " + TRIP
				+ ", cart " + CART + ")");
		assertContains(lines, "Scan jobs (1):");
		assertContains(lines, "Scan scan-1 @ alpha: complete, rails 10, switches 2, revision 9");
		assertContains(lines, "Chunk tickets: held 2/128, pending 1; recovery checkpoints 1, "
				+ "tickets 1, acq 2, rel 1; scans 3, tickets 2, acq 4, rel 3");
		assertContains(lines, "Stations (2):");
		assertContains(lines, "alpha (Alpha) | minecraft:overworld | enabled | rev 3 | "
				+ "dock 1 64 2 | exit 1 64 4 | units 2");
		assertContains(lines, "beta (Beta) | disabled | provisional (not anchored) | units 1");
	}

	@Test
	void rendersFocusedStationMainBindingsAndGraph() {
		List<String> lines = report().render(Messages.Lang.EN);

		assertContains(lines, "Station detail alpha (Alpha) | minecraft:overworld | 1 64 2");
		assertContains(lines, "  main: 1 64 5 (m flag)");
		assertContains(lines, "  bindings: alpha->beta @ 0 64 4 dock 1 64 2");
		assertContains(lines, "  scan: Scan scan-1 @ alpha: complete, rails 10, switches 2, revision 9");
		assertContains(lines, "  graph: revision 9, component comp-1, rails 10, switches 2");
	}

	@Test
	void rendersRussianCatalogWithoutUntranslatedDiagnosticsKeys() {
		List<String> lines = report().render(Messages.Lang.RU);

		assertContains(lines, "Диагностика Railway");
		assertContains(lines, "Railway: включён");
		assertContains(lines, "Состояние: загружено, revision 7, чекпоинтов 1");
		assertContains(lines, "Активные поездки (1):");
		assertContains(lines, "Резервирования: занято 4, карантин 1");
		assertContains(lines, "Задания сканирования (1):");
		assertContains(lines, "Чанк-tickets: занято 2/128, ожидает 1; восстановление: "
				+ "чекпоинтов 1, tickets 1, получено 2, освобождено 1; сканы 3, tickets 2, "
				+ "получено 4, освобождено 3");
		assertContains(lines, "Станции (2):");
		assertContains(lines, "beta (Beta) | выключена | provisional (не закреплена) | единиц 1");
		assertContains(lines, "Детали станции alpha (Alpha) | minecraft:overworld | 1 64 2");
		assertContains(lines, "  главное прибытие: 1 64 5 (флаг m)");
		for (String line : lines) {
			assertFalse(line.startsWith("diagnostics."), "untranslated key in line: " + line);
		}
	}

	@Test
	void rendersDisabledEmptyReportWithoutFocusSection() {
		DiagnosticsReport report = new DiagnosticsReport(
				new DiagnosticsReport.Global(false, "bad config", false, 0, 0,
						new DiagnosticsReport.Limits(1, 0, 1, 1, 1, 1),
						new DiagnosticsReport.RecoverySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
				List.of(), 0, List.of(), List.of(),
				new DiagnosticsReport.TicketSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				List.of(), Optional.empty());

		List<String> lines = report.render(Messages.Lang.EN);

		assertContains(lines, "Railway: disabled (bad config)");
		assertContains(lines, "State: not loaded, revision 0, checkpoints 0");
		assertContains(lines, "Active trips: none");
		assertContains(lines, "Scan jobs: none");
		assertContains(lines, "Chunk tickets: held 0/0, pending 0; recovery checkpoints 0, "
				+ "tickets 0, acq 0, rel 0; scans 0, tickets 0, acq 0, rel 0");
		assertContains(lines, "Stations: none");
		assertFalse(lines.stream().anyMatch(line -> line.startsWith("Station detail")));
	}

	@Test
	void rendersRunningAndFailedScanStatusesWithFailureCode() {
		ScanSummary running = new ScanSummary("scan-r", ALPHA, 12, 1, true, null, 4, null);
		ScanSummary failed = new ScanSummary("scan-f", BETA, 3, 1, false,
				Failure.at(FailureCode.NO_RAIL, new BlockLocation(4, 64, 7), "detail"), 5, null);
		DiagnosticsReport report = emptyReport(List.of(running, failed), List.of(),
				Optional.empty());

		List<String> lines = report.render(Messages.Lang.EN);

		assertContains(lines, "Scan scan-r @ alpha: running, rails 12, switches 1, revision 4");
		assertContains(lines, "Scan scan-f @ beta: failed, rails 3, switches 1, revision 5: "
				+ "No rail block at the target position. (4 64 7) [NO_RAIL]");
	}

	@Test
	void rendersDockFallbackWhenAnchoredStationHasNoMainMarker() {
		StationSummary gamma = new StationSummary(new StationId("gamma"), "Gamma",
				"minecraft:overworld", true, 2, false, new BlockLocation(5, 64, 2), null, 0,
				null, List.of(), null);
		DiagnosticsReport report = emptyReport(List.of(), List.of(gamma), Optional.of(gamma));

		List<String> lines = report.render(Messages.Lang.EN);

		assertContains(lines, "  main: 5 64 2 (dock fallback)");
		assertContains(lines, "  bindings: none");
		assertContains(lines, "  scan: none");
	}

	private static DiagnosticsReport emptyReport(List<ScanSummary> scans, List<StationSummary> stations,
			Optional<StationSummary> focused) {
		return new DiagnosticsReport(
				new DiagnosticsReport.Global(true, null, true, 7, 0,
						new DiagnosticsReport.Limits(8, 128, 10_000, 250, 2, 50_000),
						new DiagnosticsReport.RecoverySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
				List.of(), 0, List.of(), scans,
				new DiagnosticsReport.TicketSummary(0, 128, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				stations, focused);
	}

	private static DiagnosticsReport report() {
		SignMarker main = new SignMarker("minecraft:overworld", new BlockLocation(1, 64, 5),
				new BlockLocation(1, 63, 5), false, null, ALPHA, ALPHA, true);
		ScanSummary scan = new ScanSummary("scan-1", ALPHA, 10, 2, false, null, 9,
				new GraphSummary(9, "comp-1", 10, 2));
		StationSummary alpha = new StationSummary(ALPHA, "Alpha", "minecraft:overworld", true,
				3, false, new BlockLocation(1, 64, 2), new BlockLocation(1, 64, 4), 2, main,
				List.of(new BindingSummary(ALPHA, BETA, new BlockLocation(0, 64, 4),
						new BlockLocation(1, 64, 2))),
				scan);
		StationSummary beta = new StationSummary(BETA, "Beta", "minecraft:overworld", false,
				1, true, null, null, 1, null, List.of(), null);

		return new DiagnosticsReport(
				new DiagnosticsReport.Global(true, null, true, 7, 1,
						new DiagnosticsReport.Limits(8, 128, 10_000, 250, 2, 50_000),
						new DiagnosticsReport.RecoverySummary(1, 1, 1, 2, 0, 2, 3, 4, 5, 6, 7, 8)),
				List.of(new DiagnosticsReport.TripSummary(TRIP, TripPhase.RUNNING, ALPHA, BETA,
						UUID.fromString("00000000-0000-0000-0000-000000000002"), CART,
						9, "comp-1", 42, new BlockLocation(1, 64, 2), new BlockLocation(30, 64, 2),
						null, Optional.of(new DiagnosticsReport.TicketStats(4, 2, 1, 5)))),
				4,
				List.of(new DiagnosticsReport.QuarantineSummary("rail:alpha:1,64,2", TRIP.value(), CART,
						"cart removed")),
				List.of(scan),
				new DiagnosticsReport.TicketSummary(2, 128, 1, 1, 1, 2, 1, 3, 2, 4, 3),
				List.of(alpha, beta), Optional.of(alpha));
	}

	private static void assertContains(List<String> lines, String expected) {
		assertTrue(lines.contains(expected), "missing line: " + expected + "\nactual:\n"
				+ String.join("\n", lines));
	}
}
