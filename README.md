# EX-twins

Expandable autonomous shields and combat hives for Minecraft 1.21.1, NeoForge and Curios. Progression and upgrades are built into this mod; Relics is not required. Effects use [Photon](https://github.com/Low-Drag-MC/Photon).

![Hives and swarms: deployment, combat and belt recall](docs/images/hives-and-swarms.gif)

![EX-twins showcase: glass honeycomb shield, strikes, and the hive's droplet, barrage and containment modes](docs/images/ex-twins-showcase.gif)

The showcase above is rendered in-game by the capture galleries: shield impacts on the honeycomb, then a 750-drone hive deploying 250 drones in each attack mode. The droplet figures fly from the fan behind their owner (the coloured outline) to the target (the grey outline). It is not a live-world battle recording, and the GIF has no audio. The older [46.5-second showcase](https://github.com/discardbomb-cyber/EX-twins/releases/download/v1.0.0-beta.1/EX-twins-showcase.gif) is still attached to the 1.0.0-beta.1 release.

## Installation

Install `EX-twins-1.0.0-beta.1.jar` on both the client and server alongside Curios, Photon, LDLib2 and KilaGraph (Photon 2.2.7+, LDLib2 2.2.40+). This is a beta release; back up existing worlds before upgrading. Devices run on built-in batteries; Botania, Ars Nouveau and Iron's Spells are optional mana sources.

## Build From Source

Use Java 21 and the included Gradle wrapper. Curios, Photon, LDLib2 and KilaGraph are resolved from their Maven repositories (`maven.theillusivec4.top`, `maven.firstdark.dev/snapshots`); nothing has to be placed in `libs/`.

Run `./gradlew build` on Linux/macOS or `.\gradlew.bat build` on Windows. The result is `build/libs/EX-twins-1.0.0-beta.1.jar`. The first build requires internet access for Gradle and NeoForge artifacts.

## Items

- `relics_addon:rf_shield` -> Curios `charm`
- `relics_addon:mana_shield` -> Curios `charm`
- `relics_addon:twins_shield` -> Curios `charm`
- `relics_addon:rf_hive` -> Curios `charm`
- `relics_addon:mana_hive` -> Curios `charm`
- `relics_addon:twins_hive` -> Curios `charm`
The RF, Mana, and Ex-Twins labels are style identities. Shields operate without external energy.
Standalone drone item IDs are no longer registered. Hives deploy their existing models as typed defensive swarms; old `rf_drone`, `mana_drone`, and `twins_drone` stacks are unsupported and will not be converted.

## Playable Build

Version `1.0.0-beta.1` targets Minecraft 1.21.1, Java 21 and NeoForge 21.1.212.
The three shields and three hives use the charm (Amulet) slot. Slot count remains controlled by the modpack. Each has a recipe and recipe-book unlock.
Press `H` (rebindable in Controls) or hold Shift over a device to open its console: a holographic window with Overview (power switch, level, integrity, charge and the device's main stats), Batteries, Upgrades and, on hives, Swarm tabs. Labels shrink to fit their buttons; anything still cut short shows in full when hovered. Using a held device also toggles it.
Each of the six items stores its own experience, levels, upgrade points and upgrade ranks in its item data. Combat awards bounded experience; each new level grants one upgrade point.

- Shields have a shared 504 HP buffer, upgraded to 5000 HP over ten protection levels, plus 420 independent cells with 12 HP each. Incoming damage spends the buffer first, then local HP; empty regions become real holes when the buffer is exhausted. Total maximum at full progression: 10040 HP. Upgrading, toggling and changing settings do not refill HP.
- The shell hits back: an aggressive mob touching it is thrown clear and takes the shield's own damage, 3-7 RF discharge (softened by armour), 2.5-6 Mana burst or 4-9 Twins surge (both through armour) from level 0 to 10. One mob is struck at most once a second; each strike costs 6 battery points. Neutral mobs such as endermen and zombified piglins are only struck once they attack the wearer. Server config: `shield.strikeDamage`, `shield.strikeKnockback`, `shield.strikeCooldownTicks`.
- RF and Twins shells are a honeycomb of the 420 gameplay cells: flat glass panes joined by bright seams, with a sheen that follows the light; damaged RF cells warm through yellow to red, Twins cells dim, and a broken cell is a hole. Mana is one seamless teal dome. A shield is invisible until it is struck (client config `shield.idleOpacity` can keep it faintly visible).
- After 40 quiet ticks, type-specific repair restores damaged cells before refilling the shared buffer. New topology and neighbors are cached once, not rebuilt during combat.
- Shield radius starts at 2 blocks and unlocks up to 12 with protection upgrades. Choose the actual radius with `/relics_addon shield_radius <radius>`. Server config can impose a lower ceiling. RF plates have deterministic radial relief. Mana forms a smooth turquoise glass hemisphere facing the incoming projectile, with a luminous rim and traveling waves. Twins combines a continuous violet-black membrane, raised honeycomb segments and suspended violet motes. Hits on the Mana and Twins shields send a travelling wave across the shell: the surface bends into a crest and trough, and a refraction band on the wave front distorts the world behind it (client config `shield.refraction`, disabled automatically under Iris/Oculus shader packs).
- Every impact brightens its region and launches an expanding wave across the visible field. Up to twelve recent impacts are retained, including hits during the same server tick; later waves do not reset earlier animation. Mana hemispheres clip to the incoming side and merge without double opacity. Idle fields remain invisible. Supported hostile projectiles born inside the shield are intercepted immediately beside the projectile; friendly shots are excluded.
- `/relics_addon shield_coverage owner|allies|all` controls protection of creatures inside the sphere. The default is the owner, teams and tamed allies; `all` explicitly includes other living creatures. Melee and explosion protection use the covering owner's HP and incoming direction. These commands require no operator privilege and never change another player's item.
- Unknown mod bullets require the `relics_addon:shield_interceptable_projectiles` entity-type tag or the `shield.interceptedProjectiles` server config list; hitscan weapons need an adapter. Unless listed there, tridents and utility pearls/potions are not removed.
- Server config lists tune what the field stops. All are empty by default; entries are registry ids or `#tags`, read once per config load or reload, and a malformed or unknown entry is logged as a warning and matches nothing.
  - `shield.passingDamageTypes`: damage types that always reach the wearer, on top of the `relics_addon:shield_passes` damage-type tag (starvation, drowning, suffocation, the void and similar), e.g. `["minecraft:fall", "#minecraft:is_fire"]`.
  - `shield.absorbedDamageTypes`: damage types the field absorbs although `shield_passes` lets them through, e.g. `["minecraft:drown"]`. `shield.passingDamageTypes` wins over it, and shield strikes and swarm blows always pass.
  - `shield.interceptedProjectiles`: entity types the field stops in flight besides arrows and the tag above, e.g. `["minecraft:snowball"]`. Tridents, pearls and potions listed here are stopped too.
  - `shield.ignoredProjectiles`: entity types the field never stops in flight, overriding every other rule. Their hits are still absorbed at the wearer unless their damage type passes the field.
  - `shield.keptEffects`: harmful effects the field never cuts off or trims when an attacker applies them, e.g. `["minecraft:poison"]`.
- Eligible damage first spends common buffer HP, then the struck cell and the configured neighbor share. There is no baseline percentage leak. Any excess after this protection is exhausted reaches the wearer immediately; later hits through that hole pass until repair or gathering. Other intact cells continue protecting.
- Explosion protection spends the common buffer first and then local HP on the explosion side. Terrain destruction and knockback remain vanilla behavior.
- Upgrades are bought in the console's Upgrades tab with points earned from levels (one point per rank, three ranks each). Each shield and hive uses its own illustrated 22x31 upgrade cards; inventory item views remain 3D.
- Distribution: RF 25-45% from relic level 2, Mana 20-35% from level 3, Ex-Twins 35-50% from level 2. Each has three upgrade levels and uses the same two-neighbor HP-conserving mechanic.
- Gathering: RF 1-2 cells from level 4; Mana 1-3 from level 2; no Ex-Twins gathering. Travel remains 10 ticks with a 40-tick cooldown. Moving cells cannot protect until arrival, retain HP and leave donor holes.
- Restoration unlocks at relic levels 5/4/3 for RF/Mana/Twins and upgrades passive recovery to 2/3/2 HP per repair step. Twins additionally unlocks barrier stabilization at level 4, reducing the post-hit recovery pause from 40 to 16 ticks. Neither upgrade raises the 5000 HP buffer limit or refills protection on purchase.
- Ex-Twins retains subtle arcane seals beneath its raised segments. On a confirmed absorption, violet circuit-board traces grow out from the hit point across the shell, forking and ending in pads, with a signal pulse running along them. Every shield also gets an additive glow that flares at hits and along the wave crest.
- The hit-time damage fallback respects vanilla shield-bypass tags. Fake players, spectators and disabled slots do not operate the relics.
- The main shield ability keeps fixed full absorption while upgrading buffer capacity and radius. Distribution/gathering remain separate upgrades. Successful combat and absorption award bounded experience; idle time and repair do not.
- Shield inventory and world views reuse the same animated 3D models.

## Crafting

Devices are built from the mod's own parts rather than raw vanilla items (all use vanilla materials, so any pack can craft them):

| Part | Made from | Goes into |
| --- | --- | --- |
| Resonant Circuit (x2) | gold nuggets, redstone, quartz, copper ingot | every part and device |
| Energy Cell | copper, iron, redstone block, circuit | RF and Twins devices (RF battery) |
| Mana Cell | amethyst shards, gold, lapis block, circuit | Mana and Twins devices (mana battery) |
| RF / Mana / Twins Shield Core | a vanilla shield, iron and diamond / gold, amethyst block and diamond / crying obsidian, echo shards and diamond, plus circuits | the matching shield |
| RF Drone Frame (x2) | iron, copper, redstone, circuit | RF Hive (six frames) |
| Mana Drone Shell (x2) | gold nuggets, amethyst shards, circuit | Mana Hive (six shells) |
| Twins Drone Plate (x2) | obsidian, amethyst shards, circuit | Twins Hive (four plates, both cells and an end crystal) |

Recipes unlock in the recipe book once you hold the key part. Icons are drawn by `tools/draw_component_icons.mjs`.

## Batteries

Every device has built-in batteries: RF shields and hives an RF battery, Mana devices a mana battery, and Twins devices both. A switched-on device only works while one of its switched-on batteries holds charge; the console monitor shows NO POWER otherwise. New devices ship fully charged, and capacity grows with the device level (25 000 to 100 000 points; one point is 10 FE).

- Upkeep: 20 points per second for a running shield, 20 plus one per ten drones for a hive. Absorbing damage costs 10 points per HP, shield repair 2 per HP, each drone shot 3, healing 5 per HP and drone repair 1 per HP. Twins split every cost between their two batteries and fall back to whichever still has charge. Creative players pay nothing.
- The RF battery is a Forge Energy item: any FE charger (Mekanism, Thermal, Flux Networks and similar) can fill it, and a charged FE item placed in the console's charge slot pours its energy in. Machines cannot drain it.
- The mana battery refills twice a second while the device is on. Its source is chosen in the console: Auto (magic mods first, then experience), Magic (Botania mana items such as tablets and rings, the player's own Ars Nouveau or Iron's Spells mana) or Experience. Player mana keeps a 25% reserve for spellcasting. All rates are server config (`power.*`), and `power.requireBatteries = false` makes devices free.
- Each battery has its own on/off switch in the console's Batteries tab.

Hover a device in any inventory screen (including the Curios screen) and hold Shift to open its console; any click cancels the hold, so shift-clicking still moves the item.

Photon (with LDLib2 and KilaGraph) is required for particle effects.

## Combat Hives

Each type has its own 22x31 upgrade cards and animated 3D hive amulet. Equip it in a charm slot and switch it on. Only one hive can be worn at a time. The deployed defenders reuse the existing RF/Mana/Ex-Twins drone models.

RF hives have four silver mechanical bay doors, Mana has six ivory/gold shells, and Ex-Twins has twelve black pentagonal plates with violet circuit inlays. Inventory icons render these same animated OBJ models. Generator: `tools/build_hive_meshes.py`.

| Type | Drones: level 0 -> 10 | Flying at once | HP per drone | Repair delay -> upgraded | Blow damage -> upgraded |
| --- | --- | --- | --- | --- | --- |
| RF | 12 -> 750 | up to 250 | 3 | 4 -> 2 seconds | 2 -> 3 |
| Mana | 12 -> 750 | up to 250 | 3 | 2.5 -> 1 second | 2 -> 3 |
| Ex-Twins | 12 -> 750 | up to 250 | 3 | 6 -> 3 seconds | 3 -> 4 |

Ten levels grow the swarm to 750 drones. At most 250 fly at once; the rest wait in the hive as replacements. Only one hive can be equipped (Curios rejects a second one), and only one operates.

- The flying drones are split into 2 to 16 strike groups. Pick one of three attack modes in the console's Swarm tab:
  - **Droplet.** Each group forms one big figure in a fan behind and above its owner: a Mana drop, an RF tesseract turning through the fourth dimension, or Ex-Twins hexagons with lightning arcing between them. In turn, each figure flies at the target like a projectile, strikes it whole and knocks it back, then flies home to re-form. Ex-Twins figures bend space as they fly.
  - **Barrage.** Each group packs into a dense, glowing clump around the target, and the clumps draw the family's pattern: an RF crown, a Mana star, Ex-Twins octagons. A ball of lightning charges inside each clump. When it fires, it passes through walls and bursts on the target in a lightning blast that warps the space around it. Now and then a few drones hop to a neighbouring clump. Ex-Twins clumps are octagons that crackle with lightning and shed violet smoke.
  - **Containment.** Every family stuns what it holds:
    - RF seals the target in a torus of hexagons that zaps it and swallows its shots.
    - Mana builds a ward of three rhombi and two rings. Drones keep a reflection buffer charged, and the ward turns blows back on the attacker.
    - Ex-Twins spins hexagonal rift spheres that lift the target about 4 blocks into the air around a black hole with a violet accretion disk.
    - Players and bosses cannot be held.
- Swarm blows land as the swarm's own damage type, which may knock the target back. Containment zaps never knock it about.
- The hive fights within 128 blocks of its owner by default. Once acquired, a target stays locked until it dies, leaves that range, becomes allied or changes dimension, or the hive is switched off.
- Each drone has 3 HP. Targets in reach swing at nearby drones, and explosions damage them too. A hit drone flies home for repair, and the next drone in its lane launches at once to take its place. A repaired drone waits in reserve.
- Drones pour out of the hive along their own curved paths and stream back when a task ends. Healers ring their owner's chest.
- Open the console with `H`, select a hive and use the Swarm tab to set the attack mode and to move drones between fighters and healers (±1 / ±10). Assignments persist on the item. Healers restore only their owner and never attack. Healing is capped at 4 HP/second by default, which the server can configure.
- Each hive has three upgrades: combat damage, healing strength, and repair/reconstruction. They never raise the 750-drone cap or the healing limit.
- Drones do not absorb damage for their owner; protection is the shield's job. Hives only attack and heal.
- Drone hits use their own damage type: the owner gets the kill credit, but hundreds of hits never knock the target around.
- Drones cannot target or damage their wearer, teammates or allied pets. They have no attackable projectile entities of their own, so swarms never damage each other. Creative/spectator players and disallowed PvP targets are excluded.
- Swarms contain no server-side drone entities or pathfinding. The item stores a compact drone array, and the network sends about one byte per resting drone. Flight is deterministic and computed identically on server and client, so blows land exactly where the shapes are drawn. Distant drones render as glowing points, and the renderer culls by frustum and distance.
- Hives are not a substitute for the shield's melee/explosion protection. Every family has original synthesized attack/impact and summon/dismiss sounds, shields have absorption, cell-break, collapse and ripple sounds, and the console has its own feedback sounds (`tools/build_combat_sounds.mjs`). Playback is spatially rate-limited to avoid hundreds of simultaneous voices.

## Verification

```powershell
$env:GRADLE_USER_HOME = "$env:USERPROFILE/.gradle"
.\gradlew.bat --offline build
.\gradlew.bat --offline runGameTestServer
.\gradlew.bat --offline runVisualTestClient
```

`build` runs mesh, mechanical-animation and orbit checks. GameTests use the separate `run-gametest` world.
The interactive test client uses `run-visual` and opens the normal Minecraft main menu, without a startup preview. Separate `runShieldCaptureClient`, `runResearchCaptureClient`, `runUiCaptureClient` and `runHiveCaptureClient` automations capture renderer/resources and exit. `runStartupCheckClient` captures the normal main menu and exits.
These runs use isolated development directories. Development tests and the model gallery are excluded from the release JAR.

Existing customized config files are not automatically overwritten. Relics ability templates and extended configs are no longer read. Shield HP and hive state remain on the item; devices begin using the standalone progression component when they next enter an inventory.
Use `/relics_addon shield_status` for read-only diagnosis of your equipped shields: disabled state, slot activity, shield priority and HP. This command does not unlock or repair anything.

## Models And Assets

Inventory icons render the animated 3D models. Hives have closed inner hulls, articulated armor, recessed docking ports and distinct RF, Mana and violet-black Ex-Twins materials. The deployed swarm uses separate low-detail 3D models at high populations.

The `tools/` directory includes the current mesh generators, ability-card drawings and resource validators. Python tools require the packages listed in `tools/requirements.txt`; the hive card generator uses Node.js standard modules. Ready-to-use assets are included, so regenerating them is not required to build the mod.

`./gradlew runReleaseCheckClient` verifies the packaged release from `run-release-check/mods`, captures the ordinary title screen and exits. It does not load the main source-set classes or any preview screens. The separate startup probe is never packaged in the addon. Keep development clients in a separate checkout while recompiling: a running development client can fail to load a class if its compiler output changes during startup.

To rebuild `docs/images/ex-twins-showcase.gif`, run `runFeatureGifClient` (shield frames) and `runHiveGifClient` (swarm frames), then `cd tools && npm install && node build_showcase_gif.mjs --segment "../run-feature-gif/screenshots/relics-shield-gif-*.png:2:80" --segment "../run-hive-gif/screenshots/relics-hive-gif-*.png:1:64"`. The older release GIF came from `runFeatureGifClient` plus `runUiCaptureClient` through `python tools/assemble_feature_preview.py run-feature-gif/screenshots run-ui-capture/screenshots outputs/EX-twins-showcase.gif --font /path/to/a-cyrillic-font.ttf`. Recording is opt-in and never runs in a release client.

## Beta Limitations

- This is not a certification of full-modpack, multiplayer or shader-pack compatibility.
- Generic mod projectiles need the documented entity-tag integration; hitscan weapons need an adapter. Tridents and utility throws are not intercepted.
- The 750-model visual budget is shared across visible players; server-side helpers remain active when their models are culled.
- Drone item IDs from early prototypes are not restored. A legacy data-component decoder is retained for old stack data.

## License

All Rights Reserved, as specified in the mod metadata. Dependencies retain their respective licenses.
