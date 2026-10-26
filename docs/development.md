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

The jar is written to `build/libs/railway-0.1.0-SNAPSHOT.jar`; sources are also
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
| `mixin` | sign-save and managed-cart hooks |

No custom blocks, items, or entities are registered. Stations are vanilla rails,
signs, and buttons; service carts are ordinary minecarts with Railway tags.

## Runtime design rules

- All world/state access is on the server thread.
- Scans are tick-batched; a scan does not generate chunks.
- Routes and graphs are derived caches; state holds stations, bindings, markers,
  and checkpoints, not full route plans.
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
- recovery decisions and diagnostic summaries.

### GameTests (`src/gametest/java`)

Server integration tests using Fabric GameTest and a real Minecraft server:

- straight, curve/slope, turnout, parallel, speed, boarding/dismount, broken path;
- sign bootstrap, chain/local/main, ordinary signs, permissions, break lifecycle;
- chunk ticket lifecycle/readiness, recovery present/absent/occupied carts, late
  chunk cleanup;
- diagnostics.

GameTests should exercise the same hooks as production (for example
`SignBlockEntity.updateSignText`), not only call service methods directly.

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

- no true long-route-beyond-simulation-distance run yet;
- no true mid-trip `kill -9` run with a real client;
- vanilla ticket propagation may load an adjacent missing chunk;
- diagnostics output is unpaginated;
- no client-side UI or resource pack.
