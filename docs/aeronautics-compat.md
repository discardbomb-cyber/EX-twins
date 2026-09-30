# Create Aeronautics compatibility

How EX-twins ship shields talk to Create Aeronautics airships, what was checked, and what the
development environment looks like. Everything here was verified on 2026-10-01 against the
sources and jars listed below; anything not checked is marked UNVERIFIED.

## Versions and Maven coordinates

| Mod | Version | Mod id(s) | Gradle coordinate | Repository |
| --- | --- | --- | --- | --- |
| Sable | 2.0.5+mc1.21.1 | `sable` (nests `sablecompanion` 1.6.0, `veil` 4.3.2, the Rapier natives) | `maven.modrinth:sable:2.0.5+mc1.21.1` | `https://api.modrinth.com/maven` |
| Sable Companion | 1.6.0 | `sablecompanion` | `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0` | `https://maven.ryanhcode.dev/releases` |
| Create | 6.0.10-280 | `create` (nests `flywheel` 1.0.6, `ponder` 1.0.82, Registrate) | `com.simibubi.create:create-1.21.1:6.0.10-280` | `https://maven.createmod.net` |
| Create Aeronautics | 1.3.2+mc1.21.1 | `aeronautics_bundled` (nests `aeronautics`, `simulated`, `offroad`) | `maven.modrinth:create-aeronautics:1.3.2+mc1.21.1` | Modrinth |
| Create Big Cannons | 5.11.7 | `createbigcannons` | `maven.modrinth:create-big-cannons:5.11.7` | Modrinth |
| Ritchie's Projectile Library | 2.1.2 | `ritchiesprojectilelib` | `maven.modrinth:rpl:hZ6B2Z0x` (Modrinth version id; the slug is `rpl`, and the version number is shared with the Fabric jar) | Modrinth |
| CC: Tweaked | 1.120.2 | `computercraft` | `cc.tweaked:cc-tweaked-1.21.1-forge-api:1.120.2` (compile), `cc.tweaked:cc-tweaked-1.21.1-forge:1.120.2` (runtime) | `https://maven.squiddev.cc` |

Sable requires NeoForge >= 21.1.228, Aeronautics builds against 21.1.247; the project uses
21.1.252. Sable's own Maven (`dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`) has the same jar
but its POM drags Veil, Flywheel, Ponder and Registrate from four more repositories, so the
Modrinth artifact with `transitive = false` is used instead. Aeronautics 1.3.2 exists only as the
bundled jar on Modrinth; the split artifacts on maven.ryanhcode.dev stop at 1.3.1.

Licences: Sable is PolyForm Shield 1.0.0 (calling its API is fine, no code is copied). Sable
Companion and the Aeronautics code are MIT; Aeronautics assets are all rights reserved.

## Build setup (`build.gradle`)

- `compileOnly` on Sable Companion, Sable, Create, Create Big Cannons and the CC: Tweaked API.
  Only `dev.hurtify.relicsaddon.compat.aeronautics` refers to their types.
- The `aeronauticsRuntime` configuration holds the runtime jars; the `aeronauticsClient` run
  (`./gradlew runAeronauticsClient`, game directory `run-aeronautics`) adds it to the run task's
  `classpathProvider`. It must be the JVM classpath and never MDG's per-run
  `additionalRuntimeClasspath`, or FML treats the jars as boot-layer libraries and skips them
  (the LambDynamicLights pitfall in the skill). Every other run, `build` and `runGameTestServer`
  stay free of these mods.
- `run-aeronautics/options.txt` was copied from an existing run directory
  (`onboardAccessibility:false`), otherwise the first start waits on the accessibility screen.

### Does it start?

