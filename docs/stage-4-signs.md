# Stage 4 service signs, combined pillar model

Status: implementation contract for the first Stage 4 release. Supersedes the separate
station-sign / destination-sign model in `Railway-Spec-v1.md` sections 7, 10, and 14 for
new registrations. Keep the old reservation, trip, and station behavior.

## Goals

- Remove mandatory long ASCII station IDs from signs.
- Make one physical pillar carry one destination unit: one sign, one button.
- Allow a station to expose any number of destinations without placing a separate
  station-only sign in the common case.
- Keep stations and bindings recoverable and deterministic after restarts.
- Preserve the existing command/API path and all existing reservations.

## Names and identity

- Sign line 2 is the `from` station name. Line 3 is the `to` station name.
- Names are user-facing labels and lookup keys within one dimension.
- Normalize names with trim + collapse internal whitespace; compare case-insensitively.
  The first spelling registers the display name. A name must be unique per dimension.
- Every station still has a hidden stable `StationId`. Generate IDs for sign-created
  stations deterministically from `dimension + normalized name`; never expose the ID on
  the sign. Existing command-created IDs remain valid.
- A registered station with bindings cannot be renamed. A sign edit that would change
  the from/to identity of an existing button binding is rejected and the sign restored.
- A duplicate or ambiguous name is rejected with a clear message.

## Geometry

One destination unit is one solid pillar with exactly one wall sign and one stone button:

```text
             [sign: [Railway] / Home / Mine]
                     |
        pillar       [button]
             |
             | 1-3 blocks
             v
      ======= rail =======
```

- Part of the same solid pillar: the sign's support and the button's support must be in
  the same `(x,z)` column.
- Pillar horizontal distance from the target rail: 1-3 blocks.
- Sign and button heights: independently `dockY + 1` through `dockY + 3`. They do not
  have to be at the same height.
- One sign and one stone button per pillar. Multiple destinations use multiple pillars.
- The sign's horizontal normal axis must be perpendicular to the target track. Railway
  scans **both** the front and back normal directions in a one-block-wide prism, 1-5
  blocks horizontally. Exactly one of the two sides must find a dock. If both sides hit
  rails, the placement is ambiguous and is rejected. This lets the readable sign face
  either the platform or the track.
- Vertical scan window: from `signY - 3` through `signY` (rail at or below the sign).
  The first horizontal distance containing exactly one straight horizontal rail block is
  the dock. Multiple rail blocks in the same column are ambiguous. A curve, slope,
  crossing, or non-straight rail is rejected.
- Show resolved dock coordinates and particles. Do not silently switch to a different dock.
- A destination unit for an already-anchored station is validated against the station
  dock: horizontal distance <= 4, same dimension, sign/button in the vertical range.

## Sign text and events

The service side is the sign side that was edited (front or back). Requirement:

```text
line 1: [Railway]       exact marker, case-insensitive
line 2: <from>          required
line 3: <to>            required unless this is a station-only anchor
```

- Use `getText(side, false)` (raw, unfiltered) for parsing. Read the edited side only.
- Line 1 may be `[Railway]` (preferred) or `[railway]`; reject `[go]`, `[station]`, and
  legacy formats with an explanatory message. Do not rewrite sign text.
- A blank line 3 is a station-only anchor. It creates or anchors a station and has no
  button or binding. It is the only way to create a station with no outbound route.
- A nonblank line 3 requires exactly one stone button in the same pillar unit.
- Register on sign save, and re-run after button placement by re-saving the sign. Do not
  register half-filled signs.
- Registration is denied to non-builders; default permission is OP level 2. Do not
  consume or clear the sign text on failure.

## Registration flow

For every valid unit:

1. Resolve the pillar column and exactly one sign/button.
2. Parse normalized from/to names.
3. If `from` is unknown:
   - this sign is the station anchor;
   - resolve the dock from the sign normals and the straight rail rules;
   - select the exit: prefer the builder's safe standing feet if it is within the
     boarding horizontal radius (4) and vertical tolerance (3) of the dock; otherwise
     use a deterministic safe ring around the pillar base; exclude rails, hazards, and
     outside-world positions; reject if nothing is safe;
   - create the station enabled and begin the normal conditional reservation scan.
