# Railway

**Server-side automated passenger minecart travel over player-built vanilla rails.**

Railway turns a vanilla rail network into a small passenger transit system. Build a
station from ordinary rails, wall signs, and stone buttons. A passenger presses the
button for their destination, a service minecart arrives, and Railway routes it
through the network to the destination station. The passenger is placed safely at
the station exit and the service cart is removed.

**Ordinary clients do not need this mod.** Railway runs entirely on the Fabric
server; no client mod, resource pack, or custom blocks are required.

> **Alpha release.** Test on a backup world. The current version is
> `0.1.0-alpha.3`.

## Requirements

| Component | Version |
|---|---|
| Minecraft Java Edition | `26.1.2` |
| Fabric Loader | `0.20.1+` |
| Fabric API | `0.155.3+26.1.2` |
| Java | `25+` |
| Side | Server only |

## What it does

- **Vanilla construction.** Stations are made from ordinary rails, wall signs, and
  stone buttons. There are no custom blocks, items, or entities.
- **Graph routing.** Straight lines, curves, slopes, and turnouts are routed over
  a scanned rail graph.
- **Automatic service carts.** Pressing a destination button spawns a managed
  minecart, seats the passenger, and follows the route.
- **Safe arrivals.** The cart stops at the destination dock and the passenger is
  moved to the station exit.
- **Long routes.** Scans automatically load existing rail chunks through a bounded
  ticket window; a destination outside the simulation distance can still be
  reached.
- **No permanent force-loading.** Only the current local travel window and the
  scan frontier are ticketed.
- **Crash recovery.** Interrupted services are reconciled after restart. Trips do
  not resume automatically, and leftover managed carts are handled.
- **Diagnostics.** Operators can inspect routes, scans, tickets, recovery, and
  active trips.

## Quick start

1. Install Fabric Loader and Fabric API on a Minecraft `26.1.2` server.
2. Put `railway-0.1.0-alpha.3.jar` in the server `mods/` directory.
3. Start the server once. Railway creates `config/railway.json`.
4. Give builders OP level 2, or list their UUIDs in `builders`.
5. Build a station from vanilla rails, wall signs, and stone buttons.

No client installation is required.

## Building a station

A station has a **main arrival pillar** and any number of destination pillars.
Each destination unit is one solid pillar with one **wall sign** and one **stone
button**.

```text
[Railway]
Home
Garden
m
```

- Line 1: exact `[Railway]` marker.
- Line 2: source station name.
- Line 3: destination station name.
- Line 4: optional `m`; marks this pillar as the station main arrival point.

Example layout:

```text
Home station
├── central main-only pillar:  [Railway] Home (blank line 3) m   no button
├── Home/Garden pillar:        [Railway] Home / Garden            stone button
└── Home/Mine pillar:          [Railway] Home / Mine              stone button

Garden: [Railway] Garden / Home + stone button
Mine:   [Railway] Mine / Home   + stone button
```

Rules:

- Use a wall sign, not a standing or hanging sign.
- Use one stone button on the same solid pillar column as a destination sign.
- The sign and button can be at independent heights, one to three blocks above the
  rail support.
- The pillar must be one to three horizontal blocks from the local rail.
- The sign must face across the track. Railway checks both sides for exactly one
  straight horizontal dock rail.
- One pillar carries one sign and one button.
- A destination unit must be within four horizontal blocks of an existing pillar
  of the same station.
- Save the sign after placing the button. If you add or remove the button later,
  save the sign again.

## Passenger flow

1. Stand at a destination pillar and press its stone button.
2. Railway checks the station, route, reservations, and chunk readiness.
3. A service cart spawns at the local dock; the passenger is seated automatically.
4. The cart follows the graph route through curves, slopes, and turnouts.
5. It stops at the destination main dock and teleports the passenger to the
   station exit.
6. The service cart is removed.

There is no ticket, queue, GUI, or passenger trip command.

## Commands

All station changes require OP level 2 or a listed builder. Passengers only press
buttons.

```text
/rail station create <id> <displayName...>
/rail station exit <id>
/rail station rename <id> <displayName...>
/rail station enable|disable <id>
/rail station info <id>
/rail station list
/rail station remove <id>
/rail button bind|info|unbind
/rail network scan <station>
/rail network status [station]
/rail route check <from> <to>
/rail trip list
/rail trip abort <tripId>
/rail diagnostics [station]
/rail reload
```

Service signs are the normal construction path. The command path remains for
administration and migration.

## Configuration

Config lives at `config/railway.json`. Important defaults:

| Key | Default | Purpose |
|---|---:|---|
| `maxActiveTrips` | 8 | Concurrent trips |
| `maxSpeed` | 12.0 | Target blocks/second, capped at 12 |
| `boardingRadius` | 4 | Command/legacy boarding radius |
| `maxManagedChunkTickets` | 128 | Local ticket budget; 0 disables tickets |
| `maxRouteLength` | 10000 | Maximum graph route length |
| `builders` | `[]` | UUIDs allowed to register signs in addition to OP2 |

Invalid configuration is not overwritten. Railway disables itself until the file
is fixed and `/rail reload` or a restart succeeds.

## Automatic scans

Graphs are derived caches and are never persisted. On server start, Railway scans
anchored stations in the background, one connected component at a time. Stations
on the same component share the resulting graph, so a network with many stations
does not scan the same rails repeatedly.

A missing graph is also rebuilt automatically on the next trip or
`/rail route check`. No manual scan command is required. `/rail network scan` is
available as a forced rescan and diagnostics tool.

Wide components are processed in bounded frontier chunks. Railway never requests
new terrain generation while scanning.

## Recovery and operations

- `/rail diagnostics` shows state, scans, tickets, reservations, active trips,
  recovery, and station health.
- Checkpoints are reconciled after restart. Trips never resume automatically.
- Interrupted managed carts are removed or stopped safely.
- `/rail reload` applies configuration to new trips; active trips keep their
  snapshot.
- State is stored in `<world>/railway/state.json`; schema migrations create a
  rolling backup.

## Compatibility and limitations

- Tested with Minecraft `26.1.2` and the Fabric versions listed above.
- Tested server stack: C2ME, Lithium, Krypton, FerriteCore, and Clumps.
- Other optimization or movement mods are not automatically supported.
- Arrival is always at the station main exit. Per-origin arrival platforms are not
  implemented.
- No queues, cargo, schedules, portals, custom rails, or GUI.
- Detector and activator rails are rejected by managed routes.
- Ordinary decorative signs are ignored.
- Vanilla chunk-ticket propagation can load an adjacent boundary chunk. Railway
  itself does not generate new chunks during a scan, but the ticket boundary is
  not a hard no-generation guarantee.
- No guarantee of protection from grief or other movement mods.

## Alpha status

This is an early release intended for test worlds and feedback. Please:

- back up the world before installing;
- test with the pinned versions first;
- report the exact Minecraft, Fabric API, and Railway versions;
- include `/rail diagnostics` output and relevant server logs with bug reports.

## Links

- [Source and issue tracker](https://github.com/nikhcevov/railway)
- [Commands](https://github.com/nikhcevov/railway/blob/main/docs/commands.md)
- [Configuration](https://github.com/nikhcevov/railway/blob/main/docs/configuration.md)
- [Operations and recovery](https://github.com/nikhcevov/railway/blob/main/docs/operations.md)
- [Development and testing](https://github.com/nikhcevov/railway/blob/main/docs/development.md)
- [Production TODO](https://github.com/nikhcevov/railway/blob/main/TODO.md)

## License

MIT. Copyright (c) 2026 nikhcevov.