Yes. `./gradlew runAeronauticsClient` (first start 6.5 minutes on the author's PC) reaches the
title screen with all of these loaded, from `run-aeronautics/logs/latest.log`:

```
CC: Tweaked 1.120.2 (computercraft)      Create 6.0.10 (create)
Create Aeronautics 1.3.2 (aeronautics)   Create Aeronautics 1.3.2 (aeronautics_bundled)
Create Big Cannons 5.11.7 (createbigcannons)   Create Offroad 1.3.2 (offroad)
Create Simulated 1.3.2 (simulated)       EX-twins 1.0.0-beta.1 (relics_addon)
Flywheel 1.0.6 (flywheel)                Ponder 1.0.82+mc1.21.1 (ponder)
Ritchie's Projectile Library 2.1.2       Sable 2.0.5 (sable)
Sable Companion 1.6.0 (sablecompanion)   Veil 4.3.2 (veil)
```

The Rapier natives load without any extra setup (Sable unpacks them itself). There are no
mod-loading errors. Two things in the log deserve attention:

- Veil recompiles every vanilla-style core shader through its own GLSL front end and fails on
  three of ours: `relics_addon:armageddon_volume`, `armageddon_blast` and `mana_shell`
  (`'}' : syntax error` after Veil's transformation; the shaders compile fine under plain
  NeoForge). Veil logs "Failed to recompile vanilla shader" and keeps the original program, so
  the effects still render, but this is UNVERIFIED in a world with Sable. Worth a look in the
  effects stage: the common construct in all three is a global fixed-size array
  (`float layerAt[MAX_LAYERS];`) declared with a `const int`.
- Sable prints a warning that it replaces light storage and shaders when Flywheel is present;
  Create is loaded, so it is. Nothing broke at the title screen.

## Sable API used and available (2.0.5, package `dev.ryanhcode.sable`)

A sub-level is a set of real chunks in the same `Level`, stored in a far-away plot grid
(`SubLevelContainer.DEFAULT_ORIGIN = 10000` plot units) and drawn where the ship flies. Block
positions on a ship are ordinary `BlockPos` values in the plot; `level.getBlockState`, block
entities, capabilities and NeoForge block events all work there. A `Pose3d` maps plot
coordinates to world coordinates.

### Which ship is a block on? (used by `compat/aeronautics/AeronauticsStructures`)

`SableCompanion.INSTANCE.getContaining(Level, Vec3i)` (also `(BlockEntity)`, `(Entity)`,
`(Position)`, chunk overloads) answers by chunk and returns a `SubLevelAccess`, or null when
the position is not inside any plot. With full Sable present it is a
`dev.ryanhcode.sable.sublevel.SubLevel`, whose `getPlot().getBoundingBox()` is the block-aligned,
inclusive `BoundingBox3ic` of the ship in plot coordinates (server-computed, synced to the
client). Iterating that box with `level.getBlockState` gives the ship's blocks; the structure id
is `getUniqueId()`. Client side: `getContainingClient(...)` returns a `ClientSubLevelAccess`.

`getContaining` only matches plot positions. To find ships near a world point use
`getAllIntersecting(Level, BoundingBox3dc)` (do not modify the box while iterating) or
`SubLevelContainer.getContainer(level).queryIntersecting(bounds)`.

### Transform (for the shell renderer and hit tests, later stages)

- `SubLevelAccess.logicalPose()` (this tick), `lastPose()` (previous tick),
  `ClientSubLevelAccess.renderPose(partialTick)` (lerped position, nlerped orientation).
- `Pose3dc.transformPosition(Vec3 plot)` -> world, `transformPositionInverse(Vec3 world)` ->
  plot, `transformNormal[Inverse]`, `bakeIntoMatrix(Matrix4d)` for rendering.
- `SableCompanion.INSTANCE.projectOutOfSubLevel(Level, Vec3)` is plot -> world or identity.
- `subLevel.boundingBox()` is the world AABB (`toMojang()` gives an `AABB`).
- Server side there is no frame interpolation; `lastPose().lerp(logicalPose(), t, new Pose3d())`.

### Events and hooks

- NeoForge events on `NeoForge.EVENT_BUS` (`dev.ryanhcode.sable.neoforge.event`):
  `ForgeSableSubLevelContainerReadyEvent` (per level), `ForgeSablePrePhysicsTickEvent` and
  `ForgeSablePostPhysicsTickEvent` (per physics sub-step, several per game tick).
- Assembly and disassembly: no dedicated event; register a `SubLevelObserver`
  (`onSubLevelAdded`, `onSubLevelRemoved(SubLevel, SubLevelRemovalReason)`, `tick`) through
  `container.addObserver` inside the container-ready event.
- Block changes on a ship: no public event (`LevelPlot.onBlockChange` is internal), but the
  plot is real chunks, so the usual `BlockEvent`s and `Block#onPlace/onRemove` fire at plot
  coordinates (UNVERIFIED in game). The generator rescans its structure every 200 ticks anyway.
- `BlockEntitySubLevelActor` (`sable$tick(ServerSubLevel)`, `sable$physicsTick(...)`) is a hard
  class dependency on the block entity class, so it is not used; the same for
  `BlockSubLevelAssemblyListener` on blocks. The reflection-loaded locator keeps the block entity
  free of Sable types.

### Forces, damping, velocity (for T7 / M8, later stages)

- `RigidBodyHandle.of(ServerSubLevel)`: `applyImpulseAtPoint`, `applyLinearImpulse`,
  `applyAngularImpulse`, `applyTorqueImpulse`, `addLinearAndAngularVelocity`,
  `getLinearVelocity`, `getAngularVelocity`, `teleport`.
- Queued, grouped forces show in Sable's force debug view:
  `serverSubLevel.getOrCreateQueuedForceGroup(ForceGroups.DRAG.get()).applyAndRecordPointForce(plotPoint, localForce)`,
  applied by Sable each physics tick. Custom groups go in the `sable:force_groups` registry.
- `ServerSubLevel.latestLinearVelocity` / `latestAngularVelocity` are public fields;
  `getMassTracker()` gives `MassData` (mass, inertia tensor, centre of mass).
- Reference implementations: Simulated's `EndSeaPhysics` (damping) and CBC's
  `compat/sable/SableCompat` (impulses queued by sub-level UUID, drained in the pre-physics event).
- `ServerSubLevel.getUserDataTag()` / `setUserDataTag(CompoundTag)`: per-ship persistent NBT
  (whether it survives save and reload is UNVERIFIED).

## Create Big Cannons (5.11.7, `rbasamoyai.createbigcannons`)

- Every shell is an entity: `munitions.AbstractCannonProjectile extends Projectile`, with
  big-cannon (`SolidShotProjectile`, `HEShellProjectile`, `APShellProjectile`, ...), autocannon
  (`APAutocannonProjectile`, `FlakAutocannonProjectile`, `MachineGunProjectile`) and fragment
  bursts (`CBCProjectileBurst`, for example `ShrapnelBurst`). Entity ids live under
  `createbigcannons:` (shot, he_shell, ap_shell, shrapnel_shell, ap_autocannon, flak_autocannon,
  machine_gun_bullet, ...), so the `relics_addon:shield_interceptable_projectiles` tag and the
  `shield.interceptedProjectiles` config can list them.
- Shells do not use vanilla `Projectile.onHit`; `tick()` sweeps the path with `level().clip`
  (`clipAndDamage`), penetrates or bounces off blocks and calls `entity.hurt(...)` on entities.
  `ProjectileImpactEvent` never fires for them, so the wearable interceptor's entity-tick scan is
  the right tool, and it needs a swept test because shells move several blocks per tick.
- Block damage can be stopped: `rbasamoyai.createbigcannons.events.ProjectileDamageEvent` is a
  cancellable NeoForge event with `getLevel()` and `getPos()`, posted before a shell breaks or
  penetrates a block and before shrapnel damages blocks; the position is passed through
  `CBCCompatTransformers.transformBlockPos`, which is Sable-aware.
- CBC explosions go through `CreateBigCannons.handleCustomExplosion`, which calls NeoForge's
  `EventHooks.onExplosionStart`, so `ExplosionEvent.Start` (cancellable) fires; `Detonate` should
  too (UNVERIFIED).
- Entity damage takes the normal `hurt` path, so `LivingIncomingDamageEvent` sees it.

## CC: Tweaked (1.120.2)

Register a peripheral in the mod-bus `RegisterCapabilitiesEvent`:
`event.registerBlockEntity(PeripheralCapability.get(), SHIP_DEVICE, (be, side) -> new Peripheral(be))`;
`IPeripheral` needs `getType()` and `equals(IPeripheral)`, Lua methods are public methods with
`@LuaFunction`. Keep the class behind `ModList.get().isLoaded("computercraft")`. Not wired yet
(the modes it would expose come with the upgrades stage).

## Create item ids (checked in the 6.0.10-280 jar)

`create:brass_casing`, `create:andesite_casing`, `create:precision_mechanism` (also present:
`create:copper_casing`, `create:brass_ingot`, `create:electron_tube`). The ship device recipes
use the first three behind `neoforge:mod_loaded create`, with `*_basic` recipes otherwise.

## How the mod uses all this now

- `shipshield.ShipStructures.locate` asks the airship locator first (bound by name from
  `compat.aeronautics.AeronauticsStructures` when the `sable` mod is loaded) and falls back to a
  flood fill of the connected solid blocks around the device (6-neighbourhood, config
  `shipShield.staticStructureRadius` = 32 and `shipShield.maxStructureBlocks` = 4096; barriers,
  structure voids and light blocks do not count). Terrain counts, so a generator on the ground
  reports a truncated structure; ship shields are meant for airships and free-standing builds.
- Two devices share a structure when one's block set contains the other's position. One
  generator per structure: the second one to be switched on refuses with a notice naming the
  running one; when structures merge, the later-enabled generator switches itself off.
- Generators and docks find each other and any block with an item handler capability on the
  same structure (chests, barrels, Create vaults) every 200 ticks; the console shows the counts.

## Not done / open

- Nothing has been tried on an actual assembled airship yet (no world with a ship in the dev
  run directory). The airship locator is exercised only by loading; a `WorldScenarios` scene with
  a ship comes with the shell stage.
- The Veil shader recompile failures above.
- Whether NeoForge block events fire inside plots, `getUserDataTag` persistence, and
  `ExplosionEvent.Detonate` for CBC explosions.

## Filming in the Aeronautics client

`./gradlew runAeronauticsClient -PaeroWorld=Scenario -Pscenario=ship-devices,ship-console` opens
`run-aeronautics/saves/Scenario` (copy it from another run directory first) and films the
`WorldScenarios` scenes into `run-aeronautics/screenshots`. With Sable loaded the log says
"Ship shields follow Sable airships": the airship locator bound and answered (the deck in the
scene is not an airship, so the static scan took over). The two scenes show the six blocks on
an iron deck with the RF generator switched on, and the Mana generator's console refusing to
come on beside it.
