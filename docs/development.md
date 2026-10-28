# Development and testing

## Toolchain

Pinned by `gradle.properties`:

| Component | Version |
|---|---|
| Minecraft Java Edition | 26.1.2 (unobfuscated) |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.155.3+26.1.2 |
| Fabric Loom | 1.19.3 |
| Gradle | 9.8.2 |
| Java release target | 25 |

The mod is server-only (`fabric.mod.json` environment `server`). Minecraft 26.1.2
is unobfuscated here, so class names are the Mojang names and no Yarn/Mojmap
mapping dependency is used.

## Build and test

```bash
./gradlew build                 # compile + unit tests + jar
./gradlew test                  # unit tests only
./gradlew runGameTest           # Fabric server GameTests
./gradlew runServer             # dev dedicated server
./gradlew runClient             # dev client task (not required by the mod)
```

The jar is written to `build/libs/railway-0.1.0-alpha.5.jar`; sources are also
built.

Useful test commands:

```bash
./gradlew test --rerun-tasks
./gradlew runGameTest --console=plain
```

GameTests run against one shared server and one shared `TripService`, so tests
that start trips must respect `maxActiveTrips` and `RECOVERY_PENDING`. Test
helpers retry only on those transient global limits; all other failures fail the
test. Never treat `TRIP_LIMIT` as success.

The dev `run/` directory and GameTest worlds are ignored by Git. The current dev
stack includes production server mods such as C2ME, Lithium, Krypton, FerriteCore,
and Clumps; verify behavior under any other combination.

## Source layout

| Package | Responsibility |
|---|---|
| `domain` | Station/binding/marker/trip IDs and value types, failure codes |
| `network` | rail scanning, directed graph, route planning, turnout state |
| `motion` | speed/acceleration/braking profile |
| `dispatch` | reservations and route resource derivation |
| `persistence` | config, state schema/migrations, trip checkpoints |
| `recovery` | pure recovery decisions |
| `minecraft` | Fabric callbacks/adapters, scanning, trips, signs, tickets, recovery |
| `sign` | pure sign parsing, pillar/dock geometry, validation |
| `commands` | Brigadier surface and message catalogs |
| `diagnostics` | read-only operations report |
| `fixture` | dev-only `/railfixture` generator, verifier, and reports |
| `mixin` | sign-save and managed-cart hooks |

No custom blocks, items, or entities are registered. Stations are vanilla rails,
signs, and buttons; service carts are ordinary minecarts with Railway tags.

## Runtime design rules

- All world/state access is on the server thread.
- Scans are tick-batched; a scan does not generate chunks.
- Routes and graphs are derived caches; state holds stations, bindings, markers,
  and checkpoints, not full route plans. Graphs are rebuilt without a command:
  `Railway.startScansFor` queues anchored stations and `ScanService` starts them
  one at a time, while `ScanService.autoStart` rebuilds a missing graph from a
  trip launch or route check. A successful scan aliases every anchored station on
  the same component, so graph lookup, route checks and diagnostics reuse one
  graph. Aliases are invalidated with their owner (station or block
  invalidation), and a manual scan of a covered station clears its alias. Both
  startup and on-demand paths use the same per-station dedup and
  `ScanRetryCooldown` (200 ticks after a failure; cleared on success and by a
  manual scan/invalidation).
- Reservations are all-or-nothing; no waiting queue.
- Service carts are tagged `railway_managed` and trip-tagged. Never treat an
  arbitrary cart as managed.
- Active trips are not resumed after restart. Recovery reconciles checkpoints and
  service carts instead.
- The sign mixin captures the previous `SignText`, applies registration after
  vanilla saves, and restores the old text on rejection.
- The speed mixin is gated to Railway-managed carts.

## State and config

- `config/railway.json`: validated config; invalid files are never overwritten.
- `<world>/railway/state.json`: schema 4; migrations create `state.vN.bak`.
- `/rail reload` affects new trips; active trips use their snapshot.
- Schema changes require a migration test and backup behavior.

## Test layers

### Unit tests (`src/test/java`)

Pure domain, geometry, graph, routing, reservation, motion, config, persistence,
recovery policy, diagnostics rendering, and migration tests. They must not require
a running Minecraft server.

Add unit tests for:

- parser/geometry boundaries and rejected ambiguous cases;
- route planning and resource derivation;
- reservation/quarantine semantics;
- config validation and migration;
- recovery decisions and diagnostic summaries;
- scan retry cooldown boundaries and per-station isolation;
- scan frontier ticket selection: cap, held/loaded priority, determinism, zero
  budget, missing chunks, and that successive subsets cover the whole frontier.

### GameTests (`src/gametest/java`)

Server integration tests using Fabric GameTest and a real Minecraft server:

- straight, curve/slope, turnout, parallel, speed, boarding/dismount, broken path;
- sign bootstrap, chain/local/main, ordinary signs, permissions, break lifecycle;
- chunk ticket lifecycle/readiness, bounded scan-frontier subsets across more
  than 32 chunks, recovery present/absent/occupied carts, late chunk cleanup;
- automatic startup and on-demand scans (missing graph starts a scan and the
  retry succeeds after completion; provisional stations are skipped);
- diagnostics.

GameTests should exercise the same hooks as production (for example
`SignBlockEntity.updateSignText`), not only call service methods directly.

### Dev-only acceptance fixtures

`/railfixture` builds real worlds for cases the GameTest plot cannot model:
unloaded destination chunks, a 512-block route, restart unload, and `kill -9`
mid-trip. It is gated by `FabricLoader.isDevelopmentEnvironment()` /
`-Drailway.fixtures=true`, writes deterministic JSON under `fixtures/`, and is
deleted before a release. See [`fixtures.md`](fixtures.md).

```bash
./gradlew runFixtureServer          # dedicated run/fixture-kill9 server
./scripts/fixture-kill9.sh          # build, manual trip, kill -9, restart, verify
```

## Adding a command or message

1. Register the command in `RailCommand` with the appropriate permission.
2. Add message keys to both `en_us.json` and `ru_ru.json`; key sets must match.
3. Use structured `Failure`/`FailureCode` values for failures.
4. Add unit coverage for message formatting and GameTest/integration coverage for
   state transitions.
5. Update `docs/commands.md` and the README command summary.

## Adding persistent state

1. Bump `JsonStateStore.SCHEMA_VERSION`.
2. Add migration behavior for every older schema;
   preserve old data and create the rolling backup.
3. Add validation invariants and a schema round-trip test.
4. Update recovery/diagnostics if the new state affects them.
5. Update `docs/configuration.md` / `docs/operations.md`.

## Known limitations

See [`stage-5-report.md`](stage-5-report.md) and
[`operations.md`](operations.md). Notably:

- the dev-only `long-route-unloaded` fixture and `kill -9` harness now exist,
  but a real-client pass still has to be performed manually;
- `wide-frontier` is not generated because a real rail graph cannot expose
  more than 32 unloaded frontier chunks without a force-loaded hub and dead-end
  stubs; selector coverage remains in GameTests/unit tests;
- vanilla ticket propagation may load an adjacent missing chunk;
- diagnostics output is unpaginated;
- no client-side UI or resource pack.