4. If `from` is provisional:
   - this sign anchors it using the same dock/exit rules;
   - keep the same `StationId`, so bindings already pointing at the provisional station
     become usable.
5. If `from` is anchored:
   - the sign is a destination unit;
   - require the unit to be within the anchored station's dock range;
   - do not resolve a new dock and do not create another station.
6. If `to` is unknown and nonblank: create a provisional station in the same dimension
   with no dock, no exit, and disabled. Do not start a scan.
7. Create the binding `from -> to` keyed by the button block. Reject self-bindings,
   duplicate buttons, and conflicting button ownership.
8. Persist namespace, station, binding, and sign marker atomically enough for the
   existing state store. A failed save must leave no partial route.
9. Auto-scan only when a station becomes anchored. If the scan is incomplete, keep the
   station usable but trip starts must fail with the existing scan-incomplete reason.

A provisional station with no remaining bindings and no anchor is pruned. An anchored
station is never pruned automatically.

### Bootstrap

```text
Home: [Railway] Home Mine  + button
Mine: [Railway] Mine Home  + button
```

1. Home sign anchors Home, creates provisional Mine, stores Home -> Mine.
2. Launches are rejected with "destination not anchored".
3. Mine sign anchors the provisional Mine and stores Mine -> Home.
4. Both routes now start normally.

`Home -> Mine` does not create a physical return button. Bidirectional service requires
the second sign at Mine.

## Persistence

- Store sign markers with: dimension, sign position, edited-side flag, support position,
  optional button position, anchor flag, and station/binding identity.
- Store provisional stations explicitly. `Station` must tolerate a null dock/exit for a
  provisional station; every route, command, and UI path must handle it.
- Keep names indexed or derive the index safely. The first spelling is display; lookups
  are normalized.
- Bump the world-state schema and migrate old state without losing existing stations,
  bindings, trips, or namespace entries. Keep the rolling backup behavior.
- Sign markers must survive restart and remain breakable.

## Lifecycle and conflicts

- Breaking a destination unit's sign or button removes only that binding.
- Breaking an anchor sign disables the station. Other bindings remain stored but launches
  fail until the station is re-enabled (existing command) or removed. Active trips
  continue.
- Breaking the pillar/support follows the normal sign/button pop path and removes the
  same marker.
- Piston movement is unsupported. Moving marker blocks invalidates the registration;
  never silently follow moved blocks.
- The mod never removes a player's dock, platform, pillar, or unrelated signs.
- Redstone/powered rails keep existing behavior; sign registration and launches do not
  bypass reservation, trust, or trip rules.

## Boarding

For sign-created stations, boarding checks use horizontal distance <= 4 and vertical
difference <= 3. This is required so a button at `dockY + 3` is reachable. Keep the
returned exit within the same boarding envelope.

## Acceptance tests

Unit tests:

- both normal directions find exactly one dock; both-hit and no-hit rejected;
- pillar 1-3 and sign/button heights 1-3;
- straight horizontal rail only; curve/slope/crossing rejected;
- name normalize/uniqueness and hidden deterministic ID;
- provisional station lifecycle and pruning;
- sign parse: marker, blank line 3, bad marker, missing lines;
- break marker removes only the intended binding; anchor break disables station;
- persistence round-trip of markers and provisional stations.

GameTests:

- Home signal anchors Home, provisional Mine, binding, and blocks launch with
  destination-not-anchored;
- Mine sign anchors provisional Mine and both routes start;
- destination unit for an anchored station with high sign/button and within range;
- sign with no button rejected; button then sign accepted;
- sign break removes binding; active trip continues;
- existing straight-line, turnout, reservation, and speed suites remain green.

## Out of scope for this change

- Automatic return-button placement.
- Multi-block station buildings, elevators, and station-only separate formats.
- Staging/queues, server-wide route planning, and chunk tickets.
- Paired double-track and pathfinding changes.

## Verification required

- Unit suite green.
- All existing GameTests green, including the new sign tests.
- Manual client/server Scenario 3 still passes: build both sign units, take a trip Home
  to Mine, press Mine/Home, return, and see the Home exit restored.
