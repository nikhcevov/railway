# Stage 4.1 chained pillars, local departure, optional main flag

Status: implementation contract for the next Stage 4 increment. It extends
`docs/stage-4-signs.md`; where they differ, this file wins.

## Goal

Allow one station to occupy several chained pillars without the current 4-block
distance-to-main limit. Every destination pillar departs from its own local dock.
All incoming trips still arrive at the station's main dock/exit. A fourth sign line
optionally marks which pillar is the main arrival point.

This is not the platform model: there is still one station identity, one main
dock/exit, and one binding per destination unit.

## Sign format

```text
line 1: [Railway]
line 2: <from station>
line 3: <to station>
line 4: optional flags: "m"
```

- Three-line signs remain valid; missing/blank line 4 means no flags.
- `m` is case-insensitive and means "make this pillar the station's main arrival
  point". No other flags are defined; reject unknown flags with `SIGN_FORMAT` and
  restore the previous text if the sign is already registered.
- The edited sign side remains the service side.

## Main pillar

- Every station always has a main dock/exit.
- The first/anchor pillar initializes main.
- No marker flag is required on the first pillar.
- A unit with `m` becomes main. Its local dock and selected exit replace the
  station's main dock/exit; all future arrivals use it.
- At most one marker per station may have `m`. Saving a second `m` is rejected with
  `SIGN_MAIN_CONFLICT` and the previous text is restored. The player must clear the
  old `m` line and save it, then set the new `m`.
- Clearing `m` from the current main sign does not move main back; main is sticky at
  the last designated dock/exit. A later `m` can move it.
- Breaking the main sign does not delete the stored main dock/exit. If other units
  remain, the station stays enabled; if no sign units remain, the station is disabled
  but its data remains. A later `m` can move main.
- `m` is allowed on:
  - an anchor sign (creates/anchors the station);
  - an anchor+destination sign;
  - a destination unit of an already-anchored station;
  - a station-only anchor sign with a blank line 3.
- A new station-only sign with a blank line 3 and no button may be added to an already
  anchored station only when it carries `m`. It becomes a station unit with its own
  local dock/exit, no binding, and can move main. Re-saving such a unit with `m` cleared
  is allowed and leaves main sticky; a new station-only sign without `m` is rejected.
  A destination unit with a button may never drop its line 3 destination.
- A destination `m` unit still departs from its own local dock; only arrivals move.

## Chaining

- A new unit for an anchored station must have its sign support column within 4
  horizontal blocks of the support column of at least one existing unit of that
  station (anchor or destination). The distance to main is irrelevant.
- The first sign that anchors a station is not distance-checked.
- This supports 3-wide pillars with a one-block gap: adjacent support columns are
  within the 4-block step.
- A station is capped at 16 sign units and a 64-block horizontal span from its main
  dock. Exceeding either is rejected with `SIGN_CHAIN`.
- An intermediate broken unit does not retroactively unregister later units. The
  chain is a registration rule only.

## Local docking

- Every destination unit resolves its own dock from the sign's normal/rail, exactly
  like an anchor. Its sign/button heights and pillar distance are validated against
  that local dock.
- The binding stores the unit's local dock as `sourceDock`.
- Pressing the unit's button spawns/boards the cart at `sourceDock`; boarding uses
  the horizontal <=4 / vertical <=3 sign envelope around that local dock.
- The local dock must belong to the from station's scanned rail component. If it is
  not on the graph, launch fails with a clear unsupported-geometry/no-rail failure.
  Do not silently fall back to main.
- Legacy/command bindings have no `sourceDock`; they keep using the station main
  dock and existing boarding behavior.
- All unit source docks participate in dock occupancy and route resources.

## Arrival

- The to station's stored main dock/exit is the only arrival target.
- A main flag move updates the station's main dock/exit; future trips arrive there.
- Changing main is a station topology change: rescan the station from the new main
  dock and refresh connected station scans so graph revisions stay aligned.
- Active trips keep their existing plan.

## Persistence (schema 3)

- `Binding` gains nullable `sourceDock`.
- `SignMarker` gains owning `stationId` and boolean `main`.
- `Station` keeps one `dock`, `exit`, `exitYaw` used as main.
- Migration:
  - v1/v2 bindings have `sourceDock = null` (legacy main departure until the sign is
    re-saved).
  - v1/v2 markers have `main = false`; infer `stationId` from `anchorStationId` or
    from the binding at the marker's button.
  - Existing station main dock/exit is preserved.
  - v3 validates marker station dimensions, at most one main marker per station, and
    source-dock references only as positions.
  - Save schema 3; keep rolling backup behavior.
- Re-saving an old registered sign updates its binding `sourceDock` to the resolved
  local dock if from/to/button are unchanged. Do not reject just because the old
  sourceDock was null. A source-dock fill also refreshes the connected station scans
  so the upgraded sign can be used without a separate manual scan.

## Lifecycle

- Breaking a destination unit removes its marker and binding only.
- Breaking an anchor sign:
  - if other sign units for that station remain, keep the station enabled and keep
    its stored main dock/exit;
  - if none remain, disable the station but keep its data.
- Breaking a main sign keeps main data and any other units; main is not automatically
  reassigned.
- Button break keeps an anchor marker with its main flag; destination marker removal
  is as before.
- Support break follows the same rules.
- The mod never removes a player's pillars/docks/platforms.

## Tests

Unit:
- four-line parsing: blank, `m`, unknown flag, three-line compatibility;
- binding sourceDock round-trip;
- marker stationId/main round-trip and one-main validation;
- v2->v3 migration infers marker station/main false and preserves sourceDock=null;
- v1->v3 migration;
- main station update helper;
- chain span/unit cap (pure helper if extracted).

GameTests:
- chained far pillar (>4 from main, <=4 from an intermediate) registers and departs
  from its local dock;
- a non-chained far pillar is rejected;
- a side destination with `m` moves main dock/exit and future trips arrive there;
- second `m` is rejected; clearing old `m` then setting new works;
- breaking a main sign with other units keeps station enabled and main;
- breaking the last unit disables the station;
- existing 26 GameTests remain green.

## Out of scope

- Multiple platforms/access points with per-origin arrival.
- Station name duplication.
- Station-level graph endpoints or per-platform scans.
