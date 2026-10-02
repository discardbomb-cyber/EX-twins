# Aeronautics / Sable / CBC / CC:Tweaked research (2026-10-01)

All facts below come from cloned sources, live Maven metadata or the Modrinth API on 2026-10-01. Anything not checked that way is marked UNVERIFIED.

Local clones (scratchpad/src):
- `sable` = github.com/ryanhcode/sable @ main 6f2b321 (2026-09-02), version 2.0.5
- `companion` = github.com/ryanhcode/sable-companion @ main, version 1.6.0 (MIT)
- `aero` = github.com/Creators-of-Aeronautics/Simulated-Project @ main, mod_version 1.3.2 (code MIT, assets ARR)
- `cbc5117` = CreateBigCannons tag v5.11.7+mc.1.21.1; `cbc` = branch create-v6-1.21.1
- `cct` = CC-Tweaked tag v1.21.1-1.120.2
- `create.jar` = create-1.21.1-6.0.10-280.jar from maven.createmod.net

---

## 1. Versions and Maven coordinates

### Modrinth (NeoForge, game version 1.21.1; checked via `api.modrinth.com/v2/project/<slug>/version`)
| Project (slug / id) | Latest | Date | File |
|---|---|---|---|
| create (LNytGWDc) | 6.0.10+mc1.21.1 | 2026-04-21 | create-1.21.1-6.0.10.jar |
| sable (T9PomCSv) | 2.0.5+mc1.21.1 | 2026-08-18 | sable-neoforge-1.21.1-2.0.5.jar |
| create-aeronautics (oWaK0Q19) | 1.3.2+mc1.21.1 | 2026-08-29 | create-aeronautics-bundled-1.21.1-1.3.2.jar (it requires Sable and Create) |
| create-big-cannons (GWp4jCJj) | 5.11.7 | 2026-06-22 | createbigcannons-5.11.7+mc.1.21.1.jar (it requires Create and B3pb093D, which is Ritchie's Projectile Lib per the CBC build) |
| cc-tweaked (gu7yAYhd) | 1.120.2 (tagged alpha on Modrinth) | 2026-08-08 | cc-tweaked-1.21.1-forge-1.120.2.jar |

Create Aeronautics is released for NeoForge 1.21.1. It ships as one "bundled" jar (mod id `aeronautics_bundled`) that contains three mods: Simulated (`simulated`), Aeronautics (`aeronautics`) and Offroad (`offroad`). Sable is a separate required mod, and Sable embeds sable-companion.

### Gradle-usable Mavens (each checked with HTTP 200 on the jar or pom)
- **Sable**: repo `https://maven.ryanhcode.dev/releases`
  - `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5` (versions 1.2.1 through 2.0.5; the common artifact is `sable-common-1.21.1`)
  - The POM pulls in: `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:[1.6.0,)`, `foundry.veil:veil-neoforge-1.21.1:4.3.2` (only on `https://maven.blamejared.com`), `com.simibubi.create:create-1.21.1:6.0.10-280`, `net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1`, `dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6`, `com.tterrag.registrate:Registrate:MC1.21-1.3.0+67`, `dev.ryanhcode.sable:sable-sable_rapier-1.21.1:2.0.5`. Use `transitive = false` or add those repositories.
  - Sable's mods.toml requires `neoforge >= 21.1.228`. Our project uses 21.1.252, which satisfies it. Aeronautics builds against 21.1.247.
- **Sable Companion** (MIT, meant to be jar-in-jar'd, with safe no-op defaults when Sable is absent): `dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0` on the same repo. Sable's mods.toml marks `sablecompanion` newer than its bundled 1.6.0 as **incompatible**, so pin exactly 1.6.0. The README pattern is `jarJar(api("...:[1.6.0,)")) { version { prefer "1.6.0" } }`.
- **Aeronautics split artifacts** (same ryanhcode repo; the latest there is **1.3.1**, and 1.3.2 is not published there yet):
  - `dev.eriksonn.aeronautics:aeronautics-neoforge-1.21.1:1.3.1`
  - `dev.simulated_team.simulated:simulated-neoforge-1.21.1:1.3.1`
  - `dev.ryanhcode.offroad:offroad-neoforge-1.21.1:1.3.1`
  - The bundled jar is only on Modrinth Maven: `maven.modrinth:create-aeronautics:1.3.2+mc1.21.1` (repo `https://api.modrinth.com/maven`, group `maven.modrinth`). Modrinth Maven also has `maven.modrinth:sable:2.0.5+mc1.21.1`.
- **Create**: repo `https://maven.createmod.net`, `com.simibubi.create:create-1.21.1:6.0.10-280`. A `-slim` jar and `-sources` exist. The metadata `release` is 6.0.11-312, but 6.0.11 is not released on Modrinth, so stay on 6.0.10-280 to match Sable and Aeronautics.
- **Create Big Cannons**: `maven.modrinth:create-big-cannons:5.11.7` is the reliable one. Its own maven (`https://maven.realrobotix.me/createbigcannons`, `com.rbasamoyai:createbigcannons`) only lists dev builds for 1.21.1 (`5.11.7-dev+mc.1.21.1-build.346`).
- **CC:Tweaked**: repo `https://maven.squiddev.cc` (includeGroup `cc.tweaked`). Use `compileOnly "cc.tweaked:cc-tweaked-1.21.1-forge-api:1.120.2"` (or `-common-api`) and `runtimeOnly "cc.tweaked:cc-tweaked-1.21.1-forge:1.120.2"`. This comes from the CC:T README.
- CurseMaven was not needed and was not checked.

Suggested soft-dependency approach: compile against sable-companion (jar-in-jar, safe) for position queries. Declare `compileOnly` on `sable-neoforge` only for the optional features that need ServerSubLevel, forces or events. Load those classes only behind `ModList.get().isLoaded("sable")`. CBC does exactly this: `CBCModsNeoForge.SABLE.isLoaded()` guards `compat/sable/SableCompat`.

---

## 2. Sable API (2.0.5; package `dev.ryanhcode.sable`)

Licenses: Sable is PolyForm Shield 1.0.0 (calling its API is fine). Companion is MIT.

### Concepts
A sub-level is a set of real chunks in the same `Level`, stored in a far-away "plot grid" (`SubLevelContainer.DEFAULT_ORIGIN = 10000` in plot units). Block positions on a ship are ordinary `BlockPos` values in that plot area. `level.getBlockState(plotPos)`, block entities and NeoForge block events all work on the plot coordinates. A `Pose3d` maps plot coordinates to world coordinates.

### Is a position on a sub-level? Which one?
Companion `dev.ryanhcode.sable.companion.SableCompanion.INSTANCE` (a ServiceLoader, overridden by Sable's `ActiveSableCompanion`; Sable code uses `Sable.HELPER`):
- `@Nullable SubLevelAccess getContaining(Level, Vec3i | Position | ChunkPos | SectionPos | Vector3dc | int chunkX, int chunkZ | double blockX, double blockZ)`
- `getContaining(Entity)`, `getContaining(BlockEntity)`
- `boolean isInPlotGrid(Level, Vec3i | ...)`
- Client-side: `@Nullable ClientSubLevelAccess getContainingClient(Vec3i | Position | BlockEntity | ...)`
- World-space query (for example, "which ships are near this point?"): `Iterable<? extends SubLevelAccess> getAllIntersecting(Level, BoundingBox3dc bounds)`. Do not modify `bounds` while iterating.
- Entity helpers: `getTrackingSubLevel(Entity)`, `getVehicleSubLevel(Entity)`, `getTrackingOrVehicleSubLevel(Entity)`, `getEyePositionInterpolated(Entity, float)`.
- Note: `getContaining` only matches positions inside a plot. It does not match world positions that are physically near or inside a ship.

Full Sable (non-companion): `SubLevelContainer.getContainer(Level | ServerLevel | ClientLevel)` returns `SubLevelContainer`, `ServerSubLevelContainer` or `ClientSubLevelContainer`. Methods:
- `getSubLevel(UUID)`
- `getAllSubLevels()` (typed `List<ServerSubLevel>` / `List<ClientSubLevel>`)
- `queryIntersecting(BoundingBox3dc)`
- `getPlot(ChunkPos)`
- `inBounds(BlockPos)`

`ActiveSableCompanion.getContaining(...)` returns a concrete `SubLevel`, and on the client `ClientSubLevel`.

### Sub-level objects
- `SubLevelAccess` (companion): `Pose3dc logicalPose()` (current tick), `Pose3dc lastPose()` (previous tick), `BoundingBox3dc boundingBox()` (**global**/world AABB), `UUID getUniqueId()`, `@Nullable String getName()`.
- `ClientSubLevelAccess extends SubLevelAccess`: `Pose3dc renderPose()` (current frame partial tick) and `Pose3dc renderPose(float partialTick)`. `ClientSubLevel.renderPose(pt)` lerps from lastPose to logicalPose (position lerp, orientation nlerp).
- `dev.ryanhcode.sable.sublevel.SubLevel` (abstract, implements SubLevelAccess): `getLevel()`, `getPlot()`, `isRemoved()`, `boundingBox()`, `logicalPose()` (mutable `Pose3d`), `lastPose()`.
- `ServerSubLevel extends SubLevel implements PhysicsPipelineBody`:
  - public fields `latestLinearVelocity`, `latestAngularVelocity` (Vector3d)
  - `getMassTracker()` (MassData), `getTrackingPlayers()`, `getOrCreateQueuedForceGroup(ForceGroup)`
  - `getUserDataTag()` / `setUserDataTag(CompoundTag)`: a per-ship persistent NBT slot, useful for storing shield state (UNVERIFIED whether other mods also use this tag)
  - `getPlot()` returns `ServerLevelPlot`
- `ClientSubLevel`: `renderPose(float)`, `getPlot()` returns `ClientLevelPlot`, `boundingBox()` (swept bounds).
- Server-side interpolation: there is no helper for this. Use `sub.lastPose().lerp(sub.logicalPose(), frac, new Pose3d())` (`Pose3dc.lerp(Pose3dc, double, Pose3d)`).

### Bounds and iterating blocks
- `LevelPlot.getBoundingBox()` returns `BoundingBox3ic`: the block-aligned, inclusive box in **plot block coordinates** (`minX()..maxZ()` are ints). It is computed on the server and synced to the client (`ClientboundChangeBoundsSubLevelPacket`, `ClientboundStartTrackingSubLevelPacket` both call `plot.setBoundingBox`).
- `LevelPlot.getLoadedChunks()` returns `Collection<PlotChunkHolder>`. `PlotChunkHolder.getChunk()` returns a `LevelChunk`, and `getBoundingBox()` gives chunk-local bounds.
- Other `LevelPlot` members: `getChunkMin()`, `getChunkMax()`, `contains(ChunkPos|Vec3|Vector3dc)`, `getCenterBlock()`, `getSubLevel()`.
- Iterate the blocks with `BlockPos.betweenClosed(min, max)` over the plot bounds and call `level.getBlockState(pos)`.
- World AABB: `subLevel.boundingBox()` (global). To convert plot bounds to world: `BoundingBox3dc.transform(Pose3dc, BoundingBox3d dest)`. `toMojang()` gives an `AABB`.

### World and local (plot) conversion (`dev.ryanhcode.sable.companion.math.Pose3dc`)
- `position()`, `orientation()` (Quaterniondc), `rotationPoint()`, `scale()`
- `Vec3 transformPosition(Vec3 plotPos)`: plot to world
- `Vec3 transformPositionInverse(Vec3 worldPos)`: world to plot
- `transformNormal` / `transformNormalInverse`, with Vec3 and JOML overloads
- `Matrix4d bakeIntoMatrix(Matrix4d dest)`: use this for rendering
- Shortcuts:
  - `SableCompanion.INSTANCE.projectOutOfSubLevel(Level, Vec3)`: plot to world, or identity if the position is not on a ship
  - `distanceSquaredWithSubLevels(Level, Position a, Position b)`
  - `getVelocity(Level, Vec3)`
- JOML/Mojang conversion helpers: `JOMLConversion.toJOML(Vec3)` and `JOMLConversion.toMojang(Vector3dc)`.

### Events
All of these are NeoForge `Event`s posted on `NeoForge.EVENT_BUS` (package `dev.ryanhcode.sable.neoforge.event`):
- `ForgeSableSubLevelContainerReadyEvent`: `getLevel()`, `getContainer()`
- `ForgeSablePrePhysicsTickEvent`: `getPhysicsSystem()` (a `SubLevelPhysicsSystem`), `getTimeStep()`. Fired once per physics sub-step, which can be several times per game tick. This is the place to apply forces.
- `ForgeSablePostPhysicsTickEvent`: same getters.

Other hooks:
- **Assembly and disassembly (sub-level added or removed)**: there is no dedicated event. Call `container.addObserver(SubLevelObserver)` inside the container-ready event. `SubLevelObserver` has `onSubLevelAdded(SubLevel)`, `onSubLevelRemoved(SubLevel, SubLevelRemovalReason)` and `tick(SubLevelContainer)`. `SubLevelRemovalReason` is `UNLOADED` or `REMOVED`.
- **Block moved during assembly**: a Block subclass can implement `dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener`: `beforeMove(ServerLevel origin, ServerLevel result, BlockState, BlockPos oldPos, BlockPos newPos)` and `afterMove(...)`. This is a hard class dependency; see the note below.
- **Block changes on a ship**: there is no public event. `LevelPlot.onBlockChange(BlockPos, BlockState)` and `SubLevelPhysicsSystem.handleBlockChange` are internal. Because plots are real chunks, the normal NeoForge `BlockEvent`s and `Block#onPlace` / `onRemove` fire at plot coordinates (inferred from the design; UNVERIFIED in-game).
- **Per-block-entity hook**: a `BlockEntity` can implement `dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor`. It has `sable$tick(ServerSubLevel)` (each game tick while on a ship) and `sable$physicsTick(ServerSubLevel, RigidBodyHandle, double timeStep)`. The plot registers the block entity automatically through `instanceof`. This creates a hard class dependency: our BE class would fail to load without Sable. Prefer the event-based approach, or keep a separate BE class for this.
- Assembly API: `SubLevelAssemblyHelper.assembleBlocks(ServerLevel, BlockPos anchor, Iterable<BlockPos>, BoundingBox3ic)` returns a `ServerSubLevel`. Also `gatherConnectedBlocks(...)`.

### Forces, damping and velocity (server only)
- `dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle`:
  - `static @Nullable of(ServerSubLevel)`, or `physicsSystem.getPhysicsHandle(ServerSubLevel)` inside a physics tick
  - `applyImpulseAtPoint(Vec3|Vector3dc plotPos, force)`: the javadoc says the position is "inside the plot" and the force is a local impulse [N]
  - `applyLinearImpulse(local)`, `applyAngularImpulse(local)`, `applyTorqueImpulse(local)`, `applyLinearAndAngularImpulse(impulse, torque[, wakeUp])`
  - `addLinearAndAngularVelocity(Vector3dc lin, Vector3dc ang)`
  - `getLinearVelocity(Vector3d dest)` (global m/s), `getAngularVelocity(dest)`
  - `teleport(Vector3dc pos, Quaterniondc orient)`, `isValid()`
- Queued, grouped forces (these show in the force debug view): `serverSubLevel.getOrCreateQueuedForceGroup(ForceGroups.DRAG.get())` returns a `QueuedForceGroup`. It has `applyAndRecordPointForce(Vector3dc plotPoint, Vector3dc localForce)` and `getForceTotal().applyLinearAndAngularImpulse(...)`. Sable applies all queued groups each physics tick (`ServerSubLevel.applyQueuedForces`).
  - Built-in groups: `GRAVITY`, `DRAG`, `LEVITATION`, `BALLOON_LIFT`, `PROPULSION`, `LIFT`, `MAGNETIC_FORCE`.
  - Custom groups go in registry `ForceGroups.REGISTRY_KEY` (`sable:force_groups`); `ForceGroup` is a record of `(Component name, Component description, int color, boolean defaultDisplayed)`.
- Reference damping implementation: Simulated's `dev/simulated_team/simulated/content/end_sea/EndSeaPhysics.java`. On each physics tick it does:
  - `handle.getLinearVelocity(tmp).mul(-dt*k)`
  - `pose.transformNormalInverse(...)`, then multiply by mass
  - `dragGroup.getForceTotal().applyLinearAndAngularImpulse(lin, ang)`
- Reference soft-dep impulse from projectile hits: CBC `rbasamoyai/createbigcannons/compat/sable/SableCompat.java`. It queues forces keyed by sub-level UUID, then drains them in a `ForgeSablePrePhysicsTickEvent` listener via `SubLevelContainer.getContainer(level).getSubLevel(uuid)` and `getOrCreateQueuedForceGroup(...).applyAndRecordPointForce(...)`.
- Mass: `serverSubLevel.getMassTracker()` gives `MassData`, which has `getMass()`, `getInertiaTensor()` and a center of mass (used in EndSeaPhysics).

---

## 3. Create Big Cannons (5.11.7, package `rbasamoyai.createbigcannons`)

- Every shell is an **entity**. The base class is `munitions.AbstractCannonProjectile extends net.minecraft.world.entity.projectile.Projectile`.
  - `munitions.big_cannon.AbstractBigCannonProjectile` subclasses: `SolidShotProjectile`, `APShotProjectile`, `HEShellProjectile`, `APShellProjectile`, `ShrapnelShellProjectile`, `FluidShellProjectile`, `SmokeShellProjectile`, `MortarStoneProjectile`, `DropMortarShellProjectile`, `GrapeshotBagProjectile`, `TrafficConeProjectile`, and more. Fuzed ones extend `FuzedBigCannonProjectile`.
  - `munitions.autocannon.AbstractAutocannonProjectile` subclasses: `APAutocannonProjectile`, `FlakAutocannonProjectile`, `MachineGunProjectile`.
  - Fragment bursts: `munitions.fragment_burst.CBCProjectileBurst extends rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst`, for example `ShrapnelBurst`. These are also entities.
  - Entity ids (`createbigcannons:`): shot, he_shell, shrapnel_shell, bag_of_grapeshot, ap_shot, traffic_cone, ap_shell, fluid_shell, smoke_shell, mortar_stone, drop_mortar_shell, shrapnel_burst, flak_burst, grapeshot_burst, fluid_blob_burst, ap_autocannon, flak_autocannon, machine_gun_bullet, primed_propellant.
- **How a shell moves and hits**: `tick()` calls `clipAndDamage()`. It sweeps its own path with `level().clip(...)` (it does not use vanilla `Projectile.onHit`, and no call to `onProjectileImpact`/`ProjectileImpactEvent` was found in CBC). For blocks it calls `calculateBlockPenetration(...)` (penetrate, stop or bounce). For entities it calls `onHitEntity(Entity, ProjectileContext)`, which does `entity.hurt(indirectArtilleryFire(...), damage)` and knockback. Queued impact explosions are `ImpactExplosion`s. Fuzed shells detonate as `ShellExplosion`, `ShrapnelExplosion`, `FlakExplosion`, `FluidExplosion` and so on.
- **Interception hooks (no mixin needed)**:
  1. Scan for `AbstractCannonProjectile` entities (for example `level.getEntitiesOfClass(AbstractCannonProjectile.class, shieldAabb)`) and `discard()` or deflect them (`setDeltaMovement`) before they reach the hull. Shells are fast, so a swept test is needed.
  2. `rbasamoyai.createbigcannons.events.ProjectileDamageEvent` is a **cancellable NeoForge event** (`ICancellableEvent`) with `getLevel()` and `getPos()`. It is posted by `ProjectileDamageHooks.canDamageTerrain` before a shell breaks or penetrates a block and before shrapnel damages blocks. The pos is passed through `CBCCompatTransformers.transformBlockPos`, which is Sable-aware. Cancelling it makes the terrain unbreakable to that hit.
  3. CBC custom explosions go through `CreateBigCannons.handleCustomExplosion`, which calls NeoForge `EventHooks.onExplosionStart`, so `ExplosionEvent.Start` (cancellable) fires. `ExplosionEvent.Detonate` should fire too, since explosions call vanilla `explode()` (UNVERIFIED).
  4. Entity damage uses the normal `hurt` path, so `LivingIncomingDamageEvent` catches it.

---

## 4. CC:Tweaked peripheral (NeoForge 1.21.1, 1.120.2)
- Capability: `dan200.computercraft.api.peripheral.PeripheralCapability.get()` returns `BlockCapability<IPeripheral, Direction>`, with id `computercraft:peripheral`. It lives in the forge-api artifact. The javadoc says to use it for registration only, not for querying.
- Registration: in the mod-bus `RegisterCapabilitiesEvent`, call `event.registerBlockEntity(PeripheralCapability.get(), MY_BE_TYPE, (be, side) -> new MyPeripheral(be))`.
- `dan200.computercraft.api.peripheral.IPeripheral`:
  - required: `String getType()`, `boolean equals(@Nullable IPeripheral other)`
  - optional: `getAdditionalTypes()`, `attach(IComputerAccess)`, `detach(IComputerAccess)`, `getTarget()`
  - Lua methods are public methods annotated with `@dan200.computercraft.api.lua.LuaFunction`
- Maven: see section 1 (`cc.tweaked:cc-tweaked-1.21.1-forge-api:1.120.2`, repo maven.squiddev.cc). The mod id is `computercraft`. Keep the peripheral class behind `ModList.isLoaded("computercraft")`.

---

## 5. Create item ids (checked in create-1.21.1-6.0.10-280.jar, models/item and en_us.json)
- `create:brass_casing` (block item, "Brass Casing")
- `create:andesite_casing` (block item, "Andesite Casing")
- `create:precision_mechanism` (item, "Precision Mechanism")
- Also present: `create:copper_casing`, `create:brass_ingot`, `create:electron_tube`, `create:incomplete_precision_mechanism`.

## Unverified / caveats
- In-game behaviour of NeoForge BlockEvents inside plots.
- Whether the Aeronautics 1.3.2 split artifacts will appear on maven.ryanhcode.dev (right now only 1.3.1 is there; the 1.3.2 bundled jar is on Modrinth Maven).
- Whether `ServerSubLevel.getUserDataTag` persists across save and reload (the name suggests it does; not traced).
- Running Sable in a ModDevGradle dev environment (it needs Veil and the Rapier natives from `sable-sable_rapier`) was not tested.
