# Ship shield continuation — 2026-10-01

Worktree: `C:/dev/EX-twins-ships`, branch `feature/ship-shields`. No push or merge to main.

## Implemented in this continuation

- Read `SHIP_SHIELD_SESSION.md`, the full ship shield brief and the existing compatibility report. Viewed all 15 original references, the 12 newly attached references, and a 12-frame contact sheet of the eight-second video. The video shows rotating split armour around an energy core, not a hull shield.
- Retained the existing hull surface, server interception and drone implementation. Fixed depleted drones being replaced from their own dock inventory, returning drones losing their outstanding inventory claims on reload, and removal before the first restored tick. Loaded drones whose seats disappeared release their dock claims.
- Fixed enclosed boundary voxels beside a cabin pillar, shots born in sealed cabins, explosion damage-type exemptions, and strict cell-budget enforcement. A rebuild that cannot fit at the largest usable grid spacing is rejected while the previous mesh stays available. Failed shapes are not rebuilt every tick.
- Kept the pre-existing uncommitted damage tests and fixes, removed an always-true assertion, added recharge/reload and explosion-exemption regressions.
- Replaced the permanent diagnostic wireframe with event-driven hull surfaces. RF and Twins use the dual of the hull's triangulation, following `ShieldHoneycomb`'s polygon-cell approach. Mana uses the continuous hull mesh. Broken patches are omitted; fragments briefly peel outward and fall in ship coordinates.
- Reused amulet code directly: `ShieldShellVisual` palettes, `ShieldRipple` wave profile, `ShieldGlow` additive buffers, `ShieldSurfaceLighting.INSIDE`, `ShieldRefraction` shader with hull polygons, and `ShieldCircuitTraces` patterns mapped onto the Twins hull. The new material shader adds steel grain, teal streams and violet nebula/stars. All geometry follows the ship transform and luminous nodes/surfaces report to `EffectLights`.
- Added a bounded history of 12 confirmed impacts and activation time to `ShipShieldView`; counts and damage values use VarInt, times use VarLong, positions are generator-relative. Extended `NetworkCodecCheck`, including damage greater than 255 and concurrent hits.
- Reused the families' amulet absorption, break, collapse and ripple sounds through `RelicSounds`.
- Added automated capture scenes `ship-shell-rf`, `ship-shell-mana`, `ship-shell-twins`.

## Review c294ffebd

Findings 1–6 were accepted; fixes and regressions are described above. Finding 7 duplicates missing damage coverage and also correctly identifies that ship repair is still absent. No finding was dismissed as a false positive. Repair is still an unfinished part of the original task, not a passing test claim.

## Remaining work from the full brief

This is not completion of parts 0–9. `ShipRepair` is still a stub; snapshot controls, material-consuming reconstruction and protection-event checks remain. Six OBJ generator/dock models and their moving parts have not been made: existing block models are placeholders. The emitter visuals are small geometric nodes, not finished family-specific drone models. Dedicated generator/dock sound loops and the full sound-event table are not implemented. Upgrade progression, adaptation journal, all family upgrades and CC peripheral controls remain.

Current capture scenes demonstrate static decks inside an Aeronautics-enabled client. They do not prove hull-shield behaviour on a flying/rotating assembled ship. The ship hive's existing Sable scene is not evidence for the new generator. Custom shaders can be compiled and photographed in this client, but its Veil indirect-sphere compute-shader error is separate and still present.

Visual differences from the references: the hull cells are mostly hexagons with irregular polygons at topology changes; no high-detail marble/gold generator, orbiting galaxy spheres, unfolding masts or rune dock exists yet. The shield material and impact-wave pass are a foundation, not a claim of reference parity.
