# Configuration

Railway reads `config/railway.json` from the Fabric server config directory and
stores world state separately under `<world>/railway/state.json`.

## Loading rules

- Missing config: defaults are written automatically.
- Missing keys: the default value is used.
- Unknown keys: warnings; ignored.
- Invalid values or `configVersion`: Railway disables itself and reports errors.
  The invalid file is **not** rewritten.
- `/rail reload` validates and applies a new config to new trips. Active trips keep
  their snapshot. An invalid reload leaves the previous config active.
- A config load failure does not stop the Minecraft server.

`configVersion` is currently `1`. The `builders` key is additive and optional, so
no version bump was required.

## Keys

| Key | Default | Valid range | Meaning |
|---|---:|---:|---|
| `configVersion` | 1 | exact 1 | Config schema version |
| `boardingRadius` | 4 | 1..16 | Legacy/command boarding distance; sign units use horizontal 4 / vertical 3 |
| `boardingDelayTicks` | 10 | 0..200 | Delay after pressing before departure |
| `playerCooldownTicks` | 20 | 0..1,200 | Per-player button cooldown |
| `maxActiveTrips` | 8 | 1..64 | Concurrent active trips |
| `maxSpeed` | 12.0 | 1.0..30.0 | Target speed; effective value is capped at 12 |
| `acceleration` | 4.0 | 0.1..20.0 | Blocks/s² |
| `braking` | 6.0 | 0.1..20.0 | Blocks/s² |
| `safetyMargin` | 2.0 | 0.0..10.0 | Extra braking distance/space |
| `maxRailsPerScan` | 50,000 | 1..1,000,000 | Scan budget |
| `scanRailsPerTick` | 250 | 1..10,000 | Scan work per tick |
| `scanTimeBudgetMs` | 2 | 1..50 | Scan time budget per tick |
| `maxRouteLength` | 10,000 | 1..1,000,000 | Maximum planned route length |
| `maxManagedChunkTickets` | 128 | 0..4,096 | Local ticket budget; 0 disables tickets |
| `maxTripTicks` | 36,000 | 20..1,000,000 | Hard trip timeout |
| `builders` | `[]` | array of UUIDs | Extra sign builders; OP2 is always allowed |

### Builders

```json
{
  "builders": [
    "00000000-0000-0000-0000-000000000000",
    "11111111-2222-3333-4444-555555555555"
  ]
}
```

- UUID strings only; invalid/blank UUIDs or case-insensitive duplicates are
  rejected.
- OP level 2 can always register signs regardless of this list.
- The list grants **service-sign registration**, not all OP2 commands.
- Changes after `/rail reload` apply immediately to new sign saves.

## Example

```json
{
  "configVersion": 1,
  "boardingRadius": 4,
  "boardingDelayTicks": 10,
  "playerCooldownTicks": 20,
  "maxActiveTrips": 8,
  "maxSpeed": 12.0,
  "acceleration": 4.0,
  "braking": 6.0,
  "safetyMargin": 2.0,
  "maxRailsPerScan": 50000,
  "scanRailsPerTick": 250,
  "scanTimeBudgetMs": 2,
  "maxRouteLength": 10000,
  "maxManagedChunkTickets": 128,
  "maxTripTicks": 36000,
  "builders": []
}
```

## Tuning notes

- `maxActiveTrips` protects the server from too many carts. Route reservations
  still prevent conflicting trips.
- `maxManagedChunkTickets` is a global ticket budget shared by trip windows,
  recovery checkpoints and scan frontiers, not a route-length limit. Scan tickets
  are additionally capped at 32. Larger values allow more parallel windows. With
  `0`, scans cannot load unloaded frontier chunks and fail with
  `CHUNKS_NOT_READY`; startup and other stations keep running, and the automatic
  scan retry waits 10 seconds (200 ticks, not configurable) after a failure.
- `maxTripTicks` is a safety net. A cart that fails to finish within this many
  ticks aborts.
- `scanRailsPerTick` / `scanTimeBudgetMs` control scan latency versus tick load.
- `maxRouteLength` is compared against the graph path length before spawning a
  cart.
- Config changes do not retroactively alter active trips.

## State

State lives at:

```text
<world>/railway/state.json
```

Current schema: **4**. It includes:

- stations (main dock, exit, display name, enabled, revision);
- provisional destinations;
- bindings, including local `sourceDock`;
- sign markers, owning station, `m` main flag;
- active trip checkpoints with dimension/position;
- rolling revision.

Before a schema migration, Railway copies the old file to `state.vN.bak`. Do not
edit state while the server is running. A state load failure disables Railway and
preserves the invalid file for diagnosis.

## Permissions and compatibility

- Command permissions and builder allowlist are separate.
- The mod is server-side; no client config.
- Tested with the production mod stack: C2ME, Lithium, Krypton, FerriteCore,
  Clumps. Other mod stacks must be verified.
- Designed for the pinned Minecraft/Fabric/Java versions. Other versions may not
  have the same ticket, sign, or movement APIs.

## Removal

1. Close active trips and stop the server.
2. Back up the world.
3. Remove the Railway jar and its config if desired.
4. Start the server.

Signs, rails, buttons, platforms, and service-cart remnants are ordinary vanilla
entities/blocks. World state under `railway/` is ignored by vanilla. See
[operations](operations.md).
