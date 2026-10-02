Audited **`af912ab`**, with a clean working tree. No files changed; no Gradle, clients, or tests run. Counts below are derived from code, not measured FPS/MSPT. Allocation estimates are before possible JIT elimination.

The best opportunities are **lossless hive-state encoding, geometry/wave reuse, and lossless OBJ compaction**.

1. **Hive synchronization repeats large components — high gain, medium compatibility risk.**

   [HiveStackState.java:36](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/drone/HiveStackState.java:36) encodes all stored drones: **2,003 bytes for 2,000 pristine units**. The 250-flight limit does not limit this component. [ArmageddonController.java:318](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/server/ArmageddonController.java:318) changes battery energy during charging, potentially every tick.

   Installed Curios 9.5.1 bytecode confirms that changed stacks send the **whole stack patch** to tracking players and self. At 20 changes/s, the pristine hive component alone contributes **40 KB/s/recipient before transport compression**, and decoding constructs **40,000 Unit records/s/client**. `HiveCombatState.java:47` adds 66–67 bytes per shot, up to 100 shots; a populated stack can approach **10 KB/update**.

   **Fix:** bounded, lossless run-length/default-unit encoding; share immutable pristine units; compact timestamps without rounding. Preserve save formats, update timing, and every state value. Coordinate the network-format version. Transport compression already handles repeated bytes well, so CPU/allocation savings may exceed wire-bandwidth savings.

2. **Hive procedural geometry and frame bookkeeping — very high CPU/GC potential, medium risk.**

   [HiveModeVisual.java:557](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/HiveModeVisual.java:557) emits up to **62,400 vertices per black hole/frame**, before containment tori. Its 3,840 lines call `GlowBrush.java:54`, producing approximately **57,600 temporary Vec3s**, plus roughly 24,576 disk-corner intermediates. `GlowBrush.java:129` also rebuilds sphere arrays/trigonometry.

   [HiveVisualRenderer.java:201](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/HiveVisualRenderer.java:201) repeats roughly **4,000 unit checks/frame** for lane selection, plus up to 2,000 return checks. `HiveModeVisual.java:73` reconstructs partitions for separate rendering operations.

   **Fix:** scalar vertex emission, cached unit geometry/trigonometry, reusable flat buffers, one partition calculation/frame, and lane/return selection cached by state identity and tick. Keep interpolation per frame. Cull only conservatively bounded invisible geometry while preserving event/light processing.

   Preserve every segment, vertex order, alpha and camera-facing width. These vertices share batches: **62,400 vertices does not mean 62,400 draw calls**.

3. **Shield waves repeatedly calculate identical results — high gain, low/medium risk.**

   [ShieldGlow.java:82](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/ShieldGlow.java:82) processes **11,904 halo vertices**, calculating ripple height twice per vertex. With 12 waves, that is up to **285,696 wave-profile evaluations/frame**, involving `acos`/`exp`. A ≤2,145-direction grid cache would remove roughly **90% of those evaluations**.

   [ShieldShellVisual.java:203](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/ShieldShellVisual.java:203) similarly duplicates wave work across 3,122 dome vertices and allocates position/color arrays, small arrays, vectors and response objects. Honeycomb rendering at `:125` adds thousands more intermediates.

   **Fix:** calculate wave height once per unique direction/shield/frame; reuse it for displacement and colour. Precompute impact age/fade and use reusable primitive scratch storage. Preserve duplicated seam contributions and emitted triangle order. Keep caches isolated between shields.

4. **Post-effects consume substantial GPU work — high conditional gain, medium risk.**

   [ArmageddonVolume.java:343](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/ArmageddonVolume.java:343) submits **four passes per Twins blast**; `:406` submits **five per Mana blast**. Volume rendering already uses **⅓ width × ⅓ height**. RF uses one shared grading pass plus up to six local passes; `BlackHoleLens.java:118` can submit 24 full-screen passes.

   [armageddon_volume.fsh:108](C:/dev/EX-twins-perf/src/main/resources/assets/relics_addon/shaders/core/armageddon_volume.fsh:108) uses **36 march steps**: up to **8.29 million sample iterations/blast/frame at 1080p**, before early exits. Mana has conditional 18/22/24/32-step loops. The composite performs nine texture samples per full-resolution pixel—**18.66 million samples/pass at 1080p**.

   **Fix:** retain all steps and resolution. Restrict finite-support lens/volume work to conservative screen bounds, including composite-filter padding; skip passes proven neutral from their existing predicates. Preserve global grading, lighting and unbounded glow tails. Add a conservative radial rejection before wave noise at `armageddon_volume.fsh:131` and `mana_volume.fsh:251`; this can skip three-octave noise where the wave cannot contribute.

   Smaller wins: cache the screen quad rebuilt at `ArmageddonVolume.java:513`; reuse refraction-band scratch arrays at `ShieldRefraction.java:148`; cache Iris reflection handles at `:89`. Empty queues **already skip rendering**.

