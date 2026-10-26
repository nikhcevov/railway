package dev.nikhcevov.railway.diagnostics;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Read-only diagnostic snapshot and its plain-text rendering.
 *
 * <p>The renderer deliberately depends only on domain types and the message
 * catalog so it can be unit tested without a running server. Diagnostic calls
 * never mutate trip, reservation, ticket or recovery state.
 */
public record DiagnosticsReport(
		Global global,
		List<TripSummary> trips,
		int ownedReservations,
		List<QuarantineSummary> quarantine,
		List<ScanSummary> scans,
		TicketSummary tickets,
		List<StationSummary> stations,
		Optional<StationSummary> focused
) {
	public DiagnosticsReport {
		Objects.requireNonNull(global, "global");
		trips = List.copyOf(trips);
		quarantine = List.copyOf(quarantine);
		scans = List.copyOf(scans);
		Objects.requireNonNull(tickets, "tickets");
		stations = List.copyOf(stations);
		focused = focused == null ? Optional.empty() : focused;
	}

	public record Global(
			boolean enabled,
			String disabledReason,
			boolean stateLoaded,
			long revision,
			int checkpoints,
			Limits limits,
			RecoverySummary recovery
	) {
	}

	public record Limits(
			int maxActiveTrips,
			int maxManagedChunkTickets,
			int maxRouteLength,
			int scanRailsPerTick,
			int scanTimeBudgetMs,
			int maxRailsPerScan
	) {
	}

	public record RecoverySummary(
			int pending,
			int blocking,
			int tickets,
			long ticketAcquisitions,
			long ticketReleases,
			long checkpointsRemoved,
			long cartsDiscarded,
			long cartsStopped,
			long cartsPreserved,
			long chunkProbes,
			long lateChunkPasses,
			long deadlines
	) {
	}

	public record TripSummary(
			TripId id,
			TripPhase phase,
			StationId from,
			StationId to,
			UUID playerUuid,
			UUID cartUuid,
			long graphRevision,
			String component,
			int length,
			BlockLocation fromDock,
			BlockLocation toDock,
			String reason,
			Optional<TicketStats> ticketStats
	) {
		public TripSummary {
			ticketStats = ticketStats == null ? Optional.empty() : ticketStats;
		}
	}

	public record TicketStats(
			int windowChunks,
			int tickets,
			int pendingChunks,
			long readinessChecks
	) {
	}

	public record QuarantineSummary(String resource, UUID tripId, UUID cartUuid, String reason) {
	}

	public record ScanSummary(
			String jobId,
			StationId station,
			int rails,
			int switches,
			boolean running,
			Failure failure,
			long revision,
			GraphSummary graph
	) {
	}

	public record GraphSummary(long revision, String component, int rails, int switches) {
	}

	public record TicketSummary(
			int held,
			int budget,
			int pending,
			int recoveryCheckpoints,
			int recoveryTickets,
			long recoveryAcquisitions,
			long recoveryReleases
	) {
	}

	public record StationSummary(
			StationId id,
			String displayName,
			String dimension,
			boolean enabled,
			long revision,
			boolean provisional,
			BlockLocation dock,
			BlockLocation exit,
			int units,
			SignMarker mainMarker,
			List<BindingSummary> bindings,
			ScanSummary scan
	) {
		public StationSummary {
			bindings = List.copyOf(bindings);
		}
	}

	public record BindingSummary(
			StationId from,
			StationId to,
			BlockLocation button,
			BlockLocation sourceDock
	) {
	}

	/** Renders the snapshot as compact chat lines; diagnostics is not a GUI. */
	public List<String> render(Messages.Lang lang) {
		List<String> lines = new ArrayList<>();
		renderGlobal(lines, lang);
		renderTrips(lines, lang);
		renderReservations(lines, lang);
		renderScans(lines, lang);
		renderTickets(lines, lang);
		renderStations(lines, lang);
		renderFocused(lines, lang);
		return List.copyOf(lines);
	}

	private void renderGlobal(List<String> lines, Messages.Lang lang) {
		Global global = this.global;
		add(lines, lang, "diagnostics.header");
		if (global.enabled()) {
			add(lines, lang, "diagnostics.status.enabled");
		} else {
			add(lines, lang, "diagnostics.status.disabled",
					global.disabledReason() == null ? "" : global.disabledReason());
		}
		add(lines, lang, "diagnostics.state",
				Messages.format(lang, global.stateLoaded()
						? "diagnostics.state.loaded"
						: "diagnostics.state.not_loaded"),
				global.revision(), global.checkpoints());

		Limits limits = global.limits();
		add(lines, lang, "diagnostics.limits", limits.maxActiveTrips(),
				limits.maxManagedChunkTickets(), limits.maxRouteLength(), limits.scanRailsPerTick(),
				limits.scanTimeBudgetMs(), limits.maxRailsPerScan());

		RecoverySummary recovery = global.recovery();
		add(lines, lang, "diagnostics.recovery", recovery.pending(), recovery.blocking(),
				recovery.tickets(), recovery.checkpointsRemoved(), recovery.cartsDiscarded(),
				recovery.cartsStopped(), recovery.cartsPreserved(), recovery.chunkProbes(),
				recovery.lateChunkPasses(), recovery.deadlines());
	}

	private void renderTrips(List<String> lines, Messages.Lang lang) {
		if (trips.isEmpty()) {
			add(lines, lang, "diagnostics.trips.none");
			return;
		}
		add(lines, lang, "diagnostics.trips.header", trips.size());
		for (TripSummary trip : trips) {
			add(lines, lang, "diagnostics.trip", trip.id(), trip.phase().name(),
					trip.from().value(), trip.to().value(), trip.playerUuid(), trip.cartUuid());
			if (trip.ticketStats().isPresent()) {
				TicketStats stats = trip.ticketStats().get();
				add(lines, lang, "diagnostics.trip.detail", trip.graphRevision(),
						trip.component(), trip.length(), trip.fromDock().shortString(),
						trip.toDock().shortString(), stats.tickets(), stats.windowChunks(),
						stats.pendingChunks(), stats.readinessChecks());
			} else {
				add(lines, lang, "diagnostics.trip.detail.no_tickets", trip.graphRevision(),
						trip.component(), trip.length(), trip.fromDock().shortString(),
						trip.toDock().shortString());
			}
			if (trip.reason() != null && !trip.reason().isBlank()) {
				add(lines, lang, "diagnostics.trip.reason", trip.reason());
			}
		}
	}

	private void renderReservations(List<String> lines, Messages.Lang lang) {
		add(lines, lang, "diagnostics.reservations", ownedReservations, quarantine.size());
		for (QuarantineSummary entry : quarantine) {
			add(lines, lang, "diagnostics.quarantine", entry.resource(), entry.reason(),
					entry.tripId(), entry.cartUuid());
		}
	}

	private void renderScans(List<String> lines, Messages.Lang lang) {
		if (scans.isEmpty()) {
			add(lines, lang, "diagnostics.scans.none");
			return;
		}
		add(lines, lang, "diagnostics.scans.header", scans.size());
		for (ScanSummary scan : scans) {
			add(lines, lang, scanText(lang, scan));
		}
	}

	private void renderTickets(List<String> lines, Messages.Lang lang) {
		add(lines, lang, "diagnostics.tickets", tickets.held(), tickets.budget(), tickets.pending(),
				tickets.recoveryCheckpoints(), tickets.recoveryTickets(),
				tickets.recoveryAcquisitions(), tickets.recoveryReleases());
	}

	private void renderStations(List<String> lines, Messages.Lang lang) {
		if (stations.isEmpty()) {
			add(lines, lang, "diagnostics.stations.none");
			return;
		}
		add(lines, lang, "diagnostics.stations.header", stations.size());
		for (StationSummary station : stations) {
			String state = Messages.format(lang, station.enabled() ? "state.enabled" : "state.disabled");
			if (station.provisional()) {
				add(lines, lang, "diagnostics.station.provisional", station.id().value(),
						station.displayName(), state, station.units());
			} else {
				add(lines, lang, "diagnostics.station", station.id().value(), station.displayName(),
						station.dimension(), state, station.revision(), station.dock().shortString(),
						exitText(lang, station), station.units());
			}
		}
	}

	private void renderFocused(List<String> lines, Messages.Lang lang) {
		if (focused.isEmpty()) {
			return;
		}
		StationSummary station = focused.get();
		add(lines, lang, "diagnostics.focus.header", station.id().value(), station.displayName(),
				station.dimension(), station.provisional() ? "provisional" : station.dock().shortString());
		add(lines, lang, "diagnostics.station.main", mainText(lang, station));
		if (station.bindings().isEmpty()) {
			add(lines, lang, "diagnostics.station.no_bindings");
		} else {
			StringJoiner joiner = new StringJoiner("; ");
			for (BindingSummary binding : station.bindings()) {
				String sourceDock = binding.sourceDock() == null
						? Messages.format(lang, "diagnostics.station.dock_main")
						: binding.sourceDock().shortString();
				joiner.add(Messages.format(lang, "diagnostics.station.binding", binding.from().value(),
						binding.to().value(), binding.button().shortString(), sourceDock));
			}
			add(lines, lang, "diagnostics.station.bindings", joiner.toString());
		}
		if (station.scan() == null) {
			add(lines, lang, "diagnostics.station.scan",
					Messages.format(lang, "diagnostics.station.scan_none"));
		} else {
			add(lines, lang, "diagnostics.station.scan", scanText(lang, station.scan()));
			if (station.scan().graph() != null) {
				GraphSummary graph = station.scan().graph();
				add(lines, lang, "diagnostics.station.graph", graph.revision(), graph.component(),
						graph.rails(), graph.switches());
			}
		}
	}

	private static String exitText(Messages.Lang lang, StationSummary station) {
		return station.exit() == null
				? Messages.format(lang, "diagnostics.station.exit_missing")
				: station.exit().shortString();
	}

	private static String mainText(Messages.Lang lang, StationSummary station) {
		if (station.mainMarker() != null) {
			return Messages.format(lang, "diagnostics.station.main.marker",
					station.mainMarker().sign().shortString());
		}
		if (station.dock() != null) {
			return Messages.format(lang, "diagnostics.station.main.dock",
					station.dock().shortString());
		}
		return Messages.format(lang, "diagnostics.station.main.none");
	}

	private static String scanText(Messages.Lang lang, ScanSummary scan) {
		if (scan.running()) {
			return Messages.format(lang, "diagnostics.scan.running", scan.jobId(),
					scan.station().value(), scan.rails(), scan.switches(), scan.revision());
		}
		if (scan.failure() != null) {
			return Messages.format(lang, "diagnostics.scan.failed", scan.jobId(),
					scan.station().value(), scan.rails(), scan.switches(), scan.revision(),
					Messages.failureText(lang, scan.failure()));
		}
		return Messages.format(lang, "diagnostics.scan.complete", scan.jobId(),
				scan.station().value(), scan.rails(), scan.switches(), scan.revision());
	}

	private static void add(List<String> lines, Messages.Lang lang, String key, Object... args) {
		lines.add(Messages.format(lang, key, args));
	}
}
