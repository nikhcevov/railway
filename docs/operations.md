# Operations and recovery

This page covers running a Railway server: diagnostics, chunk readiness, restart
recovery, failures, and removal.

## Diagnostics

```text
/rail diagnostics
/rail diagnostics <stationId>
```

OP level 2; works from console and players. It is read-only.

Global output:

- enabled/disabled state and reason;
- state loaded/revision and interrupted-checkpoint count;
- active trips: phase, from/to, player/cart UUIDs, graph revision/component/length,
  docks, ticket readiness, abort reason;
- reservation ownership and quarantined resources;
- scan jobs and graph status;
- managed chunk tickets: held/budget, pending, recovery tickets;
- recovery: pending/blocking, checkpoints removed, carts discarded/stopped/
  preserved, storage probes, late-chunk passes, deadlines.

Per-station output adds:

- display name, dimension, dock, exit, enabled, revision, provisional state;
- sign-unit count and current main marker (or main fallback);
- bindings and local source docks;
- scan/graph status.

Use this first for any route, ticket, recovery, or station problem.

## Chunk tickets and readiness

Railway keeps a **local** ticket window, not the whole route:

- current route step chunk plus the braking/safety window ahead;
- bounded by `maxManagedChunkTickets`; `0` disables tickets;
- acquired per trip, updated as the cart advances, released on arrival, abort,
  disconnect, or shutdown;
- tickets are non-persistent and expire if unloaded;
- readiness requires loaded + entity-ticking + entity section loaded, not just
  `isLoaded`.

If the next chunk is not ready, the cart is stopped and waits. After a bounded
wait it aborts safely with `CHUNKS_NOT_READY`; there is no teleport and no forced
new terrain.

Launch validation treats unloaded route tails as known from the scan. Loaded
portions are validated at launch; unloaded portions, target exit, and turnouts are
validated when the ticket window reaches them. This allows a route to start outside
the currently loaded area.

