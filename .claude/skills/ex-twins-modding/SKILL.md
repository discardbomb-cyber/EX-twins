---
name: ex-twins-modding
description: Working on the EX-twins NeoForge 1.21.1 mod (shields, hives, batteries, device console, Photon effects, Curios). Use for any change to this repo - building, running the client or GameTests, adding items/recipes/lang, touching shield or hive logic, rendering, sounds or models - and when analysing gameplay videos the user sends.
---

# EX-twins modding

Standalone NeoForge 1.21.1 mod (mod id `relics_addon`, package `dev.hurtify.relicsaddon`). It no
longer depends on Relics. Required at runtime: Curios 9.5.1, Photon 2.2.7, LDLib2 2.2.41,
KilaGraph (all from Maven, see `build.gradle`). NeoForge must be >= 21.1.216 (LDLib2 requires it).

## Build, run, test

Git Bash, from the repo root:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build              # compile + unit checks (verify* tasks) + release-jar content check
./gradlew runGameTestServer  # GameTests; must end with "All N required tests passed"
./gradlew runClient          # dev client (slow on the user's PC: ~2-10 min to start)
```

- Result of GameTests: `run-gametest/logs/latest.log` (`failed at ... <message>`).
- Client log: redirect `runClient` output to a file; grep `ERROR|Exception|Failed to decode`.
- Never kill a running client the user is playing in (world does not save); ask them to quit.
- `timeout` on gradle invocations: GameTests take ~1-2 min, a clean build ~2 min.

### Tests to extend when changing behaviour

- **Plain checks** in `src/test/java` run by `verify*` JavaExec tasks wired into `check`
  (e.g. `ShieldRippleCheck`, `DevicePowerCheck`, `NetworkCodecCheck`). Pure math, codecs.
- **GameTests** in `src/main/java/.../gametest` (excluded from the release jar):
  - `DeviceTestSupport.player(helper[, relativePos])` - real survival `ServerPlayer`, added to the
    level, two Curios charm slots; `equip(helper, player, role, slot)` - switched-on, fully charged.
  - `ShieldDefenseGameTests` (damage, skulls, effects, fire immunity, battery, mob barrier),
    `DeviceGameTests` (levels/XP cap, upgrade ranks, one hive, Twins cost split, FE, console bay).
  - Templates: `test_room` (5x4x5) and `field_arena` (13x7x13, has fixtures - clear blocks with
    `helper.setBlock(..., AIR)` when a test needs open floor).
- Simulate events directly: `player.hurt(player.damageSources().X(), amount)`, spawn entities with
  `helper.getLevel().addFreshEntity`, call handlers (`ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player))`),
  `helper.runAfterDelay(ticks, ...)` for timed checks.

## Architecture map

- Staged hexagonal migration: pure rules, records and math live in `domain/`, hive code in
  `domain/hive/`; Minecraft conversions in `adapter/out/world/McVectors`, codecs in
  `adapter/out/persistence/`. Keep release features when moving code; `build` checks these
  boundaries and the reconciled resource, codec and geometry contracts.


- Items: `relic/AutonomousRelicItem` (shields), `relic/HiveRelicItem` (hives, only one wearable -
  Curios `canEquip`), `relic/ComponentItem` (crafting parts), `registry/ModItems`.
- Per-item state = data components (`registry/ModDataComponents`): `DEVICE_PROGRESSION` (level, XP,
  points, 3 module bits, upgrade ranks at `bit*2`), `DEVICE_ENERGY` (RF in FE, mana in points,
  battery switches, mana source), `SHIELD_STACK_STATE`, `HIVE_STACK_STATE`, `INSTANCE_ID`.
- Power: `power/DevicePower` (costs, `powered`, `drain`, Twins split), `power/ManaSources`
  (Botania / Ars Nouveau / Iron's Spells by reflection, player XP), FE capability `DeviceEnergyStorage`.
  `RelicRuntime.canOperate` = enabled && powered.
- Shield server: `server/ShieldController` (absorb; fully absorbed hits are CANCELLED; own
  10-tick hit immunity; damage tag `relics_addon:shield_passes` lists what passes),
  `ShieldProjectileInterceptor`, `ShieldBarrier` (pushes hostile mobs out). Server config lists
  `shield.passingDamageTypes/absorbedDamageTypes/interceptedProjectiles/ignoredProjectiles/keptEffects`
  are matched through cached `RegistryFilter`s in `AddonConfig`; `ConfigValue.set` fires no event,
  so a filter re-parses whenever the list object changes (GameTests swap lists and restore them).
- Leveling: `RelicRuntime.experienceToNext` (60..2040), `ExperienceLimiter` (config
  `progression.maxExperiencePerMinute`).
- Hive server: `HiveController` (active hive, repair costs), `HiveCombatController` (volleys, max 24
  shots/tick), `HiveTaskController` (healers).
- Console: `menu/DeviceControlMenu` (module bay, charge slot, button ids) + `client/DeviceControlScreen`
  (holographic window drawn with `HoloPaint`); open via key H or holding Shift over the item
  (`client/ShiftHoverOpener`).
- Shield rendering: `client/ShieldVisualRenderer` -> `ManaShieldVisual`, `TwinsShieldVisual`,
  cells; wave `ShieldRipple`, refraction shader `ShieldRefraction`
  (`assets/relics_addon/shaders/core/shield_refraction.*`), additive halo `ShieldGlow`, Twins
  hit traces `ShieldCircuitTraces`. Inside-the-shell views must stay faint (`ShieldSurfaceLighting.INSIDE`).
- Photon effects: `client/fx/ExFx` (+ `ExFxLibrary`, `PointEffectExecutor`), built in code,
  overridable by `assets/relics_addon/fx/<name>.fx`.
- Ship hives (blocks, `ship/`): `ShipHiveBlock`/`ShipHiveBlockEntity` (owner, FE `Battery`, switch,
  module; `save/load` = what lasts, `saveSync/loadSync` = runtime state sent in `getUpdateTag` only),
  modules `LanceModule` (turret, `LanceShape`), `AegisModule` (+ `AegisShape` ellipsoid, `AegisFields`
  = interception/absorption/explosion handlers), `EscortModule` (wings); shared `ShipBrain` (threat
  board, grudges, claims) and `ShipAllies` (sworn/friendly/threat); `ShipFrame` = where the hive really
  is (Sable pose via Sable Companion, bundled); `ShipRays` = clips with Sable hits carried out of the
  plot. Window `menu/ShipHiveMenu` + `client/ShipHiveScreen`; drawing `client/ShipHiveRenderer`
  (world space, `CLIENT_LOADED` registry), beam loop `client/ShipBeamSound`. Tests `ShipHiveGameTests`
  (each in its own batch; hives removed at test end), film `runShipScenarioClient` (Sable jar in
  `run-ship/mods`; assembles a ship with `/sable assemble area`, turns it with `/sable teleport <uuid> x y z yaw pitch`).
- Dynamic lights (optional LambDynamicLights): renderers report `EffectLights.glow` (per frame) and
  `EffectLights.flash` (per event); it merges and caps them (24). Only `client/light` touches LDL
  types; LDL loads `DynamicLightsBridge` through the `yumi:entrypoints` mod property. Client config
  `lights.dynamic`. Checks: `EffectLightsCheck`, `client/light/EffectLightPoolCheck`.

## Pitfalls already hit

- **Stream codecs**: never write counts or indices as a byte - a 500-drone swarm desynced
  `curios:sync_stack` ("Failed to decode packet clientbound/minecraft:custom_payload"). Use
  VarInt/VarLong and add the type to `NetworkCodecCheck`.
- Absorbing by setting damage to 0 still applies knockback and on-hit effects (wither skull ->
  Wither II -> death). Cancel the event instead.
- `#minecraft:bypasses_shield` includes wither, fire, magic, fall - do not use it to filter.
- Curios 9.5 defines the `charm` slot but gives it to nobody: `data/relics_addon/curios/entities/devices.json`.
- Every user-facing string needs `en_us` AND `ru_ru` keys (the user plays in Russian).
- A fresh run directory (new worktree) has no `options.txt`, so the client opens the accessibility
  onboarding screen and capture runs wait forever for the title screen; copy `options.txt`
  (`onboardAccessibility:false`) from an existing run dir first.
