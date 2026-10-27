package dev.nikhcevov.railway.diagnostics;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.minecraft.ChunkTicketManager;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.RecoveryService;
import dev.nikhcevov.railway.minecraft.ScanService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects a read-only {@link DiagnosticsReport} from the live Railway services.
 * Every accessor used here is a query; this class holds no mutable state and
 * never triggers scans, recovery, reservations or ticket changes.
 */
public final class DiagnosticsService {
	private final Railway railway;

	public DiagnosticsService(Railway railway) {
		this.railway = railway;
	}

	/** Global report with every station summary and no focused station. */
	public DiagnosticsReport snapshot() {
		return snapshot(null);
	}

	public DiagnosticsReport snapshot(StationId focus) {
		RecoveryService.Stats recovery = railway.recovery().stats();
		var ticketRecovery = railway.tickets().recoveryStats();
		List<DiagnosticsReport.TripSummary> trips = railway.trips().activeSummaries();
		int pendingTickets = 0;
		for (DiagnosticsReport.TripSummary trip : trips) {
			if (trip.ticketStats().isPresent()) {
				pendingTickets += trip.ticketStats().get().pendingChunks();
			}
		}

		List<DiagnosticsReport.ScanSummary> scans = new ArrayList<>();
		for (ScanService.Status status : railway.scans().all()) {
			scans.add(scan(status));
		}

		List<DiagnosticsReport.StationSummary> stations = new ArrayList<>();
		for (Station station : railway.state().stations()) {
			stations.add(station(station));
		}
		Optional<DiagnosticsReport.StationSummary> focused = focus == null
				? Optional.empty()
				: stations.stream().filter(station -> station.id().equals(focus)).findFirst();

		DiagnosticsReport.Global global = new DiagnosticsReport.Global(
				!railway.disabled(),
				railway.disabledReason(),
				railway.stateLoaded(),
				railway.state().revision(),
				railway.state().trips().size(),
				new DiagnosticsReport.Limits(
						railway.config().maxActiveTrips(),
						railway.config().maxManagedChunkTickets(),
						railway.config().maxRouteLength(),
						railway.config().scanRailsPerTick(),
						railway.config().scanTimeBudgetMs(),
						railway.config().maxRailsPerScan()),
				new DiagnosticsReport.RecoverySummary(
						recovery.pending(), recovery.blocking(), recovery.tickets(),
						recovery.ticketAcquisitions(), recovery.ticketReleases(),
						recovery.checkpointsRemoved(), recovery.cartsDiscarded(),
						recovery.cartsStopped(), recovery.cartsPreserved(), recovery.chunkProbes(),
						recovery.lateChunkPasses(), recovery.deadlines()));

		List<DiagnosticsReport.QuarantineSummary> quarantine = new ArrayList<>();
		for (var entry : railway.trips().quarantinedResources()) {
			quarantine.add(new DiagnosticsReport.QuarantineSummary(entry.resource(),
					entry.quarantine().tripId(), entry.quarantine().cartUuid(),
					entry.quarantine().reason()));
		}

		ChunkTicketManager.ScanStats ticketScans = railway.tickets().scanStats();
		DiagnosticsReport.TicketSummary ticketSummary = new DiagnosticsReport.TicketSummary(
				railway.tickets().ticketCount(),
				railway.config().maxManagedChunkTickets(),
				pendingTickets,
				ticketRecovery.checkpoints(),
				ticketRecovery.tickets(),
				ticketRecovery.acquisitions(),
				ticketRecovery.releases(),
				ticketScans.jobs(),
				ticketScans.tickets(),
				ticketScans.acquisitions(),
				ticketScans.releases());

		return new DiagnosticsReport(global, trips,
				railway.trips().ownedReservationCount(), quarantine, scans, ticketSummary,
				stations, focused);
	}

	/** Convenience for the command layer: collect and render in one read-only pass. */
	public List<String> render(Messages.Lang lang, StationId focus) {
		return snapshot(focus).render(lang);
	}

	private DiagnosticsReport.StationSummary station(Station station) {
		List<DiagnosticsReport.BindingSummary> bindings = new ArrayList<>();
		for (Binding binding : railway.state().bindingsFor(station.id())) {
			bindings.add(new DiagnosticsReport.BindingSummary(binding.fromStationId(),
					binding.toStationId(), binding.button(), binding.sourceDock()));
		}
		ScanService.Status status = railway.scans().status(station.id()).orElse(null);
		return new DiagnosticsReport.StationSummary(
				station.id(), station.displayName(), station.dimension(), station.enabled(),
				station.revision(), station.isProvisional(), station.dock(), station.exit(),
				railway.state().signUnitCount(station.id()),
				railway.state().mainMarkerForStation(station.id()).orElse(null),
				bindings, status == null ? null : scan(status));
	}

	private static DiagnosticsReport.ScanSummary scan(ScanService.Status status) {
		DiagnosticsReport.GraphSummary graph = status.graph() == null
				? null
				: new DiagnosticsReport.GraphSummary(status.graph().revision(),
						status.graph().componentKey(), status.graph().rails().size(),
						status.graph().switches().size());
		return new DiagnosticsReport.ScanSummary(status.jobId(), status.station(), status.rails(),
				status.switches(), status.running(), status.failure(), status.revision(), graph);
	}
}
