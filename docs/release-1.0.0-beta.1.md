# EX-twins 1.0.0 Beta 1

Minecraft 1.21.1, Java 21, NeoForge 21.1.212. Requires Relics 0.12.8, Curios 9.5.1+1.21.1, OctoLib 0.6.2 and Architectury 13.0.11.

## Changes

- All three swarm types keep their acquired target until it dies. Brief loss of sight, expired aggression timers and newer attackers do not reset the target or deployment animation.
- Attacks still collide with walls and never damage allies. Disabling the hive, assigning every drone to healing, a target becoming allied, changing dimensions or exceeding the configured pursuit range cancels pursuit.
- Added a release-class completeness check to every build and a separate `runReleaseCheckClient` task that loads the packaged JAR from an isolated mods folder, rather than mutable development classes. The verification helper is not shipped.
- Retains native item XP/ranks, three additional upgrades on each of the six relics, 24 ability cards and constellations, and the per-hive combat/healer menu (`H`).
- Includes a separate 46.5-second showcase GIF covering shields, impact waves, damage, gathering, hives, attacks, belt recall, healer formations and research UI. It uses native demonstration scenes and seeded interface captures, not live-world combat. GIF has no audio.

## Verification

105 required server GameTests passed, including target persistence, all three attack families, friendly-fire rules, healing, native progression and shield behavior. Geometry/animation checks and release class validation passed. The packaged client reached the ordinary title screen without opening a preview.

Install this JAR on both client and server, replacing the previous addon JAR rather than keeping both. Back up existing worlds first. Existing custom Relics template overrides are not changed automatically. This beta has not been certified against every modpack or shader pack.