A route is plannable as soon as one endpoint graph covers both docks. A separate
scan of the destination station is not required: if the source graph contains the
destination dock (for example when the origin's scan finished before the
destination's), it is used for the component/revision check and planning. The
same fallback applies to `/rail route check`, which does not start a destination
scan when the source graph already covers it.

### Automatic scans

Graphs are derived caches and are never persisted, so scanning needs no admin
command:

- on server start, after recovery has begun, anchored stations in loaded
  dimensions are queued and scanned one at a time. Provisional stations are
  skipped; a missing level or a rejected start is only logged and never disables
  Railway;
- when one scan succeeds, every anchored station whose dock is on that connected
  component reuses the graph and its queued scan is skipped. Startup therefore
  performs one scan per component, not one per station, and logs a summary of
  scans started/completed, stations covered, failures and skipped stations;
- a trip launch or `/rail route check` that finds neither a graph nor a running
  job starts the scan automatically and rejects that attempt with `SCAN_RUNNING`
  ("scan started automatically"); the next attempt succeeds once the scan
  finishes. Covered stations resolve to their owner's graph, so they do not start
  another scan. If the source graph already contains the destination dock, the
  destination is not rescanned;
- jobs are deduplicated per station and continue in the background after players
  join; a restart simply starts fresh scans. A manual `/rail network scan` of a
  covered station clears its alias and starts a real job;
- invalidating a block on a shared graph, or invalidating any station that uses
  it, removes the graph from the owner and every covered station; no alias keeps a
  stale graph;
- after a failed automatic scan, further automatic attempts for that station wait
  200 ticks (10 seconds). A successful scan or an invalidation clears the delay,
  and `/rail network scan <station>` bypasses it. The last failure remains
  visible in `/rail network status` and `/rail diagnostics`;
- if `maxManagedChunkTickets` is 0 or the shared budget cannot fit the frontier,
  the job fails with `CHUNKS_NOT_READY`; startup, trips and other stations are
  unaffected.

### Scan-time chunk loading

A scan that reaches a rail component outside the loaded area pauses instead of
failing with `SCAN_INCOMPLETE`:

- only the chunks of the current unloaded frontier are requested, never the whole
  route;
- a chunk is requested only after a read-only level-storage probe proves it was
  saved; a chunk that was never saved cannot contain player-built rail and is
  treated as empty, not ticketed;
- the ticket is server-only, non-persistent and loading-only (no simulation),
  with radius 0;
- scan tickets share `maxManagedChunkTickets` with trip and recovery tickets and
  are additionally capped at 32; a frontier that cannot fit the budget fails with
  `CHUNKS_NOT_READY` instead of planning a partial graph;
- the scan retries the blocked frontier every tick and keeps discovering loaded
  neighbours meanwhile;
- after 200 consecutive ticks on the same unresolved chunk set the scan fails with
  `CHUNKS_NOT_READY` at the first pending block and releases its tickets;
- tickets are also released on completion, failure, scan invalidation/clear and
  shutdown; ticket timeouts (200 ticks) are a final safety net for crashes.

Resource bounds: a scan holds at most the frontier chunks around its blocked
positions (in practice one or two), not a route-length window. Long routes are
thus scanned incrementally; the number of loaded chunks does not grow with route
length. With `maxManagedChunkTickets: 0` a scan cannot leave the naturally loaded
area and fails with `CHUNKS_NOT_READY`.

Limitation: strict storage probing controls which chunks Railway tickets. Vanilla
chunk loading still computes the neighbors a chunk's status requires, so loading
an existing saved chunk can cause the generator to produce a missing neighbor. A
strict no-generation guarantee would require replacing vanilla ticket propagation;
see the generation caveat below.

### Generation caveat

Railway requests tickets only for route chunks proven by the scanned graph and for
scan-frontier chunks proven to exist in level storage. It does not call force-load
or generation APIs. Vanilla ticket propagation can still load an adjacent missing
chunk at the radius boundary. A strict no-generation guarantee would require
replacing vanilla ticket propagation. This is a known limitation of the current
build.

## Restart and recovery

State checkpoints are stored in world state schema 4 with:

- trip/player/cart UUIDs, from/to stations, phase, started tick;
- dimension and last checkpointed cart position.

### Clean shutdown

Active trips are stopped, carts are discarded, their checkpoints are removed, and
state is saved. A normal restart has no active trips.

### Crash recovery

After an unclean stop:

- checkpoints are not discarded at startup;
- new trips are blocked with `RECOVERY_PENDING` while unresolved checkpoints exist;
- for each checkpoint Railway looks for the service cart by UUID in loaded levels;
- if the recorded chunk is not loaded but exists on disk, one bounded
  non-persistent recovery ticket is requested; no new chunks are generated;
- an empty managed cart is discarded and the checkpoint is closed;
- an occupied managed cart is left safely stopped; the checkpoint stays until it
  becomes empty or reaches the deadline;
- an unmanaged/user cart with a matching UUID is preserved, with a diagnostic;
- an absent cart with a loaded/missing chunk closes the checkpoint;
- a chunk that is not yet loaded is reconciled by the late-chunk status event or
  the orphan sweep;
- the deadline is 1,200 ticks; after that the checkpoint drops and late entities
  are left to the orphan sweep.

Trips **never resume automatically**. Players are never teleported to an
unconfirmed exit. On join, a recovered managed cart is stopped in place instead
of being teleported or force-dismounted.

Recovery tickets and state are reported by `/rail diagnostics`.

## Reservations and quarantine

- Route resources are acquired atomically before spawn; no waiting queue.
- A blocked resource returns `PATH_OCCUPIED`, `RESERVATION_CONFLICT`, or
  `DOCK_OCCUPIED`.
- If a service cart's removal cannot be confirmed, the trip's resources enter
  quarantine. They stay unavailable until reconciliation by cart UUID confirms
  the cart is gone.
- An orphan tagged cart is removed only after its trip/checkpoint is no longer
  pending. Ordinary user carts are never touched.

## Failure reference

| Code | Typical cause / action |
|---|---|
| `STATION_NOT_FOUND` | Wrong ID; use `/rail station list` |
| `STATION_INCOMPLETE` | No exit, or provisional destination not anchored |
| `STATION_DISABLED` | Enable the station |
| `DESTINATION_NOT_ANCHORED` | Save the destination's return sign |
| `SCAN_INCOMPLETE` / `SCAN_RUNNING` | Scans auto-start; wait and retry. Monitor with `/rail network status <station>`; `/rail network scan` forces a rescan |
| `SCAN_FAILED` | Inspect scan status; fix rail load/geometry. Automatic retries pause for 10 seconds per station |
| `NO_RAIL` / `UNSUPPORTED_RAIL` / `UNSUPPORTED_GEOMETRY` | Fix rail or dock; inspect `/rail diagnostics` |
| `NO_PATH` | Graph disconnected or different revisions; rescan |
| `BUTTON_REPLACED` | Re-register the sign/button |
| `PLAYER_TOO_FAR` | Move within the sign/main boarding envelope |
| `DOCK_OCCUPIED` / `PATH_OCCUPIED` / `RESERVATION_CONFLICT` | Another trip owns the resource; retry |
| `EXIT_UNSAFE` | Set a safe platform exit |
| `TRIP_LIMIT` | Raise `maxActiveTrips` or wait |
| `CHUNKS_NOT_READY` | Ticket window did not become entity-ticking in time |
| `RECOVERY_PENDING` | Wait for startup/late-chunk reconciliation |
| `INTERNAL` | Check server log and `/rail diagnostics`; report reproduction |

## Backups and removal

Before upgrading, stop the server and back up the world. Migrations create
`<world>/railway/state.vN.bak` automatically.

To remove Railway:

1. Close active trips or stop the server; clean shutdown removes service carts.
2. Back up the world.
3. Remove the Railway jar and optionally `config/railway.json`.
4. Restart. Signs, rails, buttons, and platforms remain vanilla. `railway/state.json`
   is ignored by vanilla.

The Stage 5 acceptance run loaded a copied world on a vanilla 26.1.2 server and
reached `Done` successfully.

## Limits and performance

- Config limits: `maxActiveTrips`, `maxManagedChunkTickets`, `maxRouteLength`,
  scan budgets, `maxTripTicks`.
- Ticket window size is local; not a whole-route force-load.
- Scans run in tick-batched jobs and are not persisted; they restart
  automatically after a server restart and on demand, so no manual rescan is
  needed.
- Diagnostics output is unpaginated chat.
- Large networks can produce long `/rail diagnostics` output and many scan jobs.

## Acceptance status

Verified:

- 226 unit tests and 53 GameTests, including automatic startup/on-demand scans,
  retry cooldown, and scan-ticket isolation from background jobs;
- live diagnostics;
- live startup recovery with a tagged cart + checkpoint;
- vanilla world load without Railway;
- schema 4 migration and backup.

Not fully proven:

- a true long route beyond simulation distance with a real client;
- a true mid-trip `kill -9` (startup checkpoint recovery was exercised with a real
  tagged entity);
- non-OP builder acceptance in a live config;
- a separate Fabric-modpack removal run.

See [`stage-5-report.md`](stage-5-report.md).
