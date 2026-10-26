# Service signs

Railway registers stations and destinations from ordinary vanilla **wall signs**
and **stone buttons**. No commands are required for normal construction.

## Sign text

A service sign has three or four lines:

```text
[Railway]
Home
Garden
m
```

| Line | Meaning |
|---|---|
| 1 | Exact marker `[Railway]` (case-insensitive). `[station]` and `[go]` are rejected. |
| 2 | Source station name. Required. |
| 3 | Destination station name. Blank means a station-only/main-only anchor. |
| 4 | Optional flags. Only `m` is defined. |

The sign side being saved is the **service side**. A marker may only be edited
from that side; editing the other side is rejected and reverted.

Names are user-facing labels and lookup keys. They are normalized (trimmed,
case-insensitive, internal whitespace collapsed) and must be unique per
dimension. The first spelling is the display name. Commands still use hidden
station IDs; `/rail station list` and tab completion show them. Renaming a
station with existing bindings is rejected.

## Physical unit

One destination unit is one solid pillar:

```text
               [wall sign]
                    |
        pillar     [stone button]
                    |
                    | 1-3 blocks
                    v
          ======= rail =======
```

- The sign's support and the button's support must be the same `(x,z)` column.
- One wall sign and one stone button per pillar.
- Pillar horizontal distance from the local rail: **1-3 blocks**.
- Sign and button heights: independently from `dockY` through `dockY + 2`, i.e.
  the first through third block above the rail support.
- The sign's horizontal normal axis must point across the track. Railway scans
  **both** front and back normals in a 1-5 block prism. Exactly one side must find
  a single straight horizontal rail. A curve, slope, crossing, or multiple rails
  in the same column are rejected.
- The sign may face the platform or the rail; the readable side is the side you
  save. The dock must be on the sign's normal axis.
- The button can be on any horizontal face of the same pillar, at an independent
  allowed height.
- The local dock must be on the source station's scanned rail component. A
  destination pillar on a separate unconnected track is rejected.

Use a **wall sign**. Standing/hanging signs and non-stone buttons are rejected.
Ordinary signs whose line 1 is not a known service marker are ignored.

## Station and destination registration

### First sign: anchor

The first valid sign whose line-2 station name is unknown anchors a station:

```text
[Railway]
Home
Garden
```

with a stone button creates:

- station `Home` with this pillar's local dock and selected exit;
- a provisional station `Garden`;
- binding `Home -> Garden`.

The selected exit is the builder's safe feet if possible, otherwise a
deterministic safe spot around the dock. The sign receives `m` only if you put
`m` on line 4; the first anchor is main implicitly otherwise.

A station-only anchor has a blank line 3 and no button:

```text
[Railway]
Home

```

This is useful for a station with no outbound route. On an already-anchored
station, a new buttonless unit is only accepted when it carries `m`; it becomes
an arrival-only main pillar.

### Return sign: destination is anchored

At Garden:

```text
[Railway]
Garden
Home
```

with a stone button anchors Garden and binding Garden -> Home. Now both
directions are usable, subject to a completed scan.

Until the return sign exists, a launch to the provisional station is rejected
with `DESTINATION_NOT_ANCHORED`.

### Additional destinations

Add another pillar near Home:

```text
[Railway]
Home
Mine
```

with its own button. Home gets a second destination unit. The new pillar must be
within four horizontal blocks of an existing Home pillar support (the chain
rule). It does not have to be within four blocks of main.

### Station-only/main-only central pillar

A central pillar with no route or button:

```text
[Railway]
Home

m
```

- On a new station, this creates a station-only main pillar.
- On an existing station, line 3 must be blank and `m` must be present for a new
  unit.
- No button is allowed on a station-only unit.
- Re-saving an existing buttonless unit with `m` cleared is allowed; main stays
  sticky.

A destination unit with a button can never drop its line-3 destination.

## Main arrival

Every station has one main dock/exit. Arrival always uses it.