5. **Server terrain work and scans — large absolute cost, narrower safe savings.**

   [ArmageddonController.java:75](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/server/ArmageddonController.java:75) allows **12,000 carve + 6,000 bore writes/tick**, with up to 48,000 probes for each operation. Those writes also generate lighting, chunk and network work. Pull queries reach radius 135 (`:433`); damage queries reach radius 256 (`:625`).

   Reducing quotas or scan cadence would change behaviour. A safe target is `:552`: radius-90 column preparation creates approximately **25,447 `long[2]` objects**, around 1 MB of transient structures. Use packed primitive columns and preserve distance/tie ordering.

   [HiveCombatController.java:438](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/server/HiveCombatController.java:438) scans radius 32 **every idle tick**. Return immediately when retained targets already fill the limit, avoiding unnecessary queries/sorts. [ShieldProjectileInterceptor.java:70](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/server/ShieldProjectileInterceptor.java:70) scans all dimension players per projectile/tick; use spatial candidates while preserving its exact position predicate and interception ordering.

   **Risk:** medium around query ordering and block-update semantics. Drones are virtual state, **not 2,000 pathfinding entities**.

6. **OBJ duplication — strong load/memory/size improvement, low risk.**

   [build_rf_meshes.py:78](C:/dev/EX-twins-perf/tools/build_rf_meshes.py:78), `generate_swarm_lod.mjs:72`, and `build_mana_shield_mesh.mjs:95` emit duplicated position/normal records.

   In-memory exact-string deduplication and independent face-index remapping produced:

   | Metric | Current | Lossless candidate |
   |---|---:|---:|
   | 14 OBJ files | 15,924,697 B | 5,143,159 B |
   | Position + normal records | 383,848 | 76,933 |
   | Estimated compressed OBJ data | 1,898,396 B | 1,031,133 B |

   Expanded face/material/group equivalence checks preserved **all 59,926 faces**. This removes about **307,000 parsed vector records**—roughly 7 MiB of vector objects before parser/list overhead—and an estimated **0.83 MiB from packaged assets**.

   **Fix:** reproduce this in the generators, preserving numeric strings, independent indices, normals, UV seams, materials, winding and ordering.

   Total source assets: models **16.09 MB**, sounds **4.125 MB**, textures **0.183 MB**, shaders **0.091 MB**. PNGs expand to approximately **10.53 MB RGBA**, before mipmaps/atlas overhead. No release JAR exists here; compressed assets estimate approximately **6.22 MB**, excluding classes/data/ZIP overhead. All OBJs remain referenced. Long Armageddon sounds already stream; sound recompression offers little lossless gain.

7. **Item-model identities and invariant poses — moderate CPU/allocation gain, low/medium risk.**

   [AnimatedRelicItemRenderer.java:199](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/AnimatedRelicItemRenderer.java:199) constructs model identifiers per rendered part. `:170` samples each Twins shield facet twice; [TwinsFacetPose.java:30](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/TwinsFacetPose.java:30) recreates constant geometry alongside animated values.

   Six detailed Twins drones mean **138 part lookups and about 600 pose/point constructions/frame**. Existing detail budgets limit this; do not extrapolate detailed models to all 2,000 stored drones.

   **Fix:** static part IDs, precomputed facet bases, and one pose sample per facet/time. Invalidate any baked-model cache on reload. There are 109 additional models, 104 OBJ-backed, sharing 14 OBJs; **no per-frame OBJ baking was found**. Repeated parsing during reload requires profiling before claiming it.

