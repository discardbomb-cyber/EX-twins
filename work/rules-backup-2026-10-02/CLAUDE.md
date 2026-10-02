# EX-twins — read this first, explore last

NeoForge 1.21.1 mod (id `relics_addon`, name EX-twins, Java package `dev.hurtify.relicsaddon`).
Full map, build commands and pitfalls: skill `ex-twins-modding` (`.claude/skills/ex-twins-modding/SKILL.md`,
sections "Architecture map" and "Pitfalls already hit"). Read those sections instead of sweeping the tree.

## Token rules (the author is on a tight budget)
- Never grep/read the whole tree. Go straight to the package below, `grep -l` before `Read`, read line ranges.
- Never paste long build/test logs: filter with `grep -E "error|FAIL|required tests|BUILD"`.
- Never read `work/*.md` prompts in full (50+ KB); grep their `^#` headings and read one section.
- Never read subagent `.output` files or Temp transcripts.
- Answers to the author: Russian, short. No screenshots/GIFs unless asked.
- Subagents: one at a time, model haiku/fable, prompt names exact files and a ≤8-line report.
- Codex is PAUSED (quota out) until the author says otherwise.

## Where things are (`src/main/java/dev/hurtify/relicsaddon/`)
- `domain/` (65) pure rules, no Minecraft: hive, shield, energy, geometry (`Vec3d`). Checked by
  `DomainRulesCheck` and the architecture gate in `build.gradle` (`--max-legacy/--min-domain` limits).
- `client/` (61) renderers, Photon/Veil visuals, screens. Never referenced from common code.
- `server/` controllers (Shield, Armageddon, hives); `network/` payloads; `registry/` items/blocks;
  `ship/` Create Aeronautics/Sable hives; `adapter/out/persistence` codecs; `gametest/` GameTests.
- Golden masters: `src/test/resources/golden/*` — a diff there is a behaviour change, explain it.
- Hexagonal migration spec: `docs/architecture/hexagonal-migration.md` on main (stages S0–S13, S6 closed on feature/shield-repel).

## Hard warnings
- **Network payloads must be registered on BOTH sides.** Never wrap `registrar.playToClient(...)` in
  `FMLEnvironment.dist.isClient()` — the server then lacks the channel and clients are kicked
  ("channel relics_addon:armageddon_blast absent on server"). Keep client classes inside the handler lambda.
- Target NeoForge **21.1.251** (author's server). `neo_version` in gradle.properties is also the minimum in mods.toml.
- Server needs Photon + LDLib2 installed too (they register packets).
- Changing the mod id breaks worlds; only with aliases (`relics_addon:*` → new id). Not done yet.
- Commits: no `Co-Authored-By` / AI lines. Push only when asked. Never force-push.
- Many worktrees `C:\dev\EX-twins-*`; other sessions may own them. `git status` first, never edit a dirty
  checkout you did not dirty. Never kill Minecraft clients you did not start.
- C: is nearly full: screenshots/captures go to `D:\ex-twins-captures` (junctions in `run-scenario/screenshots`).
- Excluded from release by the author: ship shields (feature/ship-shields, ship-shield-models), scythe +
  spear (feature/twins-spear, idea dropped).

## State 2026-10-02
- GitHub release v1.0.0 = main 3744118 (swarm/shields merge + NeoForge 21.1.251). It has the
  client-only `armageddon_blast` bug above; the fix (restore `ArmageddonPayloads.java` from a963f7c with
  import `domain.hive.HiveType`) is uncommitted in `C:\dev\EX-twins-merge`, build/tests not yet run.