- The first anchor initializes main.
- `m` on any unit moves main to that unit's local dock and selected exit.
- At most one marker per station may have `m`. A second `m` is rejected with
  `SIGN_MAIN_CONFLICT`; clear the old `m` (edit its line 4 to blank and save) and
  then save the new one.
- Clearing `m` clears the marker flag but **does not revert main**. Main remains
  at the last designated dock/exit until another `m` moves it.
- Breaking the main sign does not delete the stored main dock/exit; other units
  continue and main data remains. Disabling is governed by the anchor-sign rule
  above.
- A main destination unit still departs from its own local dock.

`m` is checked when the sign is saved. Moving main triggers a station rescan from
the new dock and refreshes connected station graph revisions.

## Chaining and local docking

- Each destination unit gets its own local dock. Pressing that unit's button
  spawns and boards the cart at that local dock.
- A new destination unit must be within **4 horizontal blocks** of an existing
  unit's support column for the same station.
- Station cap: **16 sign units** and **64 horizontal blocks** from main. A
  violation returns `SIGN_CHAIN`.
- The chain is a registration rule only. Breaking an intermediate unit does not
  unregister later units.
- The local dock must be on the station's scanned rail graph; otherwise
  registration is rejected.
- All local source docks participate in boarding, dock occupancy, reservations,
  and route planning.
- The source dock is persisted on the binding (`sourceDock`). Command-created
  bindings do not have one and use the station main dock.

This is what allows a wide station with several pillars: departure is local,
arrival is at main.

## Break behavior

- Breaking a destination sign/button/support removes that binding and unit.
- Breaking an anchor sign:
  - if other units remain, the station stays enabled and keeps its main data;
  - if none remain, the station is disabled but its data remains.
- Breaking a main sign keeps main data and other units.
- Breaking an anchor+button's button keeps the anchor marker with `button = null`.
- Periodic verification every 40 ticks removes registrations whose sign/button/
  support was removed by a piston, explosion, or other non-player path. Unloaded
  chunks are skipped; a marker is never invalidated merely because it is unloaded.
- Moving markers with pistons is unsupported. The old registration is removed;
  the moved blocks are not followed.

## Permissions

Registration requires OP level 2 by default. Additional builders can be listed by
UUID in `config/railway.json`:

```json
"builders": ["00000000-0000-0000-0000-000000000000"]
```

OP2 is always allowed. Non-builders cannot register/edit service signs; an edit
to an existing service sign is reverted.

## Failure messages

| Code / message | Meaning |
|---|---|
| `SIGN_FORMAT` | Bad marker, line count, flags, or blank source; check lines 1-4 |
| `SIGN_BUTTON` | Missing/multiple/non-stone button, or button not on the pillar column |
| `SIGN_GEOMETRY` | No unambiguous straight dock, bad pillar distance/height, or local dock not on the station rail |
| `SIGN_CHAIN` | Pillar too far from another unit, unit cap/span exceeded, or local dock belongs to another station |
| `SIGN_MAIN_CONFLICT` | Another unit already has `m`; clear it first |
| `SIGN_LOCKED` | Trying to change the identity/side/ownership of a registered unit |
| `DESTINATION_NOT_ANCHORED` | Return sign has not anchored the destination yet |
| `EXIT_UNSAFE` | No safe exit could be selected |
| `PERMISSION` | Builder is not OP2 or in `builders` |

## Migration from earlier sign versions

State schema 4 loads older sign state and preserves station/main data. Legacy
bindings have `sourceDock = null` and depart from the station main until the sign
is saved once again. Legacy markers have `main = false`.

To upgrade a station:

1. Re-save each old service sign once. This fills its local source dock and
   refreshes connected scans.
2. Add `m` to the central/main sign if you want it as the arrival point. If it has
   no route/button, use the blank-line-3 main-only form.
3. Re-save remote signs if you want local departure at those stations too.

## Not implemented

- Per-origin arrival platforms; all arrivals use station main.
- Multiple routes on one destination unit.
- Automatic return-button placement.
- Standing/hanging signs or non-stone buttons.
- Detector/activator rail semantics for managed routes.
- Following blocks moved by pistons.
