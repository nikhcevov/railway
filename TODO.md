# Railway production TODO

Last updated: 2026-11-08. Current alpha: `0.1.0-alpha.8`.

## Verified

- [x] Structured P0/P1 logging: `EventLog` key/value events, `Failure` cause and
  `fromThrowable`; scan, trip, sign, command, state and startup events carry
  code, non-empty detail, position, ids, tick and ref. Rejected sign edits are
  logged at WARN (previously DEBUG-only), command executions log actor/input,
  and aborts log the full failure detail plus a stack trace.
- [x] Failure journal (P2): stable per-failure `ref` shared by log, chat and
  journal; `<world>/railway/failures.jsonl` (256-entry ring, 1 MiB rotation);
  `/rail diagnostics failures [count]` and `/rail diagnostics ref <ref>`.
- [x] `/rail network rescan`: drops all cached graphs and re-analyzes every
  component once, one scan at a time; rejects while a startup/rescan queue is
  running.

- [x] Unit tests: 272 passing.
- [x] GameTests: 67 passing.
- [x] Per-origin arrival platforms (`/rail station arrival set/remove`) with main-arrival fallback; schema 5 migration; GameTest coverage for two origins and fallback.
- [x] Dev fixtures live in `src/dev`; `jar`/`sourcesJar` exclude them and CI plus `build-alpha.sh` assert absence.
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
- [x] Alpha.6 published to Modrinth and tagged `v0.1.0-alpha.6`.
- [x] Alpha.8 logging/journal/rescan release published by the automated workflow on 2026-11-08 and tagged `v0.1.0-alpha.8`.
- [x] Standalone server booted from downloaded Modrinth jars (alpha.1), then updated through alpha.5 local builds.

## Modrinth page — finish manually

- [ ] Confirm the project icon is `src/main/resources/assets/railway/icon.png`.
- [ ] Confirm `docs/assets/railway-alpha-banner.png` is in the gallery.
- [ ] Paste or sync `docs/modrinth-description.md` as the project body.
- [ ] Confirm the live version is type `alpha`, loader `fabric`, environment `server_only`, game `26.1.2`.
- [ ] Download the published alpha.8 jar and start it on a fresh copy server.
- [ ] Check the gallery screenshots/video.

## Remaining P0 checks

- [ ] **Non-OP builder:** builder UUID without OP registers a sign, saves a destination, and starts a trip; an unlisted player is rejected.
- [ ] **Concurrent trips and turnouts:** several players at once, shared junction conflicts, abort, disconnect, and reconnect; no duplicate carts or stuck reservations.
- [x] **Corrupt-state drill:** truncated `state.json` disabled Railway without overwriting it; the missing-backup path left the file untouched; schema 4 → 5 created a byte-identical `state.v4.bak` and migrated stations to schema 5 with empty arrival platforms.
- [ ] **Failed save/disk conditions:** simulate an unwritable state directory or a save error and confirm Railway logs the failure without corrupting the previous file.
- [ ] **Final removal:** remove Railway from a copied world and load it with vanilla; no orphan entities or required custom blocks.
- [ ] **Copied real-world test:** install alpha.8 on a copy of the production world, build one low-risk two-station route, and run passenger/return/restart cycles.
- [ ] **Soak test:** leave the copied-world server running for 24 hours with several trips; watch TPS, logs, tickets, and diagnostics.

## Before beta/stable

- [ ] Wide-frontier live fixture, if a real non-synthetic connected geometry can be built; current coverage is unit/GameTest only.
- [x] Exclude the dev-only fixture package and `/railfixture` registration from the production jar (dev source set + artifact assertions).
- [ ] Re-run the full acceptance matrix on the final jar and pinned dependency versions.
- [ ] Confirm production configuration limits: `maxActiveTrips`, `maxManagedChunkTickets`, `maxRailsPerScan`, `maxTripTicks`, `maxRouteLength`.
- [ ] Document the final compatibility matrix and no-generation caveat.
- [ ] Build the beta/stable artifact, changelog, tag, and Modrinth version.

## Known limitations to keep in the project body

- Server-side only; no client mod required.
- Per-origin arrival platforms are command-configured (`/rail station arrival set/remove`); there is no sign flag. Without a platform, arrivals use the destination main exit.
- No queues, cargo, schedules, portals, custom rails, or GUI.
- Vanilla chunk-ticket propagation can load an adjacent boundary chunk. Railway itself never requests new chunks or generates during a scan.
- Compatibility is tested only on the pinned Minecraft/Fabric/Java versions and the listed server mod stack.

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
