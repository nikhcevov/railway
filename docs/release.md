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
- environment: **server**;
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
- environment `server`;
- required dependency Fabric API;
- changelog from `CHANGELOG.md`.

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

`.github/workflows/release.yml` supports:

- manual `workflow_dispatch` with a version and channel;
- a `v*` tag.

Configure the repository:

| Name | Kind | Value |
|---|---|---|
| `MODRINTH_TOKEN` | Actions secret | Modrinth token with `CREATE_VERSION` |
| `MODRINTH_PROJECT_ID` | Actions variable | Modrinth project ID or slug |

Then run the workflow manually:

```text
version: 0.1.0-alpha.1
channel: alpha
```

or push a tag:

```bash
git tag v0.1.0-alpha.1
git push origin v0.1.0-alpha.1
```

The workflow checks out, builds, runs GameTests, publishes to Modrinth, and
uploads the jar as a workflow artifact. It does not create a GitHub release.

## Version bump

1. Update `version=` in `gradle.properties`.
2. Add a `CHANGELOG.md` section.
3. Update the README version/installation names.
4. Run `./scripts/build-alpha.sh`.
5. Publish a new Modrinth version; never reuse an existing Modrinth version.

## Production cleanup

Before beta/stable:

- remove the dev-only fixture package and its single `RailwayMod` registration;
- remove the fixture Gradle run configuration and scripts;
- delete `docs/fixtures.md` or move it out of the published docs;
- confirm the release jar contains no `dev/nikhcevov/railway/fixture/**` classes;
- rerun the acceptance matrix on the final jar.

The alpha may retain the dev-gated fixture classes; they are not registered in a
production environment, but they should not ship in a stable release.
