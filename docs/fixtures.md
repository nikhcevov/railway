# Dev-only acceptance fixtures

`/railfixture` generates reproducible, real-world acceptance worlds for cases
that GameTests cannot model: chunks that must be unloaded at restart, a route
longer than the simulation distance, and a real `kill -9` mid-trip.

This is a **development tool**, not a production command and not a replacement
for unit tests or GameTests.

## Gate and removal before release

`FixtureEnvironment` enables the surface only when Fabric reports a development
environment, or when the JVM runs with:

```bash
-Drailway.fixtures=true
```

`RailwayMod` registers the command and its tick driver inside that gate. A
production jar never registers `/railfixture`. The intended release cleanup is:

1. delete `src/main/java/dev/nikhcevov/railway/fixture/`;
2. delete `src/test/java/dev/nikhcevov/railway/fixture/`;
3. delete the single `FixtureEnvironment` block in `RailwayMod`;
4. delete the `fixtureServer` run config, the build-info resource, `scripts/`,
   and this page.

The fixture reports are still written by the development command only.

## Build and run

Normal development server (existing `run/` world):

```bash
./gradlew runServer
```

Then run the fixture commands from the server console, RCON, or an operator
client:

```text
/railfixture list
/railfixture build long-route-unloaded
/railfixture verify long-route-unloaded
/railfixture report long-route-unloaded
/railfixture tp long-route-unloaded home
/railfixture tp long-route-unloaded mine
```

`build`, `verify`, `report` and `list` work from a server console or RCON.
`tp` is player-only. `verify` is asynchronous because chunk tickets need server
ticks; it writes the report when it finishes.

Dedicated fixture server (needed for the restart/unload scenario):

```bash
./gradlew runFixtureServer
```

That run configuration uses `run/fixture-kill9/` and port 25567. Use a small
`simulation-distance` (4 in the harness) and no player standing at the far end
when verifying.

## Scenario `long-route-unloaded`

The fixture owns a fixed absolute area in the overworld. Running `build`
overwrites that area and the fixture's own station/binding/marker state; it
does not touch unrelated stations. The world seed is recorded in the report.

| Element | Home | Destination |
|---|---|---|
| Rail dock | `10000 65 0` | `10512 65 0` |
| Exit feet | `10000 65 3` | `10512 65 3` |
| Wall sign | `10000 67 1`, north | `10512 67 1`, north |
| Stone button | `10000 66 1`, north wall | `10512 66 1`, north wall |
| Sign support | `10000 67 2` | `10512 67 2` |
| Platform base | `10000 64 0` | `10512 64 0` |

The build lays a stone platform at `y=64` from `z=-1` through `z=4`, clears the
corridor above it, and places 513 `minecraft:rail` blocks (`EAST_WEST`) at
`y=65, z=0` from `x=10000` through `x=10512`. Each station gets a stone pillar,
a wall sign with the `[Railway]` marker and both station names, a stone button,
and a safe exit pad. The two destinations are 512 blocks apart and 32 chunk
columns apart; home is chunk `(625, 0)`, destination `(657, 0)`.

State is created through the normal `WorldState` models:

- two anchored, enabled `Station` records with exact docks, exits and yaw 0;
- two `Binding` records with `sourceDock` equal to the local station dock;
- two `SignMarker` records owning the physical signs and buttons;
- `railway.saveState()`, followed by a server world flush
  (`saveEverything`) so the chunks are durable before a kill.

Expected values in the report: 2 stations, 2 bindings, 513 rails, route length
512 in both directions, one shared graph component.

### Why the destination must be unloaded

After a restart the destination chunks are unloaded only when nothing keeps
them loaded:

- `simulation-distance=4` (or another small value);
- no player near `x=10500`;
- spawn chunks are far away (the fixture is at `x≈10000`);
- no other ticket holder.

`verify` first invalidates any graph left by a startup scan, then waits up to
1,200 ticks for the destination dock chunk to unload. If it cannot, it writes a
failure telling you to reduce simulation distance or move players away. Once
the chunk is unloaded it launches one real `ScanService` job from the home dock,
waits for it to finish, and plans and validates both route directions. The
report records `destinationLoadedBeforeScan=false` and
`destinationRailScanned=true`.

## Reports

Reports are deterministic JSON without timestamps or absolute paths:

```text
<server game dir>/fixtures/long-route-unloaded-build.json
<server game dir>/fixtures/long-route-unloaded-verify.json
```

Override the directory with:

```bash
-Drailway.fixtures.dir=/absolute/path
```

A relative value is resolved under the server game directory. The harness uses
`run/fixture-kill9/fixtures/`.

The report contains:

- scenario id, kind (`build`/`verify`), Railway version and git commit,
  Minecraft/Fabric versions, dimension, level seed, and origin;
- expected and actual stations/bindings/rails/component/route lengths;
- `destinationLoadedBeforeScan`, `destinationLoadedAfterScan`, and
  `destinationRailScanned`;
