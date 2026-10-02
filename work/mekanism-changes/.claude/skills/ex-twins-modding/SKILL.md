---
name: ex-twins-modding
description: Work on the EX-twins NeoForge 1.21.1 mod, including code, recipes, assets, builds, GameTests, and analysis of gameplay references. Use for changes to this repository.
---

# EX-twins modding

Read the repository's [AGENTS.md](../../../AGENTS.md) first. Work in the checkout selected for the current task; verify its branch and existing changes. Current user instructions override historical chat requests and dated project snapshots.

## Architecture map

Read only the relevant subsystem in [docs/project-map.md](../../../docs/project-map.md). The old docs/readme-ru checkout and main after S6 have different package layouts; do not copy legacy imports over domain imports.

## Build, run, test

Use [docs/development.md](../../../docs/development.md), sections "Build, run, test" and "Какие проверки выбирать". The target is NeoForge 21.1.251. Documentation-only edits need link and consistency checks, not Minecraft runs.

## Pitfalls already hit

Read "Pitfalls already hit" in [docs/development.md](../../../docs/development.md) before code changes. Payload registration must exist on both sides; common code must not load client implementation classes on the server. Counts and indices use VarInt/VarLong. Read the ship/shader section only for those systems, and the resources section only when changing assets.

Rules live in AGENTS.md, technical procedures in docs/development.md, and dated state in docs/project-map.md. Update the owning document instead of adding conflicting copies here. Commits must not contain AI attribution; push only when the user asks.
