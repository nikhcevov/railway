# Changelog

## 0.1.0-alpha.10 — 2026-11-11

- Routes no longer lock the entire line from departure to arrival. Track
  sections (`sec|...`) now use shared-direction corridor leases (preventing
  head-on single-track deadlocks while allowing multiple carts in the same
  direction) combined with rolling exclusive `seg|...` segments, switch conflict
  zones, and platform locks.
- New `segmentLength` configuration key (default `32`, valid range `4..1024`)
  controls the exclusive reservation segment length in rail blocks.
- Once a departing cart clears the departure dock (`stepIndex >= 2`), the next
  passenger can press the button, board their service cart immediately, and wait
  in `BOARDING` with a live actionbar countdown (`actionbar.waiting_ahead` /
  `actionbar.waiting_oncoming`) until the initial segment clears.
- Turnouts are only prepared and enforced once a trip owns their `swz|...`
  conflict zone, and standing players near a dock no longer trigger
  `DOCK_OCCUPIED`.
- A hit on an occupied service cart no longer destroys it. The cart ignores
  damage, so the passenger keeps the trip and no minecart item is dropped or
  picked up; ordinary carts keep vanilla damage and drops.
- Explosions no longer damage or move a service cart. Blast damage to rails
  still invalidates the route and aborts the trip; the passenger still takes
  explosion damage, and ordinary carts keep vanilla explosion behaviour.
- Starting a trip no longer fails because a mob, animal, player or another cart
  is somewhere on the loaded route. A service cart ignores entity collisions:
  it is not pushable, does not push mobs/players/other carts, and passes through
  them. Broken or blocked rails still stop and abort the trip, and ordinary
  carts keep vanilla collision behaviour.

## 0.1.0-alpha.9 — 2026-11-08

- Removing the anchor or main sign no longer leaves the station pointing at the
  destroyed dock. The nearest remaining unit becomes the station main
  automatically (local dock plus a safe exit) and its graph is rescanned.
- A sign carrying `m` can move main to a dock outside the station's current
  graph; the move is no longer rejected with "local dock is not on the
  station rail", and the new main triggers a rescan.
- If no remaining unit can become main, the station is disabled and the breaker
  is asked to add `m` to one of its signs; saving `m` re-enables the station and
  sets the new main explicitly.
- Removing the last sign unit reports that the station is disabled and how to
  restore it (save a sign with the same name) instead of incorrectly asking for
  an `m` on a non-existent sign.

## 0.1.0-alpha.8 — 2026-11-08

- Structured, greppable logging for failures and lifecycle decisions:
  `event=... key=value ... code=... detail="..." ref=<id>`. The startup line
  reports the exact mod version, state path and effective limits.
- Every `Failure` now carries a stable `ref`, is logged with a non-empty detail
  (even when an exception has no message), and shows the same `[ref ...]` to the
  player. `INTERNAL` aborts can no longer appear without a reason.
- Rejected sign edits are logged at WARN instead of DEBUG, and command
  executions log actor, raw input and result.
- Persistent failure journal at `<world>/railway/failures.jsonl` (256-entry
  memory ring, 1 MiB rotation) with `/rail diagnostics failures [count]` and
  `/rail diagnostics ref <ref>`.
- New `/rail network rescan`: drops every cached graph and re-analyzes each
  connected component once, one scan at a time. Startup already performs the
  same full pass, so a version change needs no extra rescan.
- Scan, trip, sign, command, state, recovery and message-catalog events are
  documented in `docs/operations.md`; `/rail diagnostics` remains the live-state
  snapshot.

## 0.1.0-alpha.7 — 2026-11-01

- Restoring a disabled station with a sign at a different dock now moves the
  station main arrival point to the restored sign when no sign units remain.
  Previously the station re-enabled but kept arriving at the old, possibly
  destroyed main dock/exit.

## 0.1.0-alpha.6 — 2026-10-29

- Powered rails are treated as ordinary track for Railway-managed service carts.
  Vanilla powered-rail acceleration and unpowered-rail slowdown are suppressed,
  preventing arrival overshoot and internal aborts without player-side rules.
- Normal, non-managed minecarts keep vanilla powered-rail behavior.
- Added a GameTest proving a managed cart is not accelerated by a powered rail.

## 0.1.0-alpha.5 — 2026-10-29

- `/rail route check` now rejects disabled source or destination stations with
  the station display name. Actual passenger trips already enforced this.
- Added GameTest coverage for disabled source and destination trips.

## 0.1.0-alpha.4 — 2026-10-28

- Restoring the last destroyed sign with the same station name re-enables the
  station when it has no sign units left. An admin-disabled station that still
  has signs remains disabled.
- Command success messages for exit, rename, enable, disable, remove, and button
  binding use station display names.
- Restored stations report `Station <name> re-enabled.` in chat.

## 0.1.0-alpha.3 — 2026-10-28

- Player-facing failures now show station display names instead of internal IDs.
- Disabled, incomplete, and active-trip notifications use readable names.
- Dimension mismatches report the dimension problem instead of a station UUID.
- Sign validation details are localized (EN/RU) instead of exposing enum names.
- Internal failure codes are hidden from player chat; `/rail diagnostics` retains
  them for support reports.
- Missing station references now say the station for the button no longer exists.

## 0.1.0-alpha.2 — 2026-10-28

- Invalidate the scanned component graph when a rail is placed, removed, or
  replaced next to an existing component. Adding a new branch no longer leaves a
  stale graph that rejects the new destination sign with `SIGN_GEOMETRY`.
- The invalidation is limited to graphs adjacent to the changed rail, so separate
  networks and GameTest plots do not invalidate each other.

## 0.1.0-alpha.1 — 2026-10-28

Initial public alpha of Railway, a server-side Fabric mod for automated passenger
minecart travel over player-built vanilla rails.

### Included

- Station, destination-button, sign, and binding model.
- Straight, curved, sloped, and turnout routing.
- Managed service-cart speed control.
- Atomic route reservations and recovery.
- Server-side construction through vanilla wall signs and stone buttons.
- Bounded chunk tickets for long routes and unloaded destination chunks.
- Automatic startup scans, one per connected component, with shared graphs.
- Bounded scan-frontier ticket subsets for wide rail components.
- Crash-recovery checkpoints, restart reconciliation, and diagnostics.
- Development-only fixture generator and kill -9 acceptance harness.
- English and Russian message catalogs.
- Alpha Modrinth icon and release banner.

### Known limitations

- No client mod or resource pack; this is a server-side mod.
- Arrival is at the station main exit. Per-origin arrival platforms are not
  implemented.
- No queues, cargo, schedules, portals, custom rails, or GUI.
- Vanilla chunk-ticket propagation can load an adjacent boundary chunk. Railway
  does not request new chunks or generate terrain during a scan.
- Test only on a backed-up world.
- The alpha jar retains dev-gated fixture classes; they are not registered in a
  production environment.
