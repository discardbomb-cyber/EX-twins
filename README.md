# EX-twins

Expandable autonomous shields and combat hives for Minecraft 1.21.1, NeoForge and Curios. Progression, modules and upgrades are built into this mod; Relics is not required.

![Hives and swarms: deployment, combat and belt recall](docs/images/hives-and-swarms.gif)

[Full feature showcase, 46.5-second GIF](https://github.com/discardbomb-cyber/EX-twins/releases/download/v1.0.0-beta.1/EX-twins-showcase.gif): shields, impact waves, damage, gathering, swarm attacks, belt recall, healer formations and research UI. These are native renderer demonstrations and seeded interface captures, not a live-world battle recording. GIF has no audio.

## Installation

Install `EX-twins-1.0.0-beta.1.jar` on both the client and server alongside the dependencies below. This is a beta release; back up existing worlds before upgrading. No generators, batteries or external energy are required.

## Build From Source

Use Java 21 and the included Gradle wrapper. Obtain these dependencies separately and put them in `libs/`:

- `curios-neoforge-9.5.1+1.21.1.jar`

Dependencies are not bundled or redistributed. An optional Curios path can be set in an untracked `gradle.local.properties` using `curiosJar`; `-P` command-line values take priority.
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
Press `H` (rebindable in Controls) to open Device Control for all carried or equipped shields and hives. The screen toggles each device, displays its level and points, and manages its three module slots and upgrades. Using a held device also toggles it.
Each of the six items stores its own experience, levels, upgrade points, modules and upgrade ranks in its item data. Combat awards bounded experience; each new level grants one upgrade point.
Craft Device Modules from an amethyst shard, redstone and an iron nugget, then install them in the console. Slot one expands shield buffer / hive count, slot two expands shield radius (subject to the server cap) / hive health, and slot three improves hive attack timing. Removing a module returns it to the player inventory (or drops it if full).

- Shields have a shared 504 HP buffer, upgraded to 5000 HP over ten protection levels, plus 420 independent cells with 12 HP each. Incoming damage spends the buffer first, then local HP; empty regions become real holes when the buffer is exhausted. Total maximum at full progression: 10040 HP. Upgrading, toggling and changing settings do not refill HP.
- After 40 quiet ticks, type-specific repair restores damaged cells before refilling the shared buffer. New topology and neighbors are cached once, not rebuilt during combat.
- Shield radius starts at 2 blocks and unlocks up to 12 with protection upgrades. Choose the actual radius with `/relics_addon shield_radius <radius>`. Server config can impose a lower ceiling. RF plates have deterministic radial relief. Mana forms a smooth turquoise glass hemisphere facing the incoming projectile, with a luminous rim and traveling waves. Twins combines a continuous violet-black membrane, raised honeycomb segments and suspended violet motes.
- Every impact brightens its region and launches an expanding wave across the visible field. Up to twelve recent impacts are retained, including hits during the same server tick; later waves do not reset earlier animation. Mana hemispheres clip to the incoming side and merge without double opacity. Idle fields remain invisible. Supported hostile projectiles born inside the shield are intercepted immediately beside the projectile; friendly shots are excluded.
- `/relics_addon shield_coverage owner|allies|all` controls protection of creatures inside the sphere. The default is the owner, teams and tamed allies; `all` explicitly includes other living creatures. Melee and explosion protection use the covering owner's HP and incoming direction. These commands require no operator privilege and never change another player's item.
- Unknown mod bullets require the `relics_addon:shield_interceptable_projectiles` entity-type tag; hitscan weapons need an adapter. Tridents and utility pearls/potions are not removed.
- Eligible damage first spends common buffer HP, then the struck cell and the configured neighbor share. There is no baseline percentage leak. Any excess after this protection is exhausted reaches the wearer immediately; later hits through that hole pass until repair or gathering. Other intact cells continue protecting.
- Explosion protection spends the common buffer first and then local HP on the explosion side. Terrain destruction and knockback remain vanilla behavior.
- Each shield has its own native research constellation and illustrated 22x31 ability cards. Main protection now requires completing its constellation; inventory item views remain 3D.
- Distribution: RF 25-45% from relic level 2, Mana 20-35% from level 3, Ex-Twins 35-50% from level 2. Each has three upgrade levels and uses the same two-neighbor HP-conserving mechanic.
- Gathering: RF 1-2 cells from level 4; Mana 1-3 from level 2; no Ex-Twins gathering. Travel remains 10 ticks with a 40-tick cooldown. Moving cells cannot protect until arrival, retain HP and leave donor holes. All upgrades require native unlocking and research.
- Restoration unlocks at relic levels 5/4/3 for RF/Mana/Twins and upgrades passive recovery to 2/3/2 HP per repair step. Twins additionally unlocks barrier stabilization at level 4, reducing the post-hit recovery pause from 40 to 16 ticks. Neither upgrade raises the 5000 HP buffer limit or refills protection on purchase.
- Ex-Twins retains subtle arcane seals beneath its raised segments. Its circuit-board-like mana tracks activate only on confirmed absorption, never at idle or merely on projectile approach. Every stroke is clipped to the authoritative cells, so broken cells remain holes after the common buffer is exhausted.
- The hit-time damage fallback respects vanilla shield-bypass tags. Fake players, spectators and disabled slots do not operate the relics.
- The main shield ability keeps fixed full absorption while upgrading buffer capacity and radius. Distribution/gathering remain separate upgrades. Successful combat and absorption award bounded experience; idle time and repair do not.
- Shield inventory and world views reuse the same animated 3D models.

No batteries, generators, energy network or additional rendering-library mod is required.

## Combat Hives

Each type has its own native constellation, 22x31 ability card and animated 3D hive amulet. Complete its research and enable the ability while equipped. The deployed defenders reuse the existing RF/Mana/Ex-Twins drone models.

RF hives have four silver mechanical bay doors, Mana has six ivory/gold shells, and Ex-Twins has twelve black pentagonal plates with violet circuit inlays. Inventory icons render these same animated OBJ models. Generator: `tools/build_hive_meshes.py`.

| Type | Initial drones -> upgraded | HP per drone -> upgraded | Reconstruction delay -> upgraded |
| --- | --- | --- | --- |
| RF | 12 -> 250 | 12 -> 40 | 4 -> 2 seconds |
| Mana | 12 -> 250 | 8 -> 30 | 2.5 -> 1 second |
| Ex-Twins | 12 -> 250 | 18 -> 60 | 6 -> 3 seconds |

Ten native ability upgrade levels reach those targets. The hard cap is 250 drones per type per wearer, or 750 active virtual helpers when all three types are equipped. Only the first active functional hive of each type participates, so duplicate amulets cannot multiply the limit. Different types coexist only if the modpack provides enough charm slots.

- Hives summon combat helpers which assist against a recently attacked target or an aggressor. RF uses cyan electricity; Mana fires traveling blue mana bolts; Twins uses violet lightning and mana bolts. Lightning checks line of sight and creates no fire. Mana bolts are server-simulated swept projectiles with block and entity collisions. Vanilla hurt invulnerability is respected.
- Once acquired, a target stays locked until it dies; losing sight or attacking another creature does not reset the swarm. Shots still respect walls. Disabling the hive, assigning all drones to healing, a target becoming allied, changing dimensions or exceeding the configured pursuit range cancels pursuit.
- Each drone has an independent 1-5 second attack interval, upgraded to 1-2 seconds. Initial volleys are staggered. RF/Mana damage upgrades from 2 to 3, Twins from 3 to 4 before the target's armor and effects.
- In transit, the swarm morphs into a softly pulsing droplet with faint moving wave bands. It then surrounds its target: RF inward-facing hexagonal emitters, Mana rotating rings, Twins a polygonal sphere. When a task ends, drones fly into the owner's belt and disappear. There are no idle formations behind the player.
- Press `H` (rebindable in Controls) to open Device Control, select a hive, then open Hive Tasks to assign healers. Remaining slots defend and attack. Assignments persist separately on each item. Healers restore only their owner and neither attack nor intercept. Healing is capped at 4 HP/second across all equipped hives by default, configurable by the server.
- Each hive has three optional research upgrades: combat damage, healing strength, and repair/reconstruction. The families use different bonus ranges. These upgrades never increase the 250-drone cap or the shared healing limit, and changing tasks does not reset combat cooldowns.
- Optional legacy interception remains available through server config `hive.interceptProjectiles`. Idle defenders intercept supported hostile projectiles at a 2.65-block boundary and spend their individual HP. Drones away in combat do not simultaneously shield their owner's location. Arrow overflow continues with reduced damage.
- Surviving defenders retain their remaining HP, recover for eight ticks, then can intercept again. After 40 quiet ticks they repair one HP each second. Destroyed defenders reconstruct after their type's delay. Repair/reconstruction earns no XP.
- Drones cannot target or damage their wearer, teammates or allied pets. They have no attackable projectile entities of their own, so swarms never damage each other. Creative/spectator players and disallowed PvP targets are excluded.
- Swarms contain no server-side drone entities or pathfinding. Damage/repair updates synchronize a bounded item component; visual flight is deterministic on the client. Only changed state is sent. Once a swarm exceeds 50 drones, or is rendered at distance, small helpers use lower-detail versions of the same silhouettes, with frustum/distance culling, a 750-model global visual budget and dense swarm LODs capped at 100 faces per model; all server-side defenders remain active.
- Hives are not a substitute for the shield's melee/explosion protection. Each family has original synthesized attack/impact and summon/dismiss sounds; shields have absorption, cell-break and collapse sounds. Playback is spatially rate-limited to avoid hundreds of simultaneous voices.

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
Use `/relics_addon shield_status` for read-only diagnosis of your equipped shields: disabled state, native research/locks, slot activity, shield priority and HP. This command does not unlock or repair anything.

## Models And Assets

Inventory icons render the animated 3D models. Hives have closed inner hulls, articulated armor, recessed docking ports and distinct RF, Mana and violet-black Ex-Twins materials. The deployed swarm uses separate low-detail 3D models at high populations.

The `tools/` directory includes the current mesh generators, ability-card drawings and resource validators. Python tools require the packages listed in `tools/requirements.txt`; the hive card generator uses Node.js standard modules. Ready-to-use assets are included, so regenerating them is not required to build the mod.

`./gradlew runReleaseCheckClient` verifies the packaged release from `run-release-check/mods`, captures the ordinary title screen and exits. It does not load the main source-set classes or any preview screens. The separate startup probe is never packaged in the addon. Keep development clients in a separate checkout while recompiling: a running development client can fail to load a class if its compiler output changes during startup.

To reproduce the extended showcase, run `runFeatureGifClient` and `runUiCaptureClient`, then `python tools/assemble_feature_preview.py run-feature-gif/screenshots run-ui-capture/screenshots outputs/EX-twins-showcase.gif --font /path/to/a-cyrillic-font.ttf`. Recording is explicitly opt-in and never runs in a release client.

## Beta Limitations

- This is not a certification of full-modpack, multiplayer or shader-pack compatibility.
- Generic mod projectiles need the documented entity-tag integration; hitscan weapons need an adapter. Tridents and utility throws are not intercepted.
- The 750-model visual budget is shared across visible players; server-side helpers remain active when their models are culled.
- Drone item IDs from early prototypes are not restored. A legacy data-component decoder is retained for old stack data.

## License

All Rights Reserved, as specified in the mod metadata. Dependencies retain their respective licenses.
