# Railway production TODO

Status as of 2026-10-28. Keep this file updated when a gate is passed.

## Verified

- [x] Automation: automatic startup scans, one scan per connected component.
- [x] Scan frontier wider than 32 chunks is processed in bounded subsets.
- [x] Unit tests: 245 passing.
- [x] GameTests: 59 passing.
- [x] Long-route fixture build/verify after restart: 513 rails, 512-block route both directions, destination unloaded before scan, scan auto-loads it, no recovery debris.
- [x] Mid-trip `kill -9`: recovery closes the checkpoint, removes the managed cart, and does not resume the trip.
- [x] Clean long-route arrival/return tested by the user on the dedicated fixture server.
- [x] Alpha icon and 16:9 release banner generated from committed SVG sources.
- [x] Alpha version `0.1.0-alpha.3` and changelog added.
- [x] Local `./gradlew modrinth` publishing task and automatic version/tag release workflow added.

## Alpha release

- [ ] Create the Modrinth project (or confirm the project slug/ID).
- [ ] Upload `src/main/resources/assets/railway/icon.png` as the project icon.
- [ ] Upload `docs/assets/railway-alpha-banner.png` as the first gallery image.
- [ ] Paste or sync `docs/modrinth-description.md` as the Modrinth project body.
- [ ] Set GitHub repository variable `MODRINTH_PROJECT_ID`.
- [ ] Set GitHub Actions secret `MODRINTH_TOKEN` with `CREATE_VERSION`.
- [x] Build the alpha artifact: `./scripts/build-alpha.sh` (local artifact and checksum produced).
- [x] Verify the jar contains `fabric.mod.json`, `assets/railway/icon.png`, and no accidental world/state files.
- [ ] Push the `gradle.properties` version change to `main`; the workflow will publish and tag `v0.1.0-alpha.3`.
- [ ] Confirm the Modrinth version type is `alpha`, loader `fabric`, environment `server`, game version `26.1.2`.
- [ ] Check the published jar downloads and starts on a copy world.

## Before beta/stable

- [ ] Non-OP builder acceptance (currently skipped): builder UUID without OP registers a sign and starts a trip; non-builder is rejected.
- [ ] Concurrent trips and turnouts: several players at once, shared junction conflicts, abort and disconnect behavior, no duplicate carts or stuck reservations.
- [ ] Backup/corrupt-state drill: corrupt `state.json`, stale schema, missing backup, invalid config, disk/write failure behavior.
- [ ] Final removal test with the release jar: vanilla server loads a copied world with Railway data but without the mod.
- [ ] Wide-frontier live fixture, if a non-synthetic connected geometry is found; current coverage is GameTest/unit only.
- [ ] Remove or exclude the dev-only fixture package and `/railfixture` registration from the production jar. Current alpha jar keeps it dev-gated; docs/fixtures.md lists removal steps.
- [ ] Re-run the full acceptance matrix on the final jar and pinned dependency versions.

## Known limitations to document in the release body

- Server-side only; no client mod required.
- Arrival is at the station main exit; per-origin arrival platforms are not implemented.
- No queues, cargo, schedules, portals, custom rails, or GUI.
- Vanilla chunk-ticket propagation can load an adjacent boundary chunk; Railway itself never requests new chunks or generates during a scan.
- Compatibility is tested only on the pinned Minecraft/Fabric/Java versions and the listed server mod stack.

## Release commands

```bash
# Check
./gradlew build
./gradlew runGameTest

# Build local alpha artifact + checksum
./scripts/build-alpha.sh

# Publish directly (requires credentials)
MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=... ./gradlew modrinth
```

See `docs/release.md` for the pipeline and asset details.
