# Release pipeline

Railway publishes Fabric server jars through Gradle Minotaur from GitHub Actions.
The release flow is versioned in `gradle.properties`; no credentials live in the
repository.

## Artifacts

| Artifact | Path |
|---|---|
| Mod icon, 512×512 | `src/main/resources/assets/railway/icon.png` |
| Alpha banner, 1280×720 | `docs/assets/railway-alpha-banner.png` |
| Vector sources | `src/main/resources/assets/railway/icon.svg`, `docs/assets/railway-alpha-banner.svg` |
| Extended Modrinth body | `docs/modrinth-description.md` |
| Release jar | `build/libs/railway-<version>.jar` |
| Checksum | `dist/railway-<version>.jar.sha256` |

Modrinth's project icon is the PNG in the jar. Upload the banner as the first
gallery image.

## Modrinth project settings

Create the project once with:

- slug/id: `railway` (or use the actual project ID);
- project type: mod;
- loader: Fabric;
- Minecraft: `26.1.2`;
- environment: **server_only**;
- license: MIT;
- icon: `src/main/resources/assets/railway/icon.png`;
- gallery: `docs/assets/railway-alpha-banner.png`.

The Gradle task publishes versions. The project body is sourced from
`docs/modrinth-description.md`; run `./gradlew modrinthSyncBody` only when you
intend to overwrite the live Modrinth body. Upload the icon, banner, and gallery
through the Modrinth project settings.

## Local alpha build

```bash
./scripts/build-alpha.sh
```

That runs unit tests, GameTests, builds the jar, checks the icon is inside the
jar, copies it to `dist/`, and writes a SHA-256 checksum.

Publish directly only after the build is green:

```bash
MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=... ./gradlew modrinth
```

Useful local dry checks:

```bash
./gradlew build
./gradlew runGameTest
```

`modrinth` uploads the `jar` task output with:

- version number from `gradle.properties`;
- version type `alpha` by default;
- game version `26.1.2`;
- loader `fabric`;
- environment `server_only`;
- required dependency Fabric API;
- only the matching version section from `CHANGELOG.md`.

Override the channel for a later beta/release:

```bash
./gradlew modrinth -PmodrinthVersionType=beta
```

## GitHub Actions

### CI

`.github/workflows/ci.yml` runs on pushes and pull requests:

- Java 25;
- `./gradlew build`;
- `./gradlew runGameTest`.

### Release

`.github/workflows/release.yml` runs automatically when `gradle.properties`
changes on `main`. Changing `version=` is the only required action:

```properties
version=0.1.0-alpha.6
```

Commit and push that change. The workflow:

1. reads the version from `gradle.properties`;
2. derives the channel from the suffix: `-alpha` → alpha, `-beta` → beta,
   otherwise release;
3. checks whether the Modrinth version already exists;
4. checks whether tag `v<version>` already exists;
5. builds and runs GameTests only when the Modrinth version is new;
6. publishes to Modrinth;
7. creates and pushes annotated tag `v<version>` only after publishing succeeds;
8. uploads the jar as a workflow artifact.

The order is deliberate. If publishing succeeds but the tag push fails, re-run
the workflow: it detects the existing Modrinth version, skips the upload, and
creates the missing tag. If the tag exists but the Modrinth version does not, the
workflow publishes it.

### Per-version changelogs

Modrinth displays a version changelog, not the whole repository history. Minotaur
extracts only the section matching `version=` from `CHANGELOG.md`; the full
history stays in the repository.

To repair already-published versions that contain the full history, run:

```bash
MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=... python3 scripts/update-modrinth-changelogs.py --apply
```

The script dry-runs by default. The token needs `VERSION_WRITE` in addition to
`CREATE_VERSION`. The `fix-modrinth-changelogs` workflow performs the same repair
using the repository secret and variable.

`workflow_dispatch` is available for retries. Do not create the release tag
manually unless recovery requires it.

Configure the repository once:

| Name | Kind | Value |
|---|---|---|
| `MODRINTH_TOKEN` | Actions secret | Modrinth token with `CREATE_VERSION` |
| `MODRINTH_PROJECT_ID` | Actions variable | Modrinth project ID or slug |

The workflow requires both. It does not create a GitHub release.

## Version bump

1. Change `version=` in `gradle.properties`.
2. Recommend updating `CHANGELOG.md` in the same commit.
3. Commit and push to `main`; the release workflow handles the rest.

Never reuse an existing Modrinth version number.

## Production cleanup

Before beta/stable:

- remove the dev-only fixture package and its single `RailwayMod` registration;
- remove the fixture Gradle run configuration and scripts;
- delete `docs/fixtures.md` or move it out of the published docs;
- confirm the release jar contains no `dev/nikhcevov/railway/fixture/**` classes;
- rerun the acceptance matrix on the final jar.

The alpha may retain the dev-gated fixture classes; they are not registered in a
production environment, but they should not ship in a stable release.
