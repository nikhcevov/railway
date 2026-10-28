package dev.nikhcevov.railway.fixture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic acceptance report. It contains no timestamps or absolute paths,
 * so two runs with the same world and versions produce byte-identical JSON.
 */
public record FixtureReport(
		String scenario,
		String kind,
		Metadata metadata,
		String dimension,
		long levelSeed,
		BlockLocation origin,
		Expected expected,
		Actual actual,
		Scan scan,
		Recovery recovery,
		boolean ok,
		List<String> failures
) {
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.serializeNulls()
			.create();

	public FixtureReport {
		Objects.requireNonNull(scenario, "scenario");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(metadata, "metadata");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(actual, "actual");
		failures = failures == null ? List.of() : List.copyOf(failures);
	}

	public record Metadata(
			String railwayVersion,
			String railwayCommit,
			String minecraftVersion,
			String fabricLoaderVersion,
			String fabricApiVersion
	) {
	}

	public record Expected(
			int rails,
			int stations,
			int bindings,
			int routeLength,
			String component
	) {
	}

	public record Actual(
			int rails,
			int stations,
			int bindings,
			String component,
			int routeLength,
			int reverseRouteLength,
			boolean destinationLoadedBeforeScan,
			boolean destinationLoadedAfterScan,
			boolean destinationRailScanned
	) {
	}

	public record Scan(
			String jobId,
			String station,
			boolean running,
			boolean waiting,
			int pendingChunks,
			int rails,
			int switches,
			boolean complete,
			String failure,
			String coveredBy,
			List<String> covered
	) {
		public Scan {
			covered = covered == null ? List.of() : List.copyOf(covered);
		}
	}

	public record Recovery(
			int checkpoints,
			int activeTrips,
			int quarantine,
			int managedCarts,
			int recoveryPending,
			int recoveryBlocking
	) {
	}

	public String fileName() {
		return scenario + "-" + kind + ".json";
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static Scan scan(String jobId, String station, boolean running, boolean waiting,
			int pendingChunks, int rails, int switches, boolean complete, Failure failure,
			String coveredBy, List<String> covered) {
		return new Scan(jobId, station, running, waiting, pendingChunks, rails, switches, complete,
				failure == null ? null : failureText(failure), coveredBy, covered);
	}

	public static String failureText(Failure failure) {
		StringBuilder text = new StringBuilder(failure.code().name());
		if (failure.location() != null) {
			text.append(" at ").append(failure.location().shortString());
		}
		if (failure.detail() != null) {
			text.append(": ").append(failure.detail());
		}
		return text.toString();
	}
}