8. **EffectLights reduction is bounded but allocation-heavy — secondary gain, low/medium risk.**

   [EffectLights.java:26](C:/dev/EX-twins-perf/src/main/java/dev/hurtify/relicsaddon/client/EffectLights.java:26) caps 256 frame reports, 64 flashes and 24 output lights. Its repeated clustering at `:102` can approach **204,160 seed comparisons/tick**, with nested-list allocation. `EffectLightPool.java:25` constructs/sorts up to 576 matching pairs/tick.

   **Fix:** fixed-capacity primitive scratch storage and pair indices, retaining stable ordering and identical merge results. Keep caps, cadence and movement thresholds unchanged. Measure section rebuilds as well as reducer time.

   Photon definitions are already cached and reload-invalidated (`ExFx.java:283,318`); runtimes start per event, not per frame. Its bounded bookkeeping is lower priority than geometry.

The following **eight worktrees have separate file ownership**. Measurements are proposed for implementation; none were run during this audit. Java paths below are relative to `src/main/java/dev/hurtify/relicsaddon/`.

| Subtask | Files touched | Expected gain; before/after measurement | Concurrent-work overlap |
|---|---|---|---|
| **1. Lossless hive codecs and immutable-state cleanup** | `drone/HiveStackState`, `HiveCombatState`; protocol-version registration; `NetworkCodecCheck` | Reduce encoding/decoding and unit allocations; lazy-copy unchanged state. Exact codec/save round trips; raw/compressed bytes, packets/s and allocation counters. | No listed files |
| **2. Hive geometry and frame caches** | `client/HiveModeVisual`, `GlowBrush`, `HiveVisualRenderer`, `HiveCombatVisual`; `drone/HiveShapes`, `HiveFormation` | Largest hive CPU/GC opportunity. JFR allocations, frame p95, vertex/batch counters; fixed-time comparisons with 250 deployed/2,000 stored, all modes and retargets. | **after hive-visual merge** |
| **3. Shield wave and geometry reuse** | `client/ShieldGlow`, `ShieldShellVisual`, `ShieldGeometry`, `ShieldVisualRenderer`, `ShieldResponse`, `ShieldRipple` | Roughly 90% fewer halo wave evaluations; fewer arrays. Profile counters and existing shield checks; compare 0/1/12 waves, seams, inside/outside views. | No listed files |
| **4. Exact post-effect work elimination** | `client/ArmageddonVolume`, `BlackHoleLens`, `ShieldRefraction`; relevant `shaders/core/*.fsh` | Less off-screen/neutral pass work, composite sampling and unnecessary noise. GPU timings, draws/blits, fixed-frame image differences at 1080p/4K and screen edges. | **after hive-visual merge** — `ArmageddonVolume` |
| **5. Server query and terrain-preparation efficiency** | `server/HiveCombatController`, `ShieldProjectileInterceptor`, `ArmageddonController`; relevant GameTests | Better projectile/player scaling and fewer preparation allocations. Query/candidate counters, tick p95/p99; identical targets, interceptions, block coordinates and modification ticks. | **after hive-visual merge** — `ArmageddonController` |
| **6. Lossless OBJ compaction** | `assets/relics_addon/models/item/*.obj`; the three generator files above; asset validation tooling | OBJ source −67.7%; estimated JAR contribution −0.83 MiB. Expanded-face hashes, archive bytes, cold-load/reload allocations and captures. | No listed files |
| **7. Model-ID and pose caching** | `client/AnimatedRelicItemRenderer`, `TwinsFacetPose`; optionally `RelicAnimationPose`, `HiveShellPose`, `ClientEventRegistrar` | Remove repeated IDs/static geometry. Allocation/sample counters, existing pose checks, fixed-time captures before/after reload. | No listed files |
| **8. Light-reduction scratch storage** | `client/EffectLights`, `client/light/EffectLightPool`; optionally `fx/ExFx`, `PointEffectExecutor` | Bounded allocation reduction. Existing light checks; exact output comparison for 0/24/256+64 reports, reducer time and section rebuild counts. | **after hive-visual merge** — `EffectLights` |

`HiveJuice` and `HiveProjectiles` are absent from this snapshot; recheck ownership after their merge. No proposed task requires changing particle density, mesh detail, raymarch step counts, gameplay timing, or the mod’s architecture.
