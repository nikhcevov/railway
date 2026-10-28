package dev.nikhcevov.railway.fixture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.nikhcevov.railway.domain.BlockLocation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureReportTest {
	private static FixtureReport report(List<String> failures) {
		return new FixtureReport(
				LongRouteScenario.SCENARIO,
				"verify",
				new FixtureReport.Metadata("0.1.0-SNAPSHOT", "abc123", "26.1.2", "0.20.1",
						"0.155.3+26.1.2"),
				"minecraft:overworld",
				12345L,
				new BlockLocation(10_000, 64, 0),
				new FixtureReport.Expected(513, 2, 2, 512, "one shared component"),
				new FixtureReport.Actual(513, 2, 2, "minecraft:overworld|513|...", 512, 512,
						false, true, true),
				new FixtureReport.Scan("scan-1", LongRouteScenario.HOME_ID, false, false, 0,
						513, 0, true, null, null, List.of(LongRouteScenario.MINE_ID)),
				new FixtureReport.Recovery(0, 0, 0, 0, 0, 0),
				failures.isEmpty(),
				failures);
	}

	@Test
	void jsonIsDeterministicAndContainsAcceptanceFields() {
		FixtureReport report = report(List.of());
		String first = report.toJson();
		String second = report.toJson();

		assertEquals(first, second);
		JsonObject root = JsonParser.parseString(first).getAsJsonObject();
		assertEquals("long-route-unloaded", root.get("scenario").getAsString());
		assertEquals("verify", root.get("kind").getAsString());
		assertTrue(root.get("ok").getAsBoolean());
		assertEquals("abc123", root.getAsJsonObject("metadata").get("railwayCommit").getAsString());
		assertEquals(10_000, root.getAsJsonObject("origin").get("x").getAsInt());
		assertEquals(513, root.getAsJsonObject("expected").get("rails").getAsInt());
		assertEquals(512, root.getAsJsonObject("expected").get("routeLength").getAsInt());
		assertEquals(513, root.getAsJsonObject("actual").get("rails").getAsInt());
		assertFalse(root.getAsJsonObject("actual").get("destinationLoadedBeforeScan").getAsBoolean());
		assertTrue(root.getAsJsonObject("actual").get("destinationRailScanned").getAsBoolean());
		assertEquals("scan-1", root.getAsJsonObject("scan").get("jobId").getAsString());
		assertTrue(root.getAsJsonObject("scan").has("failure"));
		assertTrue(root.getAsJsonObject("scan").get("failure").isJsonNull());
		assertEquals(0, root.getAsJsonObject("recovery").get("checkpoints").getAsInt());
	}

	@Test
	void failuresMakeTheReportFailAndKeepTheirOrder() {
		FixtureReport report = report(List.of("first failure", "second failure"));
		JsonObject root = JsonParser.parseString(report.toJson()).getAsJsonObject();

		assertFalse(root.get("ok").getAsBoolean());
		assertEquals("first failure", root.getAsJsonArray("failures").get(0).getAsString());
		assertEquals("second failure", root.getAsJsonArray("failures").get(1).getAsString());
	}

	@Test
	void reportFileNameIsStable() {
		assertEquals("long-route-unloaded-verify.json", report(List.of()).fileName());
	}
}
