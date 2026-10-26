# Stage 0 technical report

Date: 2026-10-26. Status: server-side de-risking complete; client checks marked below.

## Pinned environment

| Component | Version |
|---|---|
| Minecraft Java Edition | 26.1.2 (unobfuscated, no mappings) |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.155.3+26.1.2 |
| Fabric Loom | 1.19.3 |
| Gradle | 9.8.2 |
| Java | 25 (server runtime Temurin 25.0.4.1) |

Production world flag state: `minecart_improvements` is **disabled**. The server
therefore uses `OldMinecartBehavior` (`AbstractMinecart.useExperimentalMovement`
returns false). The production mod set includes Lithium 0.24.7, C2ME
0.4.0-beta.1.0, Krypton 0.3.0, FerriteCore 9.0.0 and Clumps 26.1.2.1.

## Hooks and APIs

- Minecart entity: `net.minecraft.world.entity.vehicle.minecart.AbstractMinecart`
  and `Minecart`; behavior via `MinecartBehavior` / `OldMinecartBehavior`.
- Velocity: `Entity.setDeltaMovement`; there is no `setVelocity` in 26.1.2.
- Seating: `Entity.startRiding(Entity, boolean, boolean)`; dismount via
  `Entity.stopRiding`.
- Route readiness: `ServerLevel.isLoaded`, `isPositionEntityTicking`.
- Server lifecycle, interaction, block break and disconnect: Fabric API
  `ServerLifecycleEvents`, `ServerTickEvents`, `UseBlockCallback`,
  `PlayerBlockBreakEvents`, `ServerPlayConnectionEvents`.
- No mixin is used in stage 1.

## Verified

- Straight-line stage-1 trips in both directions, automatic boarding, controlled
  stop, exit teleport and cart removal: **confirmed by a real 26.1.2 client**.
- Shift interruption mid-trip: **confirmed by a real 26.1.2 client**.
- Automated: 84 unit tests and 9 Fabric server game tests pass. Game tests were
  rerun with Lithium, C2ME, Krypton, FerriteCore and Clumps loaded; 9/9 still pass.
- Straight-line live server: 41-rail line scanned from both stations, routes
  planned in both directions, config error/reload and restart behaviour checked.

## Speed envelope

Stage 1 intentionally uses the vanilla movement path and the configured stage-1
cap of 8 blocks/s. The ridden-cart clamp is `0.75 * getMaxSpeed`, so the raw
vanilla cap is below the 12 blocks/s target.

A temporary managed-cart-only mixin returning `0.8` from
`AbstractMinecart.getMaxSpeed(ServerLevel)` was measured under the production
mod set: the cart held **0.598 blocks/tick (≈11.96 blocks/s)** while a passenger
was mounted. The prototype was reverted; stage 3 will reintroduce it gated to
carts tagged by Railway.

## Turnout shape probe

A three-way junction was built on the dev world with a normal rail at the
junction and branches west, east and south. Setting the junction state to
`shape=south_west` (west+south) or `shape=south_east` (east+south) selected the
branch as intended. The selected shape survived:

- replacing the support block below with a redstone block and back;
- removing and restoring the east branch rail;
- powering an adjacent lever.

Ordinary redstone does not toggle normal rails, and the tested neighbor updates
did not flip the selected shape. One caveat: `setblock` reported failure on one
shape write even though the block ended in the requested state, so stage 2 must
re-read the state after every write instead of trusting the command/API result.

This makes a turnout model of "Railway writes and verifies the rail shape, then
locks the conflict zone" viable. Cart traversal through a curve/turnout and
client smoothness are still unverified.

## Not yet verified

- Curves, slopes and turnout traversal: the stage-1 validator rejects them.
  Stage 2 must implement and test them; no speed or control promise is made yet.
- Client verification under the production mod set (parity run). The modded dev
  server is available for that check.

## Known limitations / decisions

- Station boarding distance is measured to the dock; a legal button must be
  reachable from within that radius (spec radius conflict remains).
- Stage 1 stores one reservation per connected straight line; parallel lines
  are independent.
- Config/state files are never rewritten when invalid; Railway disables itself
  and requires an explicit `/rail reload` or restart.
- No chunk tickets, diagnostics command or crash recovery beyond checkpoint
  invalidation in stage 1; those remain stage 5.

## Stage 2 update

The graph scanner, directed Dijkstra, turnout shaping and route-following motion
are now implemented. Server game tests cover a three-station T network for all
six directed pairs, a curve-plus-slope trip and an external turnout change that
must abort before the switch. A client check of those routes remains pending.
