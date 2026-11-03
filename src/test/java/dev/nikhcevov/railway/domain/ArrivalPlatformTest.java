package dev.nikhcevov.railway.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalPlatformTest {
	private static final String DIMENSION = "minecraft:overworld";

	private static ArrivalPlatform platform(String source, int x, float yaw) {
		return new ArrivalPlatform(new StationId(source), new BlockLocation(x, 64, 0),
				new BlockLocation(x + 1, 64, 0), yaw);
	}

	private static Station station(String id) {
		return new Station(new StationId(id), "Station " + id, DIMENSION,
				new BlockLocation(0, 64, 0), new BlockLocation(1, 64, 0), 90.0F, true, 1);
	}

	private static Station withPlatforms(List<ArrivalPlatform> platforms) {
		return new Station(new StationId("home"), "Home", DIMENSION,
				new BlockLocation(0, 64, 0), new BlockLocation(1, 64, 0), 90.0F, true, 1, platforms);
	}

	@Test
	void platformRequiresSourceDockAndExit() {
		assertThrows(NullPointerException.class, () -> new ArrivalPlatform(null,
				new BlockLocation(0, 64, 0), new BlockLocation(1, 64, 0), 0.0F));
		assertThrows(NullPointerException.class, () -> new ArrivalPlatform(new StationId("alpha"),
				null, new BlockLocation(1, 64, 0), 0.0F));
		assertThrows(NullPointerException.class, () -> new ArrivalPlatform(new StationId("alpha"),
				new BlockLocation(0, 64, 0), null, 0.0F));

		ArrivalPlatform platform = platform("alpha", 10, 45.0F);

		assertEquals(new StationId("alpha"), platform.source());
		assertEquals(new BlockLocation(10, 64, 0), platform.dock());
		assertEquals(new BlockLocation(11, 64, 0), platform.exit());
		assertEquals(45.0F, platform.yaw());
	}

	@Test
	void stationWithoutPlatformsDefaultsToEmptyList() {
		Station home = station("home");

		assertTrue(home.arrivalPlatforms().isEmpty());
		assertEquals(Optional.empty(), home.arrivalFrom(new StationId("alpha")));
		assertEquals(1, home.revision(), "the compatibility constructor keeps the given revision");
	}

	@Test
	void stationRejectsNullDuplicateAndProvisionalPlatforms() {
		List<ArrivalPlatform> withNull = new ArrayList<>();
		withNull.add(null);
		assertThrows(IllegalArgumentException.class, () -> withPlatforms(withNull));

		assertThrows(IllegalArgumentException.class,
				() -> withPlatforms(List.of(platform("alpha", 10, 0.0F), platform("alpha", 20, 0.0F))));

		assertThrows(IllegalArgumentException.class, () -> new Station(new StationId("mine"), "Mine",
				DIMENSION, null, null, 0.0F, false, 1, List.of(platform("alpha", 10, 0.0F))));
	}

	@Test
	void stationCopiesThePlatformList() {
		List<ArrivalPlatform> platforms = new ArrayList<>();
		platforms.add(platform("alpha", 10, 0.0F));

		Station home = withPlatforms(platforms);
		platforms.add(platform("beta", 20, 0.0F));

		assertEquals(1, home.arrivalPlatforms().size());
		assertThrows(UnsupportedOperationException.class,
				() -> home.arrivalPlatforms().add(platform("beta", 20, 0.0F)));
	}

	@Test
	void withMethodsPreserveArrivalPlatforms() {
		Station home = station("home").withArrivalPlatform(platform("alpha", 10, 45.0F), 2);

		assertEquals(1, home.withEnabled(false, 3).arrivalPlatforms().size());
		assertEquals(1, home.withDisplayName("Renamed", 4).arrivalPlatforms().size());
		assertEquals(1, home.withExit(new BlockLocation(2, 64, 0), 180.0F, 5).arrivalPlatforms().size());
		assertEquals(1,
				home.withMain(new BlockLocation(3, 64, 0), new BlockLocation(4, 64, 0), 0.0F, 6)
						.arrivalPlatforms().size());
		assertEquals(1,
				home.withMain(new BlockLocation(5, 64, 0), new BlockLocation(6, 64, 0), 0.0F)
						.arrivalPlatforms().size());
	}

	@Test
	void platformsAreReplacedBySourceAndRemoved() {
		ArrivalPlatform alpha = platform("alpha", 10, 45.0F);
		ArrivalPlatform beta = platform("beta", 20, 90.0F);
		Station home = station("home").withArrivalPlatform(alpha, 2).withArrivalPlatform(beta, 3);

		assertEquals(List.of(alpha, beta), home.arrivalPlatforms());
		assertEquals(alpha, home.arrivalFrom(new StationId("alpha")).orElseThrow());
		assertEquals(3, home.revision());

		ArrivalPlatform moved = platform("alpha", 30, 10.0F);
		Station replaced = home.withArrivalPlatform(moved, 4);

		assertEquals(2, replaced.arrivalPlatforms().size());
		assertEquals(List.of(moved, beta), replaced.arrivalPlatforms(),
				"a replacement keeps the platform order");
		assertEquals(4, replaced.revision());

		Station removed = replaced.withoutArrivalPlatform(new StationId("alpha"), 5);

		assertEquals(List.of(beta), removed.arrivalPlatforms());
		assertEquals(Optional.empty(), removed.arrivalFrom(new StationId("alpha")));
		assertEquals(5, removed.revision());

		Station absent = removed.withoutArrivalPlatform(new StationId("gamma"), 6);
		assertEquals(List.of(beta), absent.arrivalPlatforms(), "removing an unknown source is a no-op");
		assertEquals(6, absent.revision());
	}

	@Test
	void provisionalStationCannotGainArrivalPlatforms() {
		Station mine = Station.provisional(new StationId("mine"), "Mine", DIMENSION, 1);

		assertThrows(IllegalStateException.class,
				() -> mine.withArrivalPlatform(platform("alpha", 10, 0.0F), 2));
		assertTrue(mine.withoutArrivalPlatform(new StationId("alpha"), 2).arrivalPlatforms().isEmpty());
	}
}
