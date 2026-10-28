# Changelog

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