- scan job id/status, rails, switches, pending chunks, covered stations and
  failure;
- recovery checkpoints, active trips, quarantine, loaded managed carts, and
  recovery pending/blocking counts;
- an `ok` flag and an ordered `failures` array.

The ordering and values are stable for the same world/versions. Version and
commit metadata naturally change with a rebuild.

## `kill -9` harness

```bash
./scripts/fixture-kill9.sh
```

Optional environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `FIXTURE_PORT` | `25567` | Dedicated server port |
| `FIXTURE_RCON_PORT` | `25568` | RCON port |
| `FIXTURE_RCON_PASSWORD` | `fixture` | RCON password |
| `FIXTURE_TRIP_TIMEOUT` | `1800` | Seconds to wait for the manual trip |
| `FIXTURE_RESET` | `1` | Delete the dedicated world first; set `0` to keep it |
| `FIXTURE_KEEP_RUNNING` | `0` | Leave the fixture server running after the harness exits |

The script:

1. prepares `run/fixture-kill9/` with a fresh flat world, EULA, Railway config
   and RCON;
2. starts `./gradlew runFixtureServer` (the normal `run/` dev server is not
   touched);
3. runs `/railfixture build long-route-unloaded` through RCON and validates the
   build report;
4. prints the manual client step and waits for a persisted `RUNNING` checkpoint
   whose cart has left the home dock;
5. sends `kill -9` to the fixture Java server process;
6. restarts the same world;
7. runs `/railfixture verify long-route-unloaded`, waits for the report, and
   asserts unload-before-scan, auto-loaded scan, 513 rails, 512-block route,
   no trip resume, closed checkpoints, no quarantine, and no managed carts.

The only manual step is the real client pressing the home button. The button is
on the rail-facing side of the pillar (`10000 66 1`): teleport to the exit pad
(`10000 65 3`), walk around the pillar to `z=0`/`z=-1`, and right-click the
button while staying within the boarding radius of the dock. The script cannot
and does not start a trip itself, and no production trip-start shortcut was
added.

Logs are written under `run/fixture-kill9/logs/`.

## Wide-frontier scenario

`wide-frontier` is listed by `/railfixture list` but not built. The exact
blocker is geometry, not omitted coverage:

- `GraphScanner` discovers rails by one-block adjacency (four horizontal
  directions at three heights). With only the station's region loaded, a real
  straight or branching line exposes only the chunks immediately around its
  loaded rail tips; a straight run exposes one new chunk per scan step.
- To have more than 32 distinct **unloaded** frontier chunks, the fixture would
  have to keep a wide hub region loaded with persistent force-load tickets or a
  player, then seed a rail stub into each of >32 surrounding chunks. Those
  stubs would be dead ends rather than a route, and the state would no longer
  model a real component's unloaded frontier.
- That is exactly the kind of synthetic frontier the requirement forbids, so
  the fixture does not fake it.

Existing coverage stays in place: the GameTest
`scanFrontierOverTheCapIsProcessedInBoundedSubsets` exercises a 40-chunk
frontier, and `ScanTicketSelectionTest` covers cap, ordering, held/loaded
priority, zero budget and successive subsets.

## World safety and reproducibility

- **Use a copy.** Do not run the fixture on a world you care about. The harness
  uses its own `run/fixture-kill9/` world. For a manual run, copy the world or
  start a fresh dedicated server first.
- Back up `run/fixture-kill9/world/` before changing `FIXTURE_RESET=0` across
  experiments. Set `FIXTURE_RESET=1` to start from a fresh world.
- `build` is deterministic for the reserved area, but the surrounding terrain
  depends on the world seed; the report records the seed and versions.
- `build` may generate the 33 route chunks. A scan still never generates
  chunks; it only uses stored chunks and tickets. No production scan/trip
  behavior was changed.

## Reproducing a failure

1. Delete stale reports (`run/fixtures/` or `run/fixture-kill9/fixtures/`).
2. For the restart scenario, delete the dedicated world or set
   `FIXTURE_RESET=1`.
3. Run `/railfixture build long-route-unloaded`, then stop and restart the
   server without a player at `x≈10500`.
4. Run `/railfixture verify long-route-unloaded` and read the failures array.
5. For a full crash run, use `scripts/fixture-kill9.sh` and inspect
   `run/fixture-kill9/logs/`.

## Limitations

- The fixture verifies geometry, state, graph, route and recovery; it does not
  drive a real client or render UI. The client button press remains manual.
- `verify` waits for recovery and refuses to pass while a checkpoint, active
  trip, quarantine entry or loaded managed cart remains.
- `managedCarts` counts loaded entities. Background recovery is expected to
  remove the killed cart; an entity in an unloaded chunk cannot be observed
  without loading that chunk.
- A normal developer server with unrelated active trips or pending checkpoints
  is not a valid fixture target; use the dedicated server.
- GameTests and unit tests remain the first line of defense. This fixture
  complements them.
