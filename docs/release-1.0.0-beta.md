# EX-twins 1.0.0 Beta

For Minecraft 1.21.1, Java 21 and NeoForge 21.1.212.

Required mods: Relics 1.21.1-0.12.8, Curios 9.5.1+1.21.1, OctoLib 0.6.2 and Architectury 13.0.11. Install the addon on both client and server. Back up existing worlds before upgrading.

## Included

- Three autonomous shield amulets and three typed combat-hive amulets, with recipes, native Relics research and upgrade cards.
- Shields with 420 local HP cells and a shared buffer upgraded to 5000 HP; adjustable radius and allied-creature coverage.
- RF raised panels, Mana incoming-facing glass hemispheres, and violet-black Ex-Twins glass, honeycomb segments and particles. Independent impact waves do not reset on subsequent hits.
- Hives with closed inner hulls, articulated shells and recessed docking details. Inventory icons use their animated 3D models.
- 12 initial drones per type, upgraded to 250. Typed attacks, target formations, droplet travel and family-specific sounds.
- Bounded virtual swarms, dense-model LODs and render culling. No standalone drone items, batteries or generators.
- Ordinary Minecraft main-menu startup. Development preview screens and test fixtures are excluded from the release JAR.

## Verification

The local verification suite includes 87 required server GameTests, geometry and animation checks, model-resource validation and native client captures. These checks are not a full modpack, multiplayer, shader-pack or prolonged 750-drone performance certification.

To rerun: `./gradlew build runGameTestServer`. The native capture tasks require a graphical desktop and installed dependencies. `runStartupCheckClient` checks ordinary main-menu startup; `runHiveGifClient` records hive and swarm frames for `tools/assemble_hive_preview.py`.

## Compatibility Notes

- The internal namespace remains `relics_addon` to retain existing shield and hive data.
- Removed prototype drone item IDs are not converted. The legacy component decoder is retained for old serialized stack data.
- Generic mod projectiles need the addon entity tag; hitscan weapons require an adapter. Tridents, pearls and utility potions are not intercepted.
- Existing Relics extended-config stat overrides are not overwritten. Review old prototype templates when updating.
- Charm slot availability remains controlled by the modpack. Same-type hives do not stack their drone limit.
