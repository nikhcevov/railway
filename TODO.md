# Railway production TODO

Last updated: 2026-10-29. Current alpha: `0.1.0-alpha.5`.

## Verified

- [x] Unit tests: 245 passing.
- [x] GameTests: 62 passing.
- [x] Automatic startup scans, one scan per connected component.
- [x] Scan frontier wider than 32 chunks is processed in bounded subsets.
- [x] Long-route fixture: 513 rails, 512-block route both directions, destination unloaded before scan, scan auto-loads it, no recovery debris.
- [x] Mid-trip `kill -9`: checkpoint closed, managed cart removed, trip not resumed.
- [x] Clean long-route arrival and return tested by the user on the fixture server.
- [x] Rail branch placement invalidates stale graphs, so adding a destination pillar no longer fails with a stale `SIGN_GEOMETRY`.
- [x] Player notifications use station display names for disabled/incomplete/active-trip failures.
- [x] Internal failure codes are hidden from player chat; `/rail diagnostics` retains them.
- [x] Sign validation details are localized in English and Russian.
- [x] Restoring the last destroyed sign re-enables a station with no remaining sign units.
- [x] An explicitly disabled station with remaining signs stays disabled.
- [x] Disabled source/destination stations reject actual trips (GameTest coverage).
- [x] `/rail route check` rejects disabled source/destination stations.
- [x] Alpha icon, 16:9 banner, changelog, and automatic Modrinth release pipeline.
- [x] Alpha.5 published to Modrinth and tagged `v0.1.0-alpha.5`.
- [x] Standalone server booted from downloaded Modrinth jars (alpha.1), then updated through alpha.5 local builds.

## Modrinth page — finish manually

- [ ] Confirm the project icon is `src/main/resources/assets/railway/icon.png`.
- [ ] Confirm `docs/assets/railway-alpha-banner.png` is in the gallery.
- [ ] Paste or sync `docs/modrinth-description.md` as the project body.
- [ ] Confirm the live version is type `alpha`, loader `fabric`, environment `server_only`, game `26.1.2`.
- [ ] Download the published alpha.5 jar and start it on a fresh copy server.
- [ ] Check the gallery screenshots/video.

## Remaining P0 checks

- [ ] **Non-OP builder:** builder UUID without OP registers a sign, saves a destination, and starts a trip; an unlisted player is rejected.
- [ ] **Concurrent trips and turnouts:** several players at once, shared junction conflicts, abort, disconnect, and reconnect; no duplicate carts or stuck reservations.
- [ ] **Backup/corrupt-state drill:** corrupt `state.json`, stale schema, missing backup, invalid config, failed save/disk conditions.
- [ ] **Final removal:** remove Railway from a copied world and load it with vanilla; no orphan entities or required custom blocks.
- [ ] **Copied real-world test:** install alpha.5 on a copy of the production world, build one low-risk two-station route, and run passenger/return/restart cycles.
- [ ] **Soak test:** leave the copied-world server running for 24 hours with several trips; watch TPS, logs, tickets, and diagnostics.

## Before beta/stable

- [ ] Wide-frontier live fixture, if a real non-synthetic connected geometry can be built; current coverage is unit/GameTest only.
- [ ] Remove or exclude the dev-only fixture package and `/railfixture` registration from the production jar.
- [ ] Re-run the full acceptance matrix on the final jar and pinned dependency versions.
- [ ] Confirm production configuration limits: `maxActiveTrips`, `maxManagedChunkTickets`, `maxRailsPerScan`, `maxTripTicks`, `maxRouteLength`.
- [ ] Document the final compatibility matrix and no-generation caveat.
- [ ] Build the beta/stable artifact, changelog, tag, and Modrinth version.

## Known limitations to keep in the project body

- Server-side only; no client mod required.
- Arrival is always the station main exit; per-origin arrival platforms are not implemented.
- No queues, cargo, schedules, portals, custom rails, or GUI.
- Vanilla chunk-ticket propagation can load an adjacent boundary chunk. Railway itself never requests new chunks or generates during a scan.
- Compatibility is tested only on the pinned Minecraft/Fabric/Java versions and the listed server mod stack.
- The current alpha jar contains dev-gated fixture classes; remove them before beta/stable.

## Release commands

```bash
# Automated gate
./gradlew build
./gradlew runGameTest

# Local alpha artifact + checksum
./scripts/build-alpha.sh

# Direct publish (requires credentials)
MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=... ./gradlew modrinth
```

Version changes pushed to `main` use the automatic release workflow. See
[`docs/release.md`](docs/release.md).
