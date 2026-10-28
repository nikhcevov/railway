# Commands

Railway keeps an administrative slash-command surface alongside service signs.
Normal player construction should use signs and buttons; commands are useful for
migration, diagnostics, and controlled stations.

## Permissions

- Most modifying commands require **OP level 2** (`COMMANDS_GAMEMASTER`).
- Passengers do not need OP. They only press a registered button.
- Service-sign registration also accepts UUIDs listed in `builders`.
- Read-only `station info`, `station list`, `button info`, and `network status`
  do not require OP.
- Player-position commands (`station create`, `station exit`, `button ...`) need a
  player source. Console receives a clear error.
- Console can use `station info/list`, `network scan/status`, `route check`,
  `trip list/abort`, `diagnostics`, and `reload`.

## Identifiers

Commands use hidden ASCII station IDs:

```text
[a-z0-9_-]{1,32}
```

Service signs use user-facing names. A sign-created station gets a hidden
deterministic ID. `/rail station list` and tab completion expose IDs. Display
names are unique per dimension, normalized and case-insensitive.

## Station commands

### `/rail station create <id> <displayName...>`

Creates a station by looking at a rail within 5 blocks. The station initially has
no exit and is disabled. The display name must be unique in the dimension.

This is the legacy/manual path. Sign construction is preferred because it also
creates bindings, exits, and local docks.

```text
/rail station create home Home
/rail station exit home
/rail station enable home
```

### `/rail station exit <id>`

Sets the station exit at the administrator's current feet block and yaw. The block
must be a safe stand. For sign-created stations, this changes the **main** exit;
it does not move the main dock or change `m`.

### `/rail station rename <id> <displayName...>`

Changes only the display name. A station with existing bindings cannot be renamed;
create a new station object instead. The new name must be unique.

### `/rail station enable|disable <id>`

Enable/disable new departures and arrivals at the station. Disabling a station
with an active trip in either direction is rejected; abort the trip first. A
provisional station cannot be enabled.

### `/rail station info <id>`

Shows ID/name, dimension, main dock/exit, enabled state, revision, number of sign
units, main-arrival source (`m` marker or main fallback), bindings with source
docks, scan status, graph metadata, and known problems.

### `/rail station list`

Lists every station, including provisional destinations and sign-created units.

### `/rail station remove <id>`

Removes the station and all bindings/registrations to and from it. It refuses when
the station has an active trip. **World blocks are not changed.** Player-built
docks, signs, buttons, platforms, and pillars remain.

## Button commands

### `/rail button bind <from> <to>`

Binds the stone button under the crosshair (within 5 blocks) to a route. Stations
must exist, be in the same dimension as the player, and the source must be
anchored. The command binding has no local source dock; it uses the source
station's main dock. Service-sign units get their own local source dock.

### `/rail button info`

Shows the binding under the crosshair, expected block ID, and current block.

### `/rail button unbind`

Removes the binding at the targeted button. The button block remains. For
service signs, prefer breaking the sign/button so the marker and scan state are
updated together.

## Network commands

### `/rail network scan <station>`

Starts a tick-batched scan from the station main dock and returns a job ID. This
is a manual forced rescan and diagnostics tool; it is not required before route
checks or trips. Scans are derived caches, never persisted: after a restart
anchored stations in loaded dimensions are scanned automatically one at a time,
and each successful scan covers every station on its connected component. A
missing graph is also built on demand by a trip launch or `/rail route check`.
If the station currently reuses another station's graph, the manual scan clears
that alias and starts a real job for the station.

A manual scan bypasses the automatic retry delay. After a failed automatic scan,
further automatic attempts for that station wait 10 seconds (200 ticks); a
successful scan clears the delay. A frontier wider than the 32-ticket scan cap is
loaded in bounded subsets, so a wide or branching component is scanned instead of
being rejected. With `maxManagedChunkTickets: 0`, or when the frontier's chunks
are missing or the shared ticket budget stays fully used by trips and other
scans, the job fails with `CHUNKS_NOT_READY` instead of loading a partial graph.

### `/rail network status [station]`

Shows scan jobs, completion/failure, rail/switch counts, graph revision, and
failure reason. A station covered by another station's component graph reports
the owning scan and its graph instead of claiming that no scan exists; the owner
lists the stations it covers. Without a station, lists all real jobs (covered
stations are not separate jobs).

## Route and trip commands

### `/rail route check <from> <to>`