- A mod for some runs only goes on the run task's JVM classpath (`classpathProvider`), never on
  MDG's per-run `additionalRuntimeClasspath`: that makes it a boot-layer library and FML skips it
  ("already located earlier", visible only with DEBUG logs). LambDynamicLights also needs
  `net.minecraft.mappings=mojmap` plus `bundling=external`, or Gradle picks its shadowed `-dev.jar`.
- **Sable (Create Aeronautics)**: a ship's blocks live in a far "plot"; `level.clip` also hits ship
  hulls but returns the hit IN PLOT COORDINATES - carry it out with
  `SableCompanion.projectOutOfSubLevel` before comparing distances or querying entities (a world-to-plot
  AABB is millions of blocks). Poses are mutable (copy with `new Pose3d(pose)`); velocities are in
  blocks per second. A menu's `stillValid` must measure to the hive's world position. Sable selectors
  (`@l` latest) need a player source; name ships by UUID from the server.
- **Veil** (bundled in Sable) re-prints every core shader through glsl-processor 0.2.3, which drops
  the semicolon after a lone `x++;` statement - write `x += 1;` (for-loop headers are fine).
  Check a shader offline: parse and print it with glsl-processor, or look for "Couldn't compile
  dynamic" in a Sable client's log.
- GameTest structures stay in the level after their tests: anything that acts on its own (a ship
  hive) must be removed when its test ends, or it fights later tests nearby.
- Shell/Windows: PowerShell `Remove-Item` with wildcards in TEMP is blocked - write to a new folder.
  No Python/ffmpeg on the machine; use Node.js for scripts and WinRT for video.
  Clone into short paths (`C:/dev/...`) - the scratch path is too long for git.

## Assets and tools (all Node.js unless noted)

- `tools/build_combat_sounds.mjs` - synthesized Ogg sounds (`npm install` in `tools/` first; `--validate`).
- `tools/build_mana_shield_mesh.mjs` - Mana shield OBJ (groups body/core/fx/shell_0..3).
- `tools/compact_obj.mjs` - lossless OBJ dedup (repeated v/vt/vn records, faces renumbered). The Node
  generators write through it; after a Python generator (`build_rf_meshes.py` family) run it over the
  models, or `verifyObjCompact` (in `check`) fails. `--check a.obj b.obj` proves two files bake alike.
- `tools/draw_component_icons.mjs --preview` - 16x16 part icons + preview sheet in `work/`.
- `tools/video_frames.ps1` (PowerShell) - contact sheet of frames from the user's gameplay videos;
  read the PNG to see what happened, then zoom with `-Times`.
- Preview OBJ models by software-rendering them to PNG before shipping (check for holes on a
  magenta background from several angles).

## Working with this user

- Russian; short status updates while working; they often send screenshots/videos of bugs.
- Commit per logical change with the attribution trailer; do not push without being asked.
