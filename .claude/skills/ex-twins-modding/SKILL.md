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
  `ShieldProjectileInterceptor`, `ShieldBarrier` (pushes hostile mobs out).
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

## Pitfalls already hit

- **Stream codecs**: never write counts or indices as a byte - a 500-drone swarm desynced
  `curios:sync_stack` ("Failed to decode packet clientbound/minecraft:custom_payload"). Use
  VarInt/VarLong and add the type to `NetworkCodecCheck`.
- Absorbing by setting damage to 0 still applies knockback and on-hit effects (wither skull ->
  Wither II -> death). Cancel the event instead.
- `#minecraft:bypasses_shield` includes wither, fire, magic, fall - do not use it to filter.
- Curios 9.5 defines the `charm` slot but gives it to nobody: `data/relics_addon/curios/entities/devices.json`.
- Every user-facing string needs `en_us` AND `ru_ru` keys (the user plays in Russian).
- Shell/Windows: PowerShell `Remove-Item` with wildcards in TEMP is blocked - write to a new folder.
  No Python/ffmpeg on the machine; use Node.js for scripts and WinRT for video.
  Clone into short paths (`C:/dev/...`) - the scratch path is too long for git.

## Assets and tools (all Node.js unless noted)

- `tools/build_combat_sounds.mjs` - synthesized Ogg sounds (`npm install` in `tools/` first; `--validate`).
- `tools/build_mana_shield_mesh.mjs` - Mana shield OBJ (groups body/core/fx/shell_0..3).
- `tools/draw_component_icons.mjs --preview` - 16x16 part icons + preview sheet in `work/`.
- `tools/video_frames.ps1` (PowerShell) - contact sheet of frames from the user's gameplay videos;
  read the PNG to see what happened, then zoom with `-Times`.
- Preview OBJ models by software-rendering them to PNG before shipping (check for holes on a
  magenta background from several angles).

## Working with this user

- Russian; short status updates while working; they often send screenshots/videos of bugs.
- Commit per logical change with the attribution trailer; do not push without being asked.
