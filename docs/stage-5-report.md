# Stage 5 operations report

Date: 2026-10-27. Status: implemented on top of Stage 4.1 and validated on the dev
server with the production server-mod set. No production deployment.

## Delivered

| Area | Behavior |
|---|---|
| Stage 4 closeout | UUID builder allowlist (`builders` in `config/railway.json`; OP2 always allowed), periodic piston/explosion marker invalidation, sign-unit/main details in `/rail station info` |
| Chunk tickets | Local current + braking-window tickets, bounded by `maxManagedChunkTickets`, entity-ticking readiness, safe stop/abort with `CHUNKS_NOT_READY`, no whole-route tickets |
| Long-route launch | Loaded route portions validated at launch; unloaded tails are trusted from the scan and validated as the ticket window reaches them; target exit/switch setup deferred until loaded |
| Recovery | Schema-4 dimension/position checkpoints, startup reconciliation, late chunk-status reconciliation, player-join stop, bounded non-persistent recovery tickets, no trip resume, no teleport to unknown exits |
| Diagnostics | `/rail diagnostics [station]` OP2: state/revision, recovery, trips, reservations/quarantine, scans, tickets, station units/main/bindings |
| Removal | Vanilla 26.1.2 server loads a copy of the world without Railway |

Commits: `ef39704` (Stage 4 closeout), `626c7f1` (chunk tickets), `6b6f6ad`
(long-route launch), `941a6b4` (recovery), `97d2f1d` (diagnostics).

## Automated verification

- Unit: **217 passed, 0 failed** (`cleanTest test`, repeated).
- GameTests: **49 passed, 0 failed** (`runGameTest`, repeated).
- Existing straight-line, turnout, speed, parallel, sign, chain/local/main, ticket,
  recovery, and diagnostics suites included.

## Live acceptance performed

- Latest server started with C2ME, Lithium, Krypton, FerriteCore and Clumps.
- `/rail diagnostics` from console returned live state (13 stations, 0 trips,
  0 tickets, 0 recovery pending).
- Injected a real tagged minecart and a schema-4 trip checkpoint at the world
  spawn. Restart produced:
  `Recovering 1 interrupted trip checkpoint(s)` then
  `Recovery checkpoint ... closed: cart removed`; diagnostics showed
  `checkpoints removed 1`, `carts discarded 1`, no pending recovery.
- Copied the dev world to `/tmp/railway-removal`, removed all Railway classes from
  the launch, and started a vanilla 26.1.2 server. It reached `Done (2.800s)` with
  the world loaded; no Railway blocks/entities were required.
- Schema-4 migration created `state.v3.bak`; current state is schema 4 with 13
  stations, 20 bindings, 6 sign markers, 0 trips.

## Limitations / not yet proven

- No true mid-trip `kill -9` with a real client/active trip. Startup checkpoint +
  cart recovery was exercised with a real tagged entity; periodic checkpoint
  writes and schema round-trips are covered by tests.
- No true long route beyond simulation distance. GameTest plots are force-loaded;
  local window/budget/readiness and launch-tail deferral are tested, but a real
  scan-then-unload long run remains manual. Scan-time frontier loading (below) is
  covered by unit and ticket-lifecycle tests, not by an automated multi-chunk
  unload/load cycle.
- Vanilla ticket propagation can load a missing adjacent chunk; Railway never
  requests new chunks or scans unproven chunks, but it cannot prevent vanilla
  radius propagation at the ticket boundary. A true no-generation guarantee would
  require replacing the vanilla ticket mechanism.
- No non-OP builder acceptance GameTest; config validation and the rejection path
  are covered.
- Diagnostics output is unpaginated chat; very large networks produce long output.
- The production removal test used a vanilla server and a copied world, not a
  separate Fabric modpack install.

## Follow-up: scan-time frontier loading (2026-10-28)

The original scan failed with `SCAN_INCOMPLETE` at the first unloaded candidate,
so a route whose far end was outside simulation distance could never be planned.

Implemented:

- `GraphScanner` pauses at an unloaded candidate, exposes
  `pendingPositions()`/`isWaiting()`, keeps discovering loaded neighbours, and
  retries the blocked frontier. After 200 consecutive no-progress advances it
  fails with `CHUNKS_NOT_READY` at the first pending block.
- `ScanService` requests loading tickets only for the current unloaded frontier,
  only after a read-only level-storage probe confirms the chunk was saved. Ticket
  budget is `min(maxManagedChunkTickets, 32)`, shared with trip and recovery
  tickets. Tickets are released on completion, failure, invalidation/clear and
  shutdown. Missing chunks are treated as empty, not ticketed.
- `TripService` (and `/rail route check`) no longer requires a destination scan
  when the source graph already contains the destination dock, so a restarted
  server with only the origin rescanned can still start the route.
- Diagnostics report scan-ticket counts; `/rail network status` reports a waiting
  scan and its pending chunk count.

Automated verification for this follow-up:

- Unit: **219 passed, 0 failed** (`cleanTest test`, repeated twice).
- GameTests: **51 passed, 0 failed** (`runGameTest`, repeated twice).
- New coverage: scanner wait/retry/timeout; scan ticket budget/release; route
  launch with the destination scan absent (GameTest).

Limitation: GameTest plots are force-loaded and their chunks cannot be truly
unloaded, so the full multi-chunk wait/load cycle is not exercised inside the
GameTest server. The scanner state machine (retry and timeout) and the ticket
manager are tested directly; the end-to-end long route still needs the dev
server/manual run. Vanilla ticket propagation may still generate a missing
neighbour required by a loaded chunk's status (documented in `operations.md`).