Plans a route without spawning a cart or acquiring reservations. Reports length,
rail/switch count, component, and failure reason. Loaded route portions are
validated; unloaded tails are deferred to the ticket window at actual travel time.
A destination without an exit or an unanchored destination is rejected.

### `/rail trip list`

Lists active trips with phase, stations, player/cart IDs, elapsed ticks, and graph
revision.

### `/rail trip abort <tripId>`

Stops a trip safely. If no safe exit is available immediately, the trip can remain
in `ABORTING`; the server retries. An administrator may need to restore a safe exit
or intervene.

## Diagnostics

### `/rail diagnostics [station]`

Read-only operations snapshot. Global output includes:

- enabled/disabled state and reason;
- state loaded/revision and checkpoint count;
- active trips: phase, stations, UUIDs, graph revision/component/length, from/to
  docks, ticket readiness, abort reason;
- reservation ownership and quarantined resources;
- scan jobs and graph status;
- managed chunk tickets: held/budget, pending, recovery ticket stats;
- recovery: pending/blocking, checkpoints removed, carts discarded/stopped/
  preserved, probes, late chunk passes, deadlines.

With a station, the focused entry also shows dock/exit, units, main marker,
bindings with source docks, scan status, and graph metadata. Provisional stations
show as not anchored. Output is line-based and unpaginated.

## Configuration

### `/rail reload`

Revalidates `config/railway.json` and applies it to **new** trips. Active trips keep
their snapshot. If the new config is invalid, the previous config stays active and
the command reports the errors. Reload does not clear stations, bindings, trips, or
recovery checkpoints.

## Examples

### Service-sign route

Do not use commands for this in normal play:

```text
[Railway] Home Garden + stone button
[Railway] Garden Home + stone button
```

Save both signs after placing buttons. Scanning starts automatically; press the
Home/Garden button. If the very first press arrives before the scan completes it
is rejected with `SCAN_RUNNING` and the scan is started automatically, so press
again shortly. `/rail network scan home` is only needed to force a rescan after
editing rails without breaking a relevant block.

### Manual command route

```text
/rail station create home Home
/rail station exit home
/rail station enable home

/rail station create mine Mine
/rail station exit mine
/rail station enable mine

/rail button bind home mine
/rail button bind mine home

/rail route check home mine
```

The first route check also starts the missing scans automatically and may report
`SCAN_RUNNING`; run it again when the scan completes. `/rail network scan home`
remains available to force a rescan and wait for a job ID.

### Inspect a problem

```text
/rail station list
/rail station info home
/rail network status home
/rail diagnostics home
/rail route check home mine
```

## Common failures

| Code | Action |
|---|---|
| `STATION_NOT_FOUND` | Check ID from `/rail station list` |
| `STATION_INCOMPLETE` | Set a safe exit and enable; provisional must be anchored by a sign |
| `STATION_DISABLED` | Enable the station; abort active trips first |
| `DESTINATION_NOT_ANCHORED` | Build/save the destination's return sign |
| `SCAN_INCOMPLETE` / `SCAN_RUNNING` | Scans start automatically; wait and retry. Use `/rail network status <station>` to monitor, or `/rail network scan <station>` to force a rescan |
| `SCAN_FAILED` / `NO_RAIL` / `UNSUPPORTED_*` | Inspect report and rail geometry |
| `DOCK_NOT_ON_LINE` / `NO_PATH` | Dock/graph mismatch or disconnected network |
| `BUTTON_REPLACED` | Re-register the button/sign; the block was replaced |
| `PLAYER_TOO_FAR` | Move within the sign/main boarding envelope |
| `DOCK_OCCUPIED` / `PATH_OCCUPIED` / `RESERVATION_CONFLICT` | Another trip owns a resource; retry later |
| `EXIT_UNSAFE` | Choose a safe platform exit and re-enable |
| `TRIP_LIMIT` | Raise `maxActiveTrips` or wait |
| `CHUNKS_NOT_READY` | Chunk window did not become entity-ticking; inspect diagnostics/tickets |
| `RECOVERY_PENDING` | Wait for startup reconciliation to finish; inspect diagnostics |
| `PERMISSION` | Use OP2 or add builder UUID to config for signs |

## Dev-only acceptance fixtures

`/railfixture` is not a production command. In a Fabric development environment
(or with `-Drailway.fixtures=true`) it builds and verifies real acceptance
worlds for unloaded chunks, long routes, and `kill -9` recovery. See
[`fixtures.md`](fixtures.md).
