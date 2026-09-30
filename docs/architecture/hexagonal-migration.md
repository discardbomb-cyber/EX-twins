# EX-twins hexagonal migration: final specification (corrected)

## 0. Scope, conventions and rules for every stage

**Where the work happens**
- Repository: `C:\dev\EX-twins-repel`, branch `feature/shield-repel`, starting commit `d8931c9`. S0 tags that commit as `hexagon-baseline`.
- Mod id `relics_addon`, root package `dev.hurtify.relicsaddon`.
- NeoForge 21.1.252, Minecraft 1.21.1, Java 21, ModDevGradle 2.0.107, Gradle 8.14.5.

**Path shorthands**

| Shorthand | Path |
|---|---|
| `main/` | `src/main/java/dev/hurtify/relicsaddon/` |
| `test/` | `src/test/java/dev/hurtify/relicsaddon/` |
| `probe/` | `src/releaseProbe/java/dev/hurtify/releaseprobe/` |
| `golden/` | `src/test/resources/golden/` |

Package names are relative to `dev.hurtify.relicsaddon`.

Every command runs in Git Bash:
```bash
cd /c/dev/EX-twins-repel
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
```

**R-1. One stage per session.**
- Start from a clean tree: `git status` may show only the untracked `.claude/worktrees/`.
- Stage N+1 begins only after stage N is committed with its gate green.
- Do not work in the `wf/*` worktrees during the migration. They are already merged.

**R-2. Standard gate, run last in every stage.** Command: `./gradlew build runGameTestServer`.
- It must end BUILD SUCCESSFUL.
- `build` runs `check`, which includes every verify* task and verifyReleaseContents.
- runGameTestServer passes only if `run-gametest/logs/latest.log` is fresh and says exactly `All N required tests passed`, where N is `expectedGameTests` in build.gradle (36 until S1, 44 from S1).
- Time budgets: build about 3 min, GameTests about 2 min, each client run up to 10 min. Use a 600000 ms tool timeout.

**R-3. No behaviour change.** Transpose every flow line by line and keep:
- statement order and side-effect order;
- float versus double types (for example `absorbed * ABSORB_PER_HP` stays a float before `ceil`);
- ceil semantics exactly as written: `Trig.ceil(float)` / `Trig.ceil(double)` wherever the code calls `Mth.ceil` (ShieldController hit cost and absorb drain, ShieldProjectileInterceptor's final arrow ceil and absorb drain, the charge flight times), and `Math.ceil` wherever the code calls `Math.ceil` (EffectTrim.cost, HealingPolicy.cost, DroneDamage.explosionDamage, the crit step of ProjectilePolicy.impactCost). Never swap one for the other;
- the association of every product and quotient as the code writes it (for example the hive upgrade effects compute `r * (cap / 3D)`, which is not `r * cap / 3D` in the last bit);
- short-circuit order;
- laziness: config filters, tags and line of sight are evaluated only where they were before.

The quirks in §12.3 are preserved. The only permitted deviation is D1 (§12.4).

**R-4. Resources and goldens are frozen.**
- Never edit `src/main/resources/**` or `src/generated/**`. verifyContracts hashes both.
- Never edit `golden/**`, except:
  - recording in S0;
  - appending to `golden/literals-retired.txt` with the stage and a reason;
  - authoring `golden/wiring.txt` in S7.
- Retired literals include record component lists: a record's `toString` passes its component names as one CONSTANT_String such as `type;stack`, so replacing or deleting a record retires that string.

**R-5. Moves use `git mv` in a rename-only commit.**
- That commit may change only:
  - package lines, imports and qualifiers;
  - the declared name of a type the move renames (S3 `DroneStackState` → `LegacyDroneStackState`) and the references to it;
  - access modifiers that the move must widen for the build to compile;
  - build.gradle mainClass strings and the path constants in `tools/validate_hive_assets.py`.
- Known widenings:
  - S2 `ShieldTopology.legacyRegionFor` → public;
  - S8 `ManaSources` (the class and its static `draw`) → public;
  - S11 the five `AddonClientConfig` accessors → public.
- Every commit compiles: run at least `./gradlew compileJava compileTestJava` before committing.
- Check the rename commit with `git diff -M --stat HEAD~1`.
- Behaviour edits go in a later commit of the same stage.

**R-6. GameTests are the safety net.**
- Class names, method names (the test ids), templates, `timeoutTicks`, assertion messages and numbers never change.
- Allowed edits: imports in move stages, the named edits in S5 and S10, and the API switch in S12.

**R-7. Facades.**
- A legacy class keeps only the public members still used outside the migrated slice, as `@Deprecated(forRemoval = true)` one-line delegations. They delegate to `ModRuntime.get()` ports, to pure domain rules, or as constant aliases to `EnergyCosts` / `ModDamageTypes`. Outside users are GameTests, galleries, client code not yet migrated, and legacy code of later slices.
- Public members with no remaining outside caller are removed when the facade is created.
- Conversion goes through `McViews`, `StackDevice` and `McVectors`.
- Facades contain no logic. The only exceptions are verbatim legacy bodies that have no port yet, each with an end date:
  - `HiveController.onPlayerTick` and its private `settle`, S8–S10;
  - `ShieldProjectileInterceptor.crossing` / `unobstructed`, S9–S11, used only by the client `ShieldThreatTracker`.
- All facades are deleted in S12.

**R-8.** After each stage, set the ArchitectureCheck arguments to the values the stage lists (§11).

**R-9.** `RuntimeFactory.create()` must not trigger class initialisation of AddonConfig, AddonClientConfig, ModDataComponents, ModItems, ModMenus, ModCreativeTabs, RelicSounds or ShieldTopology.
- Adapters touch these lazily, inside their methods.
- `ServerWiring.MOD_STEPS` entries are lambdas (`bus -> ModDataComponents.DATA_COMPONENTS.register(bus)`), never bound method references. Building the lists initialises nothing, and neither does reading them in WiringCheck.

**R-10. Views and handles compare with `equals`, never `==`.**
- Entity views compare the wrapped entity with `Entity.equals`, which compares network ids.
- Device handles compare the wrapped ItemStack by identity. ItemStack has no `equals`; give `StackDevice` explicit `==` / `System.identityHashCode` equality anyway.

**R-11. Stage report**, in the final message and the commit body:
- commands run and their outcomes;
- the counts ArchitectureCheck printed;
- retired literals with reasons;
- deviations (none except D1);
- for visual stages, the PNGs, sent with SendUserFile.

**R-12.** Commit per logical step with the attribution trailer. Never push unless the user asks.

**R-13. No annotations from outside `java.*` in domain or application code.**
- `@Nullable` in §4–§6 is documentation only: write it as a Javadoc note.
- Adapters and client code may use `javax.annotation.Nullable`.

## 1. Base, grafts and rejections

**Base: the Safety-first plan.**
- Oracles before moves; characterization GameTests; a pinned GameTest count.
- Facades until a single cutover; rename-only moves.
- Listener wiring as data, checked against a golden.
- Client rendering code stays in place; only its data acquisition moves behind ports.
- A pixel gate over the deterministic GIF galleries.

**Grafted from Purity-first:**
- The ring names: domain / application (port.in, port.out) / adapter.in, adapter.out / bootstrap.
- Inbound use-case interfaces, and outbound live handle views.
- The bit-exact math kernel (Vec3d, Box, Trig) with a differential check.
- Codec classes in adapter.out.persistence.
- AddonIds, and the AddonClientConfig and ModDamageTypes placement.
- The bytecode ArchitectureCheck with source pass, canary and minimum counts.
- Broad golden masters.

**Grafted from Runtime-first:**
- The optional compile-time `core` source set (S13).
- Resolution of string-referenced class names (EntrypointCheck).
- Read-model queries for items and the console screen.
- Its hot-path allocation work is a follow-up, not part of this migration.

**Rejected:**
- Pure per-vertex shading models and a JOML port: no strong enough oracle.
- Config snapshots rebuilt on reload. ShieldConfigGameTests swap lists with `ConfigValue.set`, which fires no event.
- A single merged PlayerTick listener.
- Struct-of-arrays swarm state.
- Allocation gates.
- Bug fixes during the migration.
- A Python frame tool. `python` here is only the Microsoft Store stub, so use Node + pngjs (tools/node_modules).

## 2. Target package tree
```
dev.hurtify.relicsaddon
├─ RelicsAddon           @Mod entry, FQCN fixed. MOD_ID = AddonIds.MOD_ID (compile-time constant "relics_addon");
│                        LOGGER = AddonIds.LOGGER. Registers both configs, calls bootstrap, keeps the reflective client hook.
├─ bootstrap             composition root
│   ModRuntime           installed graph; static get(); accessors return inbound ports; package-private events()
│   RuntimeFactory       constructor wiring: outbound adapters -> services -> inbound event/command adapters
│   ServerWiring         MOD_STEPS and GAME_HOOKS as data; install(), registerModBus(IEventBus), registerGameBus(IEventBus, ModRuntime)
│   GameHook, ModBusStep, EventAdapters   records
├─ domain                PURE: JDK + domain only
│   math    Vec3d Box Trig
│   device  RelicRole DeviceUpgrade DeviceProgression DeviceStat DeviceStats ProgressionRules UpgradeRules WornSlot HiveWearRule
│   energy  DeviceEnergy(+ManaSource) EnergyCosts BatteryRules ExperienceCurve
│   combat  DamageKind
│   shield  ShieldTopology ShieldCellMove ShieldStackState ShieldCellDefense ShieldSettings Coverage ShieldField ShieldImpact
│           ShieldImpactHistory ShieldStats ShieldUpgradeEffects CellSelection DamageFacts DamagePassPolicy HitImmunity EffectTrim
│           ProjectileFacts ProjectilePolicy CoverageRule BarrierPush StrikeRules ShieldStatus
│   hive    AttackMode HiveType HiveSettings HiveStackState HiveCombatState HiveSupportState HiveSlots HiveFormation HiveShapes
│           HiveUpgradeEffects SwarmRules DroneDamage SwarmStrike ShotLog ChargeFlight Hold HoldPolicy HealingPolicy
├─ application           PURE: JDK + domain + application
│   port.in   ManageDevice Progress ManagePower RunUpkeep DeviceQueries ShieldQueries ManageSwarm EquippedHive
│             AbsorbDamage IncomingDamage MaintainShield HoldBackHostiles InterceptProjectiles GuardEffects EffectApplication
│             EffectVerdict ManageShieldSettings ShieldStatusLine RadiusChange CoverageChange RunSwarm ContainTargets ConfigureHive
│             OperateConsole ConsoleCommand ForecastThreats ShieldThreat
│   port.out  WorldRef EntityView Creature MobView PlayerView ProjectileView EffectView ExplosionView DeviceHandle SlotAddress
│             CharmSlot SegmentHit DeviceStore ShieldStore HiveStore Equipment WorldQuery CreatureActions EntityControl
│             ProjectileControl ProjectileLedger EntityMemories EntityMemory Cues UiCue Notifier ServerSettings ManaSupply IdSource
│   combat    Relations
│   device    DeviceService ProgressionService ExperienceWindows DeviceQueryService DeviceConsoleService
│   energy    PowerService UpkeepService
│   shield    ShieldLocator ShieldImpacts ShieldDefenseService ShieldMaintenanceService ShieldBarrierService
│             ProjectileInterceptionService EffectGuardService ShieldCommandService ThreatForecastService
│   hive      HiveRoster HiveTaskService HiveHealingService ContainmentService ChargeFlights HiveCombatService
├─ adapter               framework side, server-safe (no client libraries)
│   AddonIds             MOD_ID, LOGGER, static ResourceLocation id(String path)
│   in.event       PowerEvents ShieldDamageEvents McIncomingDamage McDamageFacts ShieldTickEvents ProjectileEvents
│                  EffectGuardEvents McEffectApplication HiveEvents ContainmentEvents
│   in.command     ShieldStatusCommand
│   in.item        AutonomousRelicItem HiveRelicItem ComponentItem
│   in.menu        DeviceControlMenu ConsoleButtons
│   in.network     OpenDevicePayload
│   in.capability  DeviceEnergyStorage
│   out.persistence HiveCodecs ShieldCodecs DeviceCodecs LegacyDroneStackState ItemComponentStore StackDevice RandomInstanceIds
│   out.world      McViews McEntityBase McEntity McCreature McMob McPlayer McProjectile McWorld McEffect McExplosion McVectors
│                  MinecraftWorldQuery MinecraftCreatureActions MinecraftEntityControl MinecraftProjectiles PersistentDataLedger
│                  WeakEntityMemories ActionBarNotifier
│   out.curios     CuriosEquipment
│   out.config     AddonConfig AddonClientConfig RegistryFilter ConfigServerSettings
│   out.mana       ManaSources
│   out.sound      RelicSounds SoundCues
│   registry       ModItems ModCreativeTabs ModMenus ModDataComponents ModDamageTypes
├─ client                Dist.CLIENT presentation adapter. The layout is unchanged, plus SwarmMath (S5).
│                        ClientEventRegistrar and client.light.DynamicLightsBridge keep their FQCNs.
│                        client.fx is the only Photon user; client.light is the only LambDynamicLights user.
└─ gametest (+ gametest.client)   dev only, excluded from the jar, location unchanged
```

New test tree:
- `test/contract/`: ClassFile, ArchitectureCheck, ContractCheck, EntrypointCheck, CodecGoldenCheck, GeometryGoldenCheck, WiringCheck.
- `test/domain/`: DomainRulesCheck; `math/MathKernelCheck`; `hive/HiveFormationCheck` (moved); `energy/BatteryRulesCheck` (was `power/DevicePowerCheck`).
- `test/application/ApplicationRulesCheck`.
- `test/adapter/out/persistence/NetworkCodecCheck` (moved); `test/adapter/out/config/RegistryFilterCheck` (moved).
- `test/client/**` is unchanged.

## 3. Dependency rules and enforcement

### 3.1 Classification

Each class file is classified by the prefix of its internal name.

| Class | Prefix |
|---|---|
| DOMAIN | `dev/hurtify/relicsaddon/domain/` |
| PORT (part of APPLICATION) | `dev/hurtify/relicsaddon/application/port/` |
| APPLICATION | `dev/hurtify/relicsaddon/application/` |
| ADAPTER_INJECTED | `dev/hurtify/relicsaddon/adapter/in/event/`, `dev/hurtify/relicsaddon/adapter/in/command/` |
| ADAPTER_OUT | `dev/hurtify/relicsaddon/adapter/out/` |
| ADAPTER | any other `dev/hurtify/relicsaddon/adapter/` class |
| BOOTSTRAP | `dev/hurtify/relicsaddon/bootstrap/` |
| ROOT | `dev/hurtify/relicsaddon/RelicsAddon` |
| CLIENT | `dev/hurtify/relicsaddon/client/` |
| GAMETEST_CLIENT | `dev/hurtify/relicsaddon/gametest/client/` |
| GAMETEST | `dev/hurtify/relicsaddon/gametest/` |
| LEGACY | any other `dev/hurtify/relicsaddon/` class |
| JDK | `java/` |
| CLIENT_LIBS | `net/minecraft/client/`, `com/mojang/blaze3d/`, `net/neoforged/neoforge/client/`, `org/lwjgl/`, `com/lowdragmc/`, `dev/lambdaurora/` |
| LIB | anything else |

FORBIDDEN_JDK, for DOMAIN and APPLICATION: `java/io/`, `java/nio/`, `java/net/`, `java/lang/reflect/`, `java/util/concurrent/`, `java/util/logging/`, `java/lang/Thread`, `java/lang/Runtime`, `java/lang/ProcessBuilder`, `java/sql/`, `java/awt/`, `javax/`.

### 3.2 Rules

"References" means type names found in the class file (see §3.3).

| Id | Rule |
|---|---|
| D1 | DOMAIN references only JDK (minus FORBIDDEN_JDK) and DOMAIN. |
| A1 | APPLICATION references only JDK (minus FORBIDDEN_JDK), DOMAIN and APPLICATION. |
| S1 | No DOMAIN or APPLICATION class declares a field with ACC_STATIC set and ACC_FINAL clear. |
| S2 | No DOMAIN or APPLICATION CONSTANT_String starts with `net.minecraft.`, `net.neoforged.`, `com.mojang.`, `com.lowdragmc.`, `top.theillusivec4.`, `dev.lambdaurora.`, `io.netty.` or `dev.hurtify.relicsaddon.` (reflection escapes). |
| X1 | Distribution safety: no class outside CLIENT and GAMETEST_CLIENT references CLIENT_LIBS or CLIENT. The only exception, until S11, is ROOT referencing `client/AddonClientConfig`. |
| X2 | Inside CLIENT, `com/lowdragmc/` is referenced only from `client/fx/`, and `dev/lambdaurora/` only from `client/light/`. |
| P1 | ADAPTER*, CLIENT and ROOT reference APPLICATION only through PORT, never service classes. |
| P2 | BOOTSTRAP classes other than `bootstrap/ModRuntime` are referenced only by ROOT, BOOTSTRAP and GAMETEST*. |
| P3 | `bootstrap/ModRuntime` is referenced only by ROOT, BOOTSTRAP, ADAPTER (not ADAPTER_OUT, not ADAPTER_INJECTED), CLIENT, GAMETEST* and LEGACY. |
| G1 | Nothing outside GAMETEST* references GAMETEST*. |
| L1 | The count of LEGACY top-level classes (class files with no `$`) is at most `--max-legacy`. DOMAIN and APPLICATION never reach LEGACY (implied by D1/A1). |
| C1 | With `--client-strict` (from S12, once `registry/*` has moved to `adapter/registry/`), CLIENT references no LEGACY, no `adapter/in/event/`, no `adapter/in/command/` and no `adapter/registry/ModDataComponents`. |
| M1 | Class counts: DOMAIN ≥ `--min-domain`, APPLICATION ≥ `--min-application`, ADAPTER* ≥ `--min-adapter`. |
| K1 | Canary: rule D1, applied to `dev/hurtify/relicsaddon/RelicsAddon.class`, must report at least one violation. Otherwise the parser is broken and the check fails. |

### 3.3 ArchitectureCheck (`test/contract/ArchitectureCheck.java`, JDK only)

It uses `test/contract/ClassFile.java`, which parses a class file with `DataInputStream`:
- magic, minor and major version;
- the constant pool:

  | Tag | Entry | Read |
  |---|---|---|
  | 1 | Utf8 | `readUTF` |
  | 3, 4 | Integer, Float | 4 bytes |
  | 5, 6 | Long, Double | 8 bytes, two slots |
  | 7 | Class | u2 |
  | 8 | String | u2 |
  | 9, 10, 11 | Field, Method and InterfaceMethod refs | u2 + u2 |
  | 12 | NameAndType | u2 + u2 |
  | 15 | MethodHandle | u1 + u2 |
  | 16 | MethodType | u2 |
  | 17, 18 | Dynamic, InvokeDynamic | u2 + u2 |
  | 19, 20 | Module, Package | u2 |

- access flags, this_class, super_class and interfaces;
- fields (flags, name, descriptor), skipping attributes by length.

Referenced type names are:
- (a) every Class entry name that does not start with `[`;
- (b) every match of `L([^;<>]+)[;<]` in every Utf8 entry that is not the payload of a String entry. This covers descriptors, generic signatures, annotation types and lambda and method-handle types.

String payloads are checked separately (rule S2).

The source pass runs over the `domain/` and `application/` directories under each `--sources` root. It skips a missing directory; they do not exist before S2.
- Strip comments and string and char literals.
- Fail on:
  - `import` or `import static` of anything other than `java.*`, `dev.hurtify.relicsaddon.domain.*` and (application only) `dev.hurtify.relicsaddon.application.*`;
  - any fully qualified name starting with `net.minecraft.`, `net.neoforged.`, `com.mojang.`, `com.lowdragmc.`, `com.google.`, `top.theillusivec4.`, `dev.lambdaurora.`, `io.netty.`, `org.lwjgl.`, `org.joml.`, `org.slf4j.`, `javax.`, or `dev.hurtify.relicsaddon.` followed by anything other than `domain.` (and, for application, `application.`). Explicit roots avoid matching field access on locals named `top`, `io` or `net`;
  - `System.out`, `System.err`, `System.currentTimeMillis`, `System.nanoTime`, `System.getProperty`, `System.getenv`, `new Thread`, `Thread.`, `Math.random`, `ThreadLocalRandom`, `new Random()`.
- Report `file:line`.

Arguments:
```
<classes dir> [<classes dir> ...] --sources <root> [--sources <root> ...] --max-legacy <n> --min-domain <n> --min-application <n> --min-adapter <n> [--client-strict]
```
Until S13 the task passes `build/classes/java/main` and `--sources src/main/java/dev/hurtify/relicsaddon`.

Output:
- one line per violation: `from -> to [rule]`;
- the per-layer counts and the legacy count.

Any violation, a failed canary or a failed minimum throws `AssertionError`, which fails `check`.

### 3.4 Checks wired into `check`

| Task | Main class | Classpath | Added | Purpose |
|---|---|---|---|---|
| verifyArchitecture | contract.ArchitectureCheck | `sourceSets.test.output.classesDirs` | S0 | §3.2 |
| verifyContracts | contract.ContractCheck | test runtime | S0 | resource hashes and presence of literals |
| verifyEntrypoints | contract.EntrypointCheck | test runtime + compileJava | S0 | string-referenced classes resolve |
| verifyCodecGolden | contract.CodecGoldenCheck | test runtime + compileJava | S0 | byte and NBT goldens |
| verifyGeometryGolden | contract.GeometryGoldenCheck | test runtime + compileJava (S0–S4), then test runtime only (S5+) | S0 | bit-exact geometry |
| verifyTwinsShieldLayers | client.TwinsShieldLayerAnimationCheck | test runtime | S0 | wires an existing orphan check |
| verifyMathKernel | domain.math.MathKernelCheck | test runtime + compileJava | S2 | Vec3d, Box and Trig against Vec3, AABB and Mth |
| verifyDomainRules | domain.DomainRulesCheck | test runtime only | S6 | pure rules |
| verifyWiring | contract.WiringCheck | test runtime + compileJava | S7 | listener order |
| verifyApplication | application.ApplicationRulesCheck | test runtime only | S8 | services against fakes |

- "test runtime" means `sourceSets.test.runtimeClasspath`, which has no Minecraft on it.
- "compileJava" means `tasks.named('compileJava').get().classpath`.
- A pure check that runs on the test runtime alone throws NoClassDefFoundError if domain or application code reaches Minecraft. This is the runtime purity proof.
- verifyArchitecture and verifyContracts read `build/classes/java/main`, so they depend on `classes`.

mainClass edits to existing tasks:
- verifyNetworkCodecs (S3);
- verifyHiveFormation (S5, classpath becomes test runtime only);
- verifyDevicePower (S6, becomes `dev.hurtify.relicsaddon.domain.energy.BatteryRulesCheck`, test runtime only);
- verifyRegistryFilter (S12).

**ContractCheck** has two modes. `--record` writes the goldens; the default mode compares against them.
- `golden/resources.sha256`: `<sha256>  <path>` for every file under `src/main/resources` and `src/generated/resources`, sorted by path. Any difference fails.
  - Text files are hashed after converting CRLF to LF, so the golden does not depend on `core.autocrlf` (this checkout has `* text=auto` and autocrlf=true, which gives CRLF working files).
  - Binary files are `.png`, `.gif`, `.ogg`, `.jar` and `.nbt`, as in `.gitattributes`.
- `golden/literals.txt`:
  - Built from every CONSTANT_String payload of every class under the scanned class directories: `build/classes/java/main` (gametest included), plus `build/classes/java/core` from S13.
  - Payloads containing `\u0001` or `\u0002` (indy concat recipes) are split into their constant parts, and parts shorter than 3 characters are dropped. The result is sorted, deduplicated and escaped.
  - Record `toString` component lists (`a;b;c`) are CONSTANT_Strings and so are literals too.
  - Check mode: each literal must occur as a substring of some Utf8 entry of some current class, unless it is listed in `golden/literals-retired.txt` as `literal<TAB>stage<TAB>reason`.

**EntrypointCheck** asserts that:
- `Class.forName("dev.hurtify.relicsaddon.client.ClientEventRegistrar", false, loader)` has `public static void register(net.neoforged.bus.api.IEventBus)`;
- RelicsAddon's constant pool contains that exact string;
- `META-INF/neoforge.mods.toml` contains `"lambdynlights:initializer"="dev.hurtify.relicsaddon.client.light.DynamicLightsBridge"`;
- that class loads without initialisation and implements `dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer`.

### 3.5 Review rules no checker sees

- Side-effect order inside each use case matches the transposed handler.
- The item store is re-read wherever the old code re-read the stack. Nothing caches item state across port calls.
- Domain APIs take values or suppliers, never views or handles. `DamageFacts` and `ProjectileFacts` are domain interfaces that adapter views implement.
- Every stage report lists retired literals.

## 4. Domain (pure)

### 4.1 domain.math (S2)

All three classes are exact replicas of the 1.21.1 classes, verified with `build/moddev/artifacts/neoforge-21.1.252-sources.jar`.

**Vec3d**: `public final class` with `public final double x, y, z`, `public static final Vec3d ZERO`, and accessor methods `x()`, `y()`, `z()`.
- Arithmetic:
  - `add(Vec3d)`, `add(double,double,double)`;
  - `subtract(Vec3d)` = `subtract(v.x, v.y, v.z)`, and `subtract(dx,dy,dz)` = `add(-dx,-dy,-dz)`;
  - `multiply(double,double,double)`, `scale(f)` = `multiply(f,f,f)`, `reverse()` = `scale(-1.0)`.
- `normalize()`: `d = Math.sqrt(x*x+y*y+z*z); return d < 1.0E-4 ? ZERO : new Vec3d(x/d, y/d, z/d)`.
- Products and lengths: `dot`, `cross` (the Vec3 formula), `length`, `lengthSqr`, `horizontalDistance`, `horizontalDistanceSqr`.
- Distances: `distanceTo(p)` (with `d0 = p.x - x`), `distanceToSqr(Vec3d)`, `distanceToSqr(double,double,double)`.
- `lerp(to, delta)`: `Trig.lerp(delta, x, to.x)` per axis.
- `static directionFromRotation(float xRot, float yRot)`: Vec3's float formula, `f = cos(-yRot*(float)(PI/180) - (float)PI)` and so on, through `Trig.sin` and `Trig.cos`.
- `equals` uses `Double.compare` on each axis, `hashCode` uses the doubleToLongBits formula, and `toString` returns `"(" + x + ", " + y + ", " + z + ")"`.

**Box**: public final `minX..maxZ`.
- Constructors `(x1,y1,z1,x2,y2,z2)` store `Math.min`/`Math.max`; `(Vec3d, Vec3d)` delegates to it.
- `inflate(double)` and `inflate(dx,dy,dz)`.
- `expandTowards(Vec3d)`, with AABB's sign branches.
- `contains(Vec3d)` and `contains(x,y,z)`, half-open: `>= min && < max`.
- `center()` = `new Vec3d(Trig.lerp(.5,minX,maxX), …)`.

**Trig**:
- A private static final `float[65536]` table, filled with `(float) Math.sin((double) i * Math.PI * 2.0 / 65536.0)`.
- `sin(float v)` = `SIN[(int)(v * 10430.378F) & 65535]`.
- `cos(float v)` = `SIN[(int)(v * 10430.378F + 16384.0F) & 65535]`.
- `ceil(float)`, `ceil(double)` and `floor(double)` with Mth semantics: `(int) v`, then adjust.
- `lerp(delta, start, end)` = `start + delta * (end - start)`.

### 4.2 domain.device

- **RelicRole** (moved in S2, unchanged): all 9 constants, colours, `repairInterval`, the `shields()/drones()/hives()` copies, `EQUIPMENT_SLOT = "charm"`. `abilityId()` and `slot()` are dropped in S13.
- **DeviceUpgrade** (moved in S2):
  - The constructor takes id literals: `"damage_distribution"`, `"shield_gather"`, `"shield_restoration"`, `"shield_stabilization"`, `"combat_protocol"`, `"support_protocol"`, `"recovery_protocol"`.
  - Bits 0..6 and required levels 2, 2, 3, 4, 2, 3, 4.
  - `shield()`, `availableFor`, `availableUpgrades` and `byId` are unchanged.
  - `ShieldUpgrades.*` and `HiveUpgrades.*` id constants become `= DeviceUpgrade.X.id()`.
- **DeviceProgression** (moved in S4): record, `normalized`, `rank`, `withRank`, `withPoints`, `MAX_LEVEL = 10`, `DEFAULT`.
- **DeviceStat** (S6): enum `BUFFER_CAPACITY("buffer_capacity")`, `RADIUS("radius")`, `DRONE_COUNT("drone_count")`, `DRONE_HEALTH("drone_health")`, `ATTACK_DAMAGE("attack_damage")`, `ATTACK_INTERVAL_MAX("attack_interval_max")`, `COOLDOWN("cooldown")`. Methods: `id()` and `static Optional<DeviceStat> byId(String)`.
- **DeviceStats** (S6): `static double stat(Optional<RelicRole> role, int level, DeviceStat stat, double fallback, double min, double max)`.
  - It is the switch from `RelicRuntime.stat`, verbatim, including the int arithmetic before `/ 10.0`.
  - `hiveValue`: an empty role gives 0; a non-hive role throws from `HiveType.of`, as today. It is evaluated only for ATTACK_DAMAGE and COOLDOWN.
  - Result: `Double.isFinite(v) ? Math.clamp(v, min, max) : fallback`.
- **ProgressionRules** (S6):
  - `experienceToNext(l)` = `60 + 40*l + 20*l*l`.
  - `gain(float v)` = `Math.clamp(Math.round(v * .25F), 1, 3)`.
  - `addExperience(DeviceProgression before, int gain)`: the level-up loop, while `level < MAX_LEVEL` (experience keeps accruing at level 10), returning `new DeviceProgression(experience, level, points, before.upgrades())`.
- **UpgradeRules** (S6):
  - `canPurchase(RelicRole role, DeviceUpgrade u, DeviceProgression p)` repeats today's checks exactly, in order:
    1. `role.isShield() != (u.bit() <= STABILIZATION.bit())` → false;
    2. `u == GATHER && role == TWINS_SHIELD` → false;
    3. `level < required || points < 1 || rank >= 3` → false.

    There is no STABILIZATION role check.
  - `afterPurchase(p, u)` = `withRank(u.id(), rank+1).withPoints(points-1)`.
  - `canBuy(u, p)` = `points > 0 && level >= required && rank < 3`.
  - `enum Button {MAXED, NEEDS_LEVEL, NO_POINTS, BUY}` and `button(u, p)`, which checks in that order.
- **WornSlot** (S6): `record WornSlot(String identifier, int index)`.
- **HiveWearRule** (S6): `allows(Optional<List<WornSlot>> worn, String identifier, int index)`. An empty Optional means allow. Otherwise every worn slot must equal the target slot.

### 4.3 domain.energy

- **DeviceEnergy** (codecs removed in S3, moved in S4): the record, clamps and with* copies are unchanged. `ManaSource {AUTO, MAGIC, EXPERIENCE}` has `id()` and `public static byId` (unknown → AUTO).
- **EnergyCosts** (S6):

  | Constant | Value |
  |---|---|
  | FE_PER_POINT | 10 |
  | FE_TRANSFER_PER_TICK | 20000 |
  | SHIELD_UPKEEP | 20 |
  | HIVE_UPKEEP | 20 |
  | ABSORB_PER_HP | 10 |
  | REPAIR_PER_HP | 2 |
  | SHOT | 3 (dead, removed in S13) |
  | HEAL_PER_HP | 5 |
  | HIVE_REPAIR_PER_HP | 1 |
  | STRIKE | 6 |
  | MANA_CHARGE_PER_PULSE | 250 |

  All are `public static final int` compile-time constants.
- **BatteryRules** (S6):
  - `hasRf(role)`, `hasMana(role)`.
  - `capacity(level)` = `25000 + 7500*level`; `feCapacity(level)` = `capacity * FE_PER_POINT`.
  - `full(role, level)`.
  - `usableRf(role, e)` = `hasRf && e.rfOn() ? e.rf()/FE_PER_POINT : 0`; `usableMana(role, e)`.
  - `public static int[] split(int rf, int mana, int points)` (verbatim).
  - `record Drain(boolean paid, DeviceEnergy after)` and `drain(role, energy, points)`. When `rf+mana < points` it returns `(false, energy.withRf(rf_fe - rf*FE).withMana(mana - usableMana))`, emptying the usable batteries. Otherwise it applies `split`.
  - Callers invoke `drain` only after today's early exits (no role → false; `points <= 0` or free → true, no write).
  - `acceptFe(role, energy, level, amount)` = `min(min(amount, 20000), max(0, feCapacity - rf))`, and 0 when there is no RF battery or `amount <= 0`.
- **ExperienceCurve** (S6): `pointsForLevel(int)`, verbatim from ManaSources.

### 4.4 domain.combat

**DamageKind** (S6): `DRONE_SHOT("drone_shot")`, `SWARM_STRIKE("swarm_strike")`, `SWARM_VOID("swarm_void")`, `SWARM_REFLECT("swarm_reflect")`, `SHIELD_DISCHARGE("shield_discharge")`, `SHIELD_MANA_BURST("shield_mana_burst")`, `SHIELD_TWIN_SURGE("shield_twin_surge")`, with `id()`.

### 4.5 domain.shield

- **ShieldTopology** (moved in S2):
  - The generator is untouched.
  - Gains `PANEL_FRONT=0`, `PANEL_LEFT=1`, `PANEL_RIGHT=2`, `PANEL_BACK=3`, `PANEL_NONE=-1`. `ShieldStackState.PANEL_*` alias them.
  - `legacyRegionFor` becomes public, in the S2 rename commit (R-5).
  - `neighborsOf` is dropped in S13.
- **ShieldCellMove** and **ShieldCellDefense** (S4): unchanged. ShieldCellDefense's 4-argument `damage` is dropped in S13.
- **ShieldStackState** (S4):
  - The canonical-constructor semantics are unchanged: empty → sectors, 42 → legacy map, 420 → clamped, anything else throws.
  - Dead helpers are dropped in S13: `integrity(int)`, `routeCellDamage`, `damagePanel`, both `repairFirstDamagedPanel`, `withActivity`, `needsRepair()`, `MAX_TOTAL_INTEGRITY`, the unused 8-argument constructor, and the PANEL aliases. The constructor's own `PANEL_NONE` then refers to `ShieldTopology.PANEL_NONE`.
  - `damageCell` and `damageLocalCell` stay for the gallery.
- **ShieldSettings** (S4): record `(double radius, String coverage)` with unchanged normalisation.
- **Coverage** (S6): enum `OWNER("owner")`, `ALLIES("allies")`, `ALL("all")`; `of(String)`, where unknown → ALLIES. ShieldSettings already normalises, so the fallback is never reached; the old code treated an unknown mode like `all`.
- **ShieldField** (S5, Vec3d): `RADIUS 2`, `CENTER_Y .92`, `PREVIEW_TICKS 4`, `record Crossing(double time, Vec3d normal)`, `incoming`, `intercept`, `focus`, `fade`. The 3-argument `incoming` is dropped in S13.
- **ShieldImpact** (S5):
  - `normal` is a Vec3d.
  - The constructor normalises: `normal != null && finite(lengthSqr) && lengthSqr > 1e-10 ? normal.normalize() : new Vec3d(0,0,1)`. The other clamps are unchanged.
  - The 5- and 6-argument convenience constructors (used by NativeShieldGallery and NetworkCodecCheck), `of`, `strike`, `atDistance` and `isStrike` are unchanged.
- **ShieldImpactHistory** (S5): `LIMIT 12`; `append` keeps its 36-tick window.
- **ShieldStats** (S6):
  - `capacity(level)` = `(int) Math.round(DeviceStats.stat(empty, level, BUFFER_CAPACITY, 504, 504, 5500))`.
  - `totalCapacity(level)` = `capacity + ShieldTopology.CELL_COUNT * ShieldStackState.MAX_PANEL_INTEGRITY`.
  - `radiusLimit(level)` = `stat(RADIUS, 2, 2, 24)`.
  - `maxRadius(double configLimit, int level)` = `Math.min(configLimit, radiusLimit(level))`. The caller reads the config first.
  - `radius(double maxRadius, ShieldSettings s)` = `Math.min(maxRadius, s.radius())`.
  - `strikeDamage(Optional<RelicRole> role, int level, double multiplier)` = `(float)(base * multiplier)`, where base is MANA `2.5+.35L`, TWINS `4+.5L`, otherwise `3+.4L`. The result is 0 if the role is empty or not a shield.
  - `strikeKnockback(role, multiplier)` = `base * multiplier`, where base is MANA .8, TWINS 1.2, otherwise 1.0.
- **ShieldUpgradeEffects** (S6). The callers apply the operability gate, which forces rank 0:
  - `sharing(role, rank)`: rank 0 gives 0. Otherwise start and end are RF .25/.45, MANA .20/.35, else .35/.50, and the value is `start + (end - start) * rank / 3D`, evaluated left to right as written.
  - `gathering(role, rank)`: 0 for TWINS_SHIELD (checked first, whatever the rank). Otherwise rank 0 gives 0, else `min(MANA ? 3 : 2, rank)`.
  - `repairSteps(role, rank)` = `min(MANA ? 3 : 2, 1 + rank)`.
  - `quietTicks(role, rank)`: 40 unless TWINS with rank > 0, which gives `40 - 8*rank`.
- **CellSelection** (S6): `select(float yaw, Vec3d incoming)`. It computes `f = Vec3d.directionFromRotation(0, yaw)` and returns `ShieldTopology.INSTANCE.nearest(-incoming.x*f.z + incoming.z*f.x, incoming.y, incoming.x*f.x + incoming.z*f.z)`.
- **DamageFacts** (S6): interface `passListed()`, `inPassTag()`, `absorbListed()`, `strike()`.
  **DamagePassPolicy**: `passesField(DamageFacts f)` = `f.passListed() || (f.inPassTag() && (!f.absorbListed() || f.strike()))`, lazily, in that order.
- **HitImmunity** (S6): `record HitImmunity(float tick, float absorbed)` with `TICKS = 10`.
  - `covers(long now)` = `!(now - (long) tick >= TICKS || now < (long) tick)`.
  - `static HitImmunity record(HitImmunity previous /* may be null */, long now, float absorbed)` = `new HitImmunity((float) now, (previous != null && previous.covers(now) ? previous.absorbed() : 0) + absorbed)`.
  - The float-stored tick is kept on purpose.
- **EffectTrim** (S6):
  - `kept(boolean struck, float absorbed, float passed, int duration)` = `struck && passed > 0 ? (int) Math.floor(duration * passed / Math.max(1e-3F, absorbed + passed)) : 0`, in float arithmetic.
  - `removed(boolean infinite, int duration, int kept)` = `infinite ? 1200 : duration - kept`.
  - `cost(int removed, int amplifier)` = `Math.max(1, (int) Math.ceil(removed / 20.0 * (amplifier + 1) * 2))`, with `Math.ceil`.
- **ProjectileFacts** (S6): interface `removed()`, `ignoredListed()`, `noPhysicsArrow()`, `interceptListed()`, `trident()`, `arrow()`, `inInterceptTag()`, `crit()`, `baseDamage()`, `speed()`, `costClass()`, with `enum CostClass {HEAVY_8, SMALL_FIREBALL_5, LIGHT_1, OTHER_4}`.
  **ProjectilePolicy**:
  - `supported(f)` checks, in order: removed → false; ignoredListed → false; noPhysicsArrow → false; interceptListed → true; trident → false; otherwise `arrow || inInterceptTag`.
  - `impactCost(f)`:
    - For an arrow: `d = base*speed`; if crit, `d = Math.ceil(d)*1.5D + 1` (with `Math.ceil`); then `finite ? Math.clamp(Trig.ceil(d), 1, 10000) : 10000`.
    - Otherwise 8, 5, 1 or 4 by CostClass.
- **CoverageRule** (S6): `withinRadius(Vec3d victimCentre, Vec3d ownerFeet, double radius)` = `victimCentre.distanceToSqr(ownerFeet.add(0, CENTER_Y, 0)) <= radius*radius`.
- **BarrierPush** (S6): `PUSH_COST 1`, `DRIFT .35`.
  - `reach(radius, width)` = `radius + width*.5`.
  - `outward(Vec3d offset, double distance, Vec3d look)`: `distance < 1e-3 ? look.multiply(1,0,1) : offset.scale(1/distance)`; if `lengthSqr < 1e-6` use `(1,0,0)`; then normalize.
  - `horizontal(Vec3d out)`: `(out.x, 0, out.z)`, `(1,0,0)` if tiny, then normalize.
  - `step(depth)` = `Math.min(depth, 1.5)`.
  - `needsDrift(motion, h)` = `motion.x*h.x + motion.z*h.z < DRIFT`.
  - `drift(h, motion)` = `h.scale(DRIFT).add(0, Math.max(motion.y, .05), 0)`.
- **StrikeRules** (S6): `damageKind(role)`: MANA → SHIELD_MANA_BURST, TWINS → SHIELD_TWIN_SURGE, otherwise SHIELD_DISCHARGE.
- **ShieldStatus** (S6): enum `INACTIVE_SLOT("inactive_slot")`, `DISABLED("disabled")`, `PRIORITY("priority")`, `BROKEN("broken")`, `ACTIVE("active")`. `of(boolean slotActive, boolean enabled, boolean isActiveShield, int totalIntegrity)` tests them in that order.

### 4.6 domain.hive

- **AttackMode** and **HiveType** (S2, unchanged). AttackMode gains `byId(String)`, where unknown → BARRAGE.
- **HiveSettings**, **HiveStackState**, **HiveCombatState**, **HiveSupportState** and **HiveSlots** (S4, codecs removed in S3):
  - The shot kind constants `DROPLET=4 … DRONE_HIT=10` stay in HiveCombatState.
  - `HiveSettings.healer(int,int)` and `HiveSlots.lane` are dropped in S13.
- **HiveFormation** and **HiveShapes** (S5): Vec3d, verbatim. The private hash, fibonacciSphere and GOLDEN_ANGLE stay private.
- **HiveUpgradeEffects** (S6), with `r = Math.min(3, rank)`. Every multiplication keeps the code's association:
  - `damage(type, rank)` = `1 + r * perRank`, with perRank RF .10, MANA .08, TWINS .12.
  - `healing(type, rank)` = `1 + r * (cap / 3D)`, with caps RF .50, MANA 1, TWINS .75.
  - `rebuild(type, rank)` = `1 - r * (cap / 3D)`, with caps RF .30, MANA .20, TWINS .35.
  - `r * cap / 3D` differs in the last bit (MANA rank 3 gives 0.20000000000000004 instead of 0.2), which feeds `Math.round` in drone rebuild times.
  - The callers apply the operability gate, which forces rank 0.
- **SwarmRules** (S6):
  - `SETTLE_QUIET_TICKS 120`, `SETTLE_PERIOD 10`.
  - `capacity(type, level)` = `(int) Math.round(stat(DRONE_COUNT, type.initialCount, 12, 750))`.
  - `strikeInterval(level)` = `(int) Math.round(stat(ATTACK_INTERVAL_MAX, 100, 20, 100))`.
  - `attackDamageBase(type, level)` = `stat(ATTACK_DAMAGE, type.initialAttackDamage, 1, 100)`.
  - `cooldown(type, level)` = `stat(COOLDOWN, type.initialCooldown, 10, 400)`.
  - `restoredHealth(before, after)`.
- **DroneDamage** (S6):
  - `REPAIR_TICKS_PER_HP 40`.
  - `reeling(int invulnerableTime)` = `> 10`.
  - `swingDamage(double attack)` = `attack >= 6 ? 2 : 1`.
  - `explosionDamage(double d, double reach)` = `(int) Math.ceil(3 * (1 - d/reach))`, with `Math.ceil`.
  - `away(int hpLeft, double cooldown, double pace)` = `hpLeft <= 0 ? Math.round(cooldown*pace) : Math.round(40 * (DRONE_HP - hpLeft) * pace)`.
  - `backAt(now, away)` = `now + HiveFormation.RETURN_TICKS + away`.
- **SwarmStrike** (S6):

  | Member | Value |
  |---|---|
  | COST_PER_DRONE | 1 |
  | knockback(members) | `Math.min(1.4, .35 + .03*members)` |
  | CONTAINMENT_DAMAGE | .12F |
  | SPLASH_DAMAGE | .4F |
  | SPLASH_INFLATE | 2 |
  | SPLASH_DISTANCE_SQR | 4 |
  | SPLASH_KNOCKBACK | .5 |
  | SWING_INFLATE | 1.5 |
  | SWING_PERIOD / SWING_PHASE | 20 / 7 |
  | CONTAINMENT_PERIOD | 20 |
  | containmentDrain(flying) | `Math.max(1, flying/5)` |
  | WARD_PER_DRONE | .5 |
  | WARD_HUM_PERIOD | 60 |
  | TARGET_REFRESH_PERIOD | 5 |
  | TARGET_MEMORY_TICKS | 100 |
  | EXPLOSION_OWNER_RANGE_SQR | 200*200 |

- **ShotLog** (S6): `VISUAL_TICKS 40`; `recent(List<Shot>, long now)`, verbatim from `recentShots`.
- **ChargeFlight** (S6):
  - `BALL_SPEED .9`, `EXPIRY_GRACE 40`.
  - `velocity(start, end)` = `end.subtract(start).normalize().scale(.9)`.
  - `flightTicks(start, end)` = `Math.max(1, Trig.ceil(start.distanceTo(end) / .9))`.
  - `expiresAt(now, start, end)` = `now + flightTicks + 40`.
  - `arrival(firedAt, start, end)` = `firedAt + flightTicks`.
  - `homing(targetCentre, position, velocity)`: `aim = centre - position`; `aim.lengthSqr() > 1e-6 ? aim.normalize().scale(.9) : velocity`.
- **HoldPolicy** (primitive methods in S6, Hold overloads in S10):
  - `LIFT 4` (int), `LIFT_TICKS 30`, `GROUND_PROBE = LIFT + 2`.
  - `joinsOther(boolean mine, long seen, long now)` = `!mine && now - seen <= 1 && now >= seen`.
  - `stale(boolean alive, long seen, long now)` = `!alive || now - seen > 2 || now < seen`.
  - `pinPoint(Vec3d anchor, boolean twins, double startLift, double lift, long since, long now)`: `t = min(1, (now-since)/(double)30)`, then `anchor.add(0, startLift + (lift-startLift)*t*t*(3-2*t), 0)` for twins; otherwise `anchor`.
  - `refill(ward, wardMax, amount)` = `Math.min(wardMax, ward + Math.max(0, amount))`.
  - `wardMax(drones)` = `Math.max(1, drones)`.
  - S10 adds exactly these Hold overloads:
    - `mine(Hold h, UUID owner, HiveType type)` = `h.owner().equals(owner) && h.type() == type`;
    - `joinsOther(Hold h, boolean mine, long now)`;
    - `stale(Hold h, boolean alive, long now)`;
    - `pinPoint(Hold h, long now)`;
    - `refill(Hold h, double amount)`, which sets `h.ward(...)` and returns the added amount.
- **Hold** (S10): a mutable final class.
  - Constructor `(UUID owner, HiveType type, Vec3d anchor, double lift, double startLift, long since)`; `seen` starts at `since`.
  - Getters: `owner`, `type`, `anchor`, `lift`, `startLift`, `since`.
  - `seen()` / `seen(long)`, `ward()` / `ward(double)`, `wardMax()` / `wardMax(double)`.
  - `List<Vec3d> intercepted()` and `reflected()`, both mutable.
- **HealingPolicy** (S6):
  - `PERIOD 20`, `NEXT_MEND 100`.
  - `firstMendAt(now, index)` = `now + 20L*(1 + index%5)`.
  - `request(double multiplier, float budget, float missing)` = `Math.min((float)(.5*multiplier), Math.min(budget, missing))`.
  - `cost(float restored)` = `(int) Math.ceil(restored * HEAL_PER_HP)`, with `Math.ceil`.

## 5. Ports

No port or domain type carries an annotation from outside `java.*` (R-13). The `@Nullable` markers below are documentation only.

### 5.1 Outbound views and values (application.port.out)

Views are live wrappers: each method reads current entity state. They are never snapshots.
```java
public interface WorldRef { long gameTime(); boolean clientSide(); }            // Level.getGameTime / isClientSide; equals = same Level
public interface EntityView {
  int id(); UUID uuid(); WorldRef world(); Vec3d position(); Box bounds(); double width(); double height();
  boolean alive(); boolean spectator(); boolean removed(); boolean alliedTo(EntityView other);
  double distanceToSqr(EntityView other); double distanceToSqr(Vec3d point);
  float distanceTo(EntityView other);                 // Entity.distanceTo: FLOAT arithmetic (feeds travelTicks)
  Optional<EntityView> responsible();                 // TraceableEntity -> owner (empty if null), else this
  boolean tamable(); Optional<UUID> tamedOwnerId(); Optional<EntityView> tamedOwner();   // TamableAnimal
  Optional<Creature> asCreature(); Optional<PlayerView> asPlayer(); Optional<MobView> asMob(); Optional<ProjectileView> asProjectile();
}
public interface Creature extends EntityView {        // LivingEntity
  float health(); float maxHealth(); int invulnerableTime(); boolean onGround(); boolean canBeHitByProjectile();
  boolean boss();                                     // getType().is(Tags.EntityTypes.BOSSES)
  boolean isPlayer(); boolean noGravity();
}
public interface MobView extends Creature {           // Mob
  boolean enemy(); boolean neutral(); boolean angryAt(PlayerView p); boolean piglin(); boolean targets(EntityView e); // getTarget() == e
  boolean multipart(); Vec3d motion(); OptionalDouble attackDamage();   // ATTACK_DAMAGE attribute value if present
}
public interface PlayerView extends Creature {
  boolean real();                                     // !(FakePlayer)
  boolean serverPlayer(); boolean creative(); boolean instabuild(); float yaw(); Vec3d look(); int tickCount();
  Optional<Creature> lastHurtMob(); int lastHurtMobTimestamp(); Optional<Creature> lastHurtByMob(); int lastHurtByMobTimestamp();
  boolean canHarm(PlayerView other); boolean pvpAllowed();  // ServerPlayer: server.isPvpAllowed(); otherwise true
  boolean hasLineOfSight(EntityView e);
}
public interface ProjectileView extends EntityView, ProjectileFacts { Vec3d velocity(); Optional<EntityView> owner(); }
public interface EffectView { boolean harmful(); boolean kept(); boolean infinite(); int duration(); int amplifier(); }
public interface ExplosionView { WorldRef world(); Vec3d centre(); double radius(); Optional<EntityView> cause(); } // getIndirectSourceEntity
public interface DeviceHandle { }                     // opaque; adapter: StackDevice(ItemStack), identity equality
public record SlotAddress(boolean charm, int slot) { }
public record CharmSlot(int index, boolean active, DeviceHandle device) { }
public record SegmentHit(Creature creature, Vec3d point) { }
public enum UiCue { TOGGLE, UPGRADE }
```

### 5.2 Outbound ports (application.port.out)

```java
public interface DeviceStore {          // S8; ItemComponentStore; every method is one get/has/set on the live stack, defaults as today
  Optional<RelicRole> role(DeviceHandle d);                 // item instanceof AutonomousRelicItem -> role()
  Optional<String> instanceId(DeviceHandle d); boolean hasInstanceId(DeviceHandle d); void setInstanceId(DeviceHandle d, String id);
  DeviceProgression progression(DeviceHandle d); boolean hasProgression(DeviceHandle d); void setProgression(DeviceHandle d, DeviceProgression p);
  DeviceEnergy energy(DeviceHandle d); boolean hasEnergy(DeviceHandle d); void setEnergy(DeviceHandle d, DeviceEnergy e);
}
public interface ShieldStore {          // S8
  ShieldStackState state(DeviceHandle d); void setState(DeviceHandle d, ShieldStackState s);
  ShieldSettings settings(DeviceHandle d); void setSettings(DeviceHandle d, ShieldSettings s);
  Optional<ShieldImpact> lastImpact(DeviceHandle d); void setLastImpact(DeviceHandle d, ShieldImpact i);
  ShieldImpactHistory history(DeviceHandle d); void setHistory(DeviceHandle d, ShieldImpactHistory h);
}
public interface HiveStore {            // S8
  HiveStackState swarm(DeviceHandle d); void setSwarm(DeviceHandle d, HiveStackState s);
  HiveSettings settings(DeviceHandle d); void setSettings(DeviceHandle d, HiveSettings s);
  HiveCombatState combat(DeviceHandle d); void setCombat(DeviceHandle d, HiveCombatState c); void clearCombat(DeviceHandle d);
  HiveSupportState support(DeviceHandle d); void setSupport(DeviceHandle d, HiveSupportState s);
}
public interface Equipment {            // S8; CuriosEquipment
  List<CharmSlot> charms(PlayerView p);   // every 'charm' slot in index order with isSlotActive (inactive and empty slots included); empty if no Curios inventory or handler
  DeviceHandle locate(PlayerView p, SlotAddress a); // HiveTaskController.locate semantics; wraps ItemStack.EMPTY when absent
  DeviceHandle mainHand(PlayerView p); DeviceHandle offHand(PlayerView p);
  int inventorySize(PlayerView p); DeviceHandle inventory(PlayerView p, int slot);
}
public interface ServerSettings {       // S8; ConfigServerSettings; read live on every call, never cached
  double shieldMaxRadius();             // SHIELD_MAX_RADIUS.get()           (unguarded, as today)
  double strikeDamageMultiplier();      // isLoaded ? SHIELD_STRIKE_DAMAGE : 1
  double strikeKnockbackMultiplier();   // isLoaded ? SHIELD_STRIKE_KNOCKBACK : 1
  int strikeCooldownTicks();            // isLoaded ? SHIELD_STRIKE_COOLDOWN : 20
  double hiveTargetRange();             // unguarded
  double hivePursuitRange();            // unguarded
  float hiveStrikeEfficiency();         // isLoaded ? HIVE_STRIKE_EFFICIENCY.floatValue() : .5F
  double hiveHealPerSecond();           // unguarded
  boolean batteriesRequired();          // !isLoaded || POWER_REQUIRED
  int maxExperiencePerMinute();         // isLoaded ? XP_PER_MINUTE : 30
}
public interface ManaSupply { int draw(PlayerView p, DeviceHandle d, int points, DeviceEnergy.ManaSource mode); } // S8; ManaSources
public interface IdSource { String newInstanceId(); }               // S8; RandomInstanceIds: UUID.randomUUID().toString()
public interface Cues {                 // S8; SoundCues -> RelicSounds statics; plays only on a ServerLevel
  void summon(WorldRef w, Vec3d at, HiveType t, boolean on); void shieldHit(WorldRef w, Vec3d at, RelicRole r, boolean broken, boolean exhausted);
  void strike(WorldRef w, Vec3d at, RelicRole r); void swarmStrike(WorldRef w, Vec3d at, HiveType t); void chargeFire(WorldRef w, Vec3d at, HiveType t);
  void swarmExplosion(WorldRef w, Vec3d at, HiveType t); void containment(WorldRef w, Vec3d at, HiveType t); void reflect(WorldRef w, Vec3d at);
  void ui(PlayerView p, UiCue cue);
}
public interface WorldQuery {           // S9 (firstHit S10); MinecraftWorldQuery
  List<PlayerView> players(WorldRef w);                                   // level.players() order
  Optional<EntityView> entity(WorldRef w, int id); Optional<PlayerView> player(WorldRef w, UUID id);
  List<MobView> mobs(WorldRef w, Box area, Predicate<MobView> filter);    // getEntitiesOfClass(Mob.class, ...)
  List<Creature> creatures(WorldRef w, Box area, Predicate<Creature> filter); // getEntitiesOfClass(LivingEntity.class, ...)
  Optional<SegmentHit> firstHit(WorldRef w, Vec3d from, Vec3d to, Predicate<Creature> filter); // AABB(from,to).inflate(.5); bb.inflate(.3).clip; strictly nearest to 'from', first found on ties
}
public interface CreatureActions {      // S9; MinecraftCreatureActions (damage source built from the target's level + ModDamageTypes.key)
  boolean hurt(Creature target, DamageKind kind, Optional<EntityView> credit, float amount);
  void knockback(Creature target, double strength, double x, double z);  // then hurtMarked = true
  void move(MobView mob, Vec3d delta);                                     // MoverType.SELF
  void setMotion(Creature c, Vec3d motion); void markHurt(Creature c);
  float heal(PlayerView p, float amount);                                  // returns max(0, after - before)
}
public interface ProjectileControl {    // S9; MinecraftProjectiles
  boolean unobstructed(ProjectileView p, double ticks);  // ShieldProjectileInterceptor.unobstructed verbatim
  void stopAt(ProjectileView p, Vec3d point);            // setPos; arrow with pickup ALLOWED -> spawnAtLocation(copy); discard
  void scaleArrowDamage(ProjectileView p, double factor);
}
public interface ProjectileLedger {     // S9; PersistentDataLedger (keys in §12.1)
  boolean absorbedFor(ProjectileView p, UUID player); void markAbsorbed(ProjectileView p, UUID player); // putUUID then putBoolean
  boolean passedFor(ProjectileView p, UUID player); void markPassed(ProjectileView p, UUID player);
  float credit(ProjectileView p, UUID player); String creditDevice(ProjectileView p, UUID player);
  void setCredit(ProjectileView p, UUID player, float paid, String instanceId); void clearCredit(ProjectileView p, UUID player); // putFloat then putString; remove both
}
public interface EntityMemories { <T> EntityMemory<T> create(); }  // S9; WeakEntityMemories
public interface EntityMemory<T> {      // WeakHashMap keyed by the wrapped live entity (Entity.equals semantics)
  T get(EntityView e) /* may be null */; void put(EntityView e, T value); T remove(EntityView e) /* may be null */;
  boolean contains(EntityView e); boolean isEmpty(); List<Creature> keys();   // snapshot, like List.copyOf(map.keySet())
}
public interface Notifier { void shieldBlocked(PlayerView p, float absorbed, int buffer, int capacity, int cellHp, int maxCellHp); } // S9
public interface EntityControl {        // S10; MinecraftEntityControl
  Vec3d groundBelow(Creature c, double depth);            // onGround ? position : clip down COLLIDER/NONE; MISS -> position
  double headroom(Creature c, Vec3d anchor, double most); // quarter-block steps with noCollision
  void pin(Creature c, Vec3d at, boolean suspendGravity, boolean silence);
  void restoreGravity(Creature c);                        // only if the mark is present: setNoGravity(mark), remove the mark
}
```
`pin` performs, in order:
1. If `suspendGravity`: write the `relics_addon:held_gravity` mark when absent, then `setNoGravity(true)`.
2. `setDeltaMovement(ZERO)`.
3. `teleportTo` when `position().distanceToSqr(at) > 1e-4`.
4. `fallDistance = 0` and `hurtMarked = true`.
5. For a Mob only:
   1. stop navigation and `setJumping(false)`;
   2. then, if `silence`: `setTarget(null)`, `setAggressive(false)`, erase ATTACK_TARGET when the brain has that memory value, and `setSwellDir(-1)` for a creeper.

### 5.3 Inbound ports (application.port.in)

```java
public interface ManageDevice {         // S8 DeviceService
  boolean enabled(DeviceHandle d); boolean canOperate(PlayerView p /* may be null */, DeviceHandle d);
  void setEnabled(PlayerView p, DeviceHandle d, boolean enabled); void ensureDefaults(DeviceHandle d);
}
public interface Progress {             // S8 ProgressionService
  int experienceToNext(int level); void award(PlayerView p, DeviceHandle d, float value); boolean purchase(DeviceHandle d, DeviceUpgrade u);
}
public interface ManagePower {          // S8 PowerService
  boolean free(PlayerView p /* may be null */); boolean powered(PlayerView p /* may be null */, DeviceHandle d);
  boolean canAfford(PlayerView p /* may be null */, DeviceHandle d, int points); boolean drain(PlayerView p /* may be null */, DeviceHandle d, int points);
  int receiveFe(DeviceHandle d, int fe, boolean simulate); DeviceEnergy energy(DeviceHandle d); int capacity(DeviceHandle d);
  int feCapacity(DeviceHandle d); DeviceEnergy full(DeviceHandle d);
  void setBattery(DeviceHandle d, boolean rf, boolean on); void setManaSource(DeviceHandle d, DeviceEnergy.ManaSource s);
}
public interface RunUpkeep { void onPlayerTick(PlayerView p); }     // S8 UpkeepService
public interface DeviceQueries {        // S8 DeviceQueryService; read-only; used by items and the screen
  Optional<RelicRole> role(DeviceHandle d); boolean isDevice(DeviceHandle d); boolean enabled(DeviceHandle d);
  boolean powered(PlayerView viewer /* may be null */, DeviceHandle d); DeviceProgression progression(DeviceHandle d); int experienceToNext(int level);
  DeviceEnergy energy(DeviceHandle d); int batteryCapacity(DeviceHandle d); int feCapacity(DeviceHandle d);
  ShieldStackState shieldState(DeviceHandle d); int shieldCapacity(DeviceHandle d); int shieldTotalCapacity(DeviceHandle d);
  double shieldRadius(DeviceHandle d); float strikeDamage(DeviceHandle d);
  HiveStackState swarm(DeviceHandle d); HiveSettings hiveSettings(DeviceHandle d); int hiveCapacity(DeviceHandle d); // 0 for non-hives (D1)
  double stat(DeviceHandle d, DeviceStat s, double fallback, double min, double max);
  List<SlotAddress> deviceSlots(PlayerView p);             // charm slots holding a device (AutonomousRelicItem with an available role), then inventory 0..size-1
  Optional<SlotAddress> addressOf(PlayerView p, DeviceHandle d); // by stack identity
}
public interface ShieldQueries {        // S8 ShieldLocator
  Optional<DeviceHandle> activeShield(PlayerView p); Optional<RelicRole> role(DeviceHandle s);
  ShieldStackState state(DeviceHandle s); ShieldImpactHistory history(DeviceHandle s); Optional<ShieldImpact> lastImpact(DeviceHandle s);
  ShieldSettings settings(DeviceHandle s); int capacity(DeviceHandle s); int totalCapacity(DeviceHandle s);
  double maxRadius(DeviceHandle s); double radius(DeviceHandle s); float strikeDamage(DeviceHandle s);
  double strikeKnockback(RelicRole role); int strikeCooldown();
  double sharing(PlayerView p /* may be null */, DeviceHandle s); int gathering(PlayerView p /* may be null */, DeviceHandle s);
  int repairSteps(PlayerView p /* may be null */, DeviceHandle s); int quietTicks(PlayerView p /* may be null */, DeviceHandle s);
  boolean covers(PlayerView owner, DeviceHandle shield, Creature victim);   // ShieldCoverage.covers, see §6.1
}
public record EquippedHive(HiveType type, DeviceHandle device) { }
public interface ManageSwarm {          // S8 HiveRoster
  List<EquippedHive> active(PlayerView p); int capacity(DeviceHandle h);   // capacity throws for a non-hive, as today
  HiveStackState prepare(PlayerView p, DeviceHandle h, boolean repair); void settle(PlayerView p, DeviceHandle h, HiveStackState s);
  int strikeInterval(DeviceHandle h); float attackDamage(PlayerView p /* may be null */, DeviceHandle h, HiveType t);
  double healingMultiplier(PlayerView p /* may be null */, DeviceHandle h); double rebuildMultiplier(PlayerView p /* may be null */, DeviceHandle h);
  double cooldown(DeviceHandle h, HiveType t);
  HiveStackState swarm(DeviceHandle h); HiveCombatState combat(DeviceHandle h); HiveSettings settings(DeviceHandle h); HiveSupportState support(DeviceHandle h);
}
public interface IncomingDamage extends DamageFacts {   // S9; live view of LivingIncomingDamageEvent (McIncomingDamage)
  Creature victim(); float amount(); void setAmount(float a); void cancel(); boolean canceled();
  Optional<EntityView> attacker(); Optional<EntityView> directEntity(); Optional<ProjectileView> directProjectile(); Optional<Vec3d> sourcePosition();
}
public interface AbsorbDamage { void onIncomingDamage(IncomingDamage hit); boolean passesField(DamageFacts facts); }  // S9
public interface MaintainShield { void onPlayerTick(PlayerView p); }                                       // S9
public interface HoldBackHostiles { void onPlayerTick(PlayerView owner); }                                 // S9
public interface InterceptProjectiles {                                                                     // S9
  boolean onProjectileTick(ProjectileView p);   // true = the projectile was stopped and removed: cancel its tick
  boolean supported(ProjectileView p); boolean threatens(ProjectileView p, PlayerView player);
  boolean intercept(ProjectileView p, PlayerView player); int impactCost(ProjectileView p);
}
public interface EffectApplication { Creature victim(); EffectView effect(); Optional<EntityView> source(); }  // S9
public record EffectVerdict(boolean deny, int keptTicks) { public static final EffectVerdict ALLOW = new EffectVerdict(false, 0); }
public interface GuardEffects {                                                                             // S9
  void recordHit(Creature victim, Optional<EntityView> source, float absorbed, float passed);
  void onDamageLanded(Creature victim, Optional<EntityView> source, float newDamage); // source = getEntity() ?: getDirectEntity()
  EffectVerdict onEffectApplicable(EffectApplication application);
}
public record ShieldStatusLine(DeviceHandle device, int slot /* 0-based; printed as slot + 1 */, ShieldStatus status, int integrity, int totalCapacity, int livingCells, int buffer, int capacity) { }
public record RadiusChange(Result result, double value, double maximum) { public enum Result { NO_SHIELD, INVALID, CHANGED } }
public record CoverageChange(boolean changed, String coverage) { }
public interface ManageShieldSettings {                                                                     // S9
  List<ShieldStatusLine> status(PlayerView p); RadiusChange setRadius(PlayerView p, double radius); CoverageChange setCoverage(PlayerView p, String coverage);
}
public interface RunSwarm {                                                                                 // S10 HiveCombatService
  void onPlayerTick(PlayerView p); void combatTick(PlayerView p); void onExplosion(ExplosionView e); void forget(PlayerView p);
  Optional<Creature> selectTarget(PlayerView owner, int previousTargetId); boolean validTarget(PlayerView owner, Creature c, boolean pursuing);
}
public interface ContainTargets {                                                                           // S10 ContainmentService
  void onHeldAttack(IncomingDamage hit); boolean swallow(ProjectileView p); void onCreatureJoin(Creature c);
  boolean pinned(EntityView e); Optional<Hold> held(EntityView e); void onLevelTick(WorldRef w);
}
public interface ConfigureHive {                                                                            // S10 HiveTaskService
  HiveSettings settings(DeviceHandle h);
  boolean configureHealers(PlayerView owner, SlotAddress a, String identity, int healers);
  boolean configureMode(PlayerView owner, SlotAddress a, String identity, AttackMode mode);  // true whenever validation passes, even for the current mode
}
public sealed interface ConsoleCommand {                                                                    // S10
  record Toggle() implements ConsoleCommand { } record AdjustHealers(int delta) implements ConsoleCommand { }
  record ToggleBattery(boolean rf) implements ConsoleCommand { } record SelectManaSource(DeviceEnergy.ManaSource source) implements ConsoleCommand { }
  record SelectMode(AttackMode mode) implements ConsoleCommand { } record Purchase(DeviceUpgrade upgrade) implements ConsoleCommand { }
}
public interface OperateConsole {                                                                           // S10 DeviceConsoleService
  Optional<DeviceHandle> prepareOpen(PlayerView p, SlotAddress a); DeviceHandle device(PlayerView p, SlotAddress a); String identity(DeviceHandle d); // "" when absent
  boolean deviceValid(PlayerView p, SlotAddress a, String identity); boolean stillValid(PlayerView p, SlotAddress a, String identity);
  boolean press(PlayerView p, SlotAddress a, String identity, ConsoleCommand c);
}
public record ShieldThreat(Vec3d normal, double ticks) { }
public interface ForecastThreats {                                                                          // S11 ThreatForecastService
  boolean supported(ProjectileView p);
  List<ShieldThreat> forecast(PlayerView player, DeviceHandle shield, List<ProjectileView> supported); // stable-sorted by ticks, not truncated
}
```

## 6. Application services

Each service is a plain final class with constructor injection. Instance state only. Rules stay pure.

| Class | Implements | Constructor dependencies | State | Transposed from |
|---|---|---|---|---|
| device.DeviceService | ManageDevice | DeviceStore, ShieldStore, HiveStore, ManagePower, Cues, IdSource | – | RelicRuntime.enabled/canOperate/setEnabled; AutonomousRelicItem.ensureState |
| device.ProgressionService | Progress | DeviceStore, ServerSettings | ExperienceWindows | RelicRuntime.award*/purchaseUpgrade/experienceToNext; ExperienceLimiter |
| device.ExperienceWindows | – | – | synchronized `Map<String,long[]>` (synchronized methods or `Collections.synchronizedMap`, never `java.util.concurrent`) | ExperienceLimiter.allow verbatim. The cap is passed in; prune at 4096 when opening a window; 1200-tick window; a clock that went back resets the window |
| device.DeviceQueryService | DeviceQueries | DeviceStore, ShieldStore, HiveStore, ShieldQueries, ManageSwarm, ManagePower, ManageDevice, Equipment | – | reads done by DeviceControlScreen, AutonomousRelicItem and DeviceTargets |
| device.DeviceConsoleService | OperateConsole | Equipment, DeviceStore, ManageDevice, ManagePower, Progress, ManageSwarm, ConfigureHive, Cues | – | DeviceControlMenu.open/device/stillValid/clickMenuButton |
| energy.PowerService | ManagePower | DeviceStore, ServerSettings | – | DevicePower, except onPlayerTick |
| energy.UpkeepService | RunUpkeep | ShieldQueries, ManageSwarm, ManagePower, ManageDevice, DeviceStore, HiveStore, Equipment, ManaSupply | – | DevicePower.onPlayerTick and chargeMana |
| shield.ShieldLocator | ShieldQueries | Equipment, DeviceStore, ShieldStore, ServerSettings, ManageDevice | – | EquippedRelicSetResolver.findFirstActive, ShieldParameters, ShieldUpgrades, ShieldCoverage.covers |
| shield.ShieldImpacts | – | ShieldStore | – | `record(shield, impact)`: setLastImpact, then setHistory(history.append) |
| shield.ShieldDefenseService | AbsorbDamage | ShieldQueries, ShieldStore, WorldQuery, ProjectileInterceptionService, EffectGuardService, ShieldImpacts, Progress, ManagePower, Cues, Notifier, EntityMemories | `EntityMemory<HitImmunity>` | ShieldController.onIncomingDamage/passesField/withinImmunity/settle/tryShieldBlock |
| shield.ShieldMaintenanceService | MaintainShield | ShieldQueries, ShieldStore, ManagePower | – | ShieldController.onPlayerTick |
| shield.ShieldBarrierService | HoldBackHostiles | ShieldQueries, ShieldStore, WorldQuery, CreatureActions, ManagePower, Progress, Cues, ShieldImpacts, EntityMemories | `EntityMemory<Long>` (strike ready-at per mob, shared by all owners) | ShieldBarrier, ShieldStrike.strike/record |
| shield.ProjectileInterceptionService | InterceptProjectiles | ShieldQueries, ShieldStore, DeviceStore, WorldQuery, ProjectileControl, ProjectileLedger, ShieldImpacts, Progress, ManagePower, Cues, IdSource, ServerSettings | – | ShieldProjectileInterceptor, including consumePaidDamage/alreadyAbsorbed/passedThrough (public service methods, not port methods) |
| shield.EffectGuardService | GuardEffects | ShieldQueries, ShieldStore, WorldQuery, ManagePower, EntityMemories | `EntityMemory<LastHit>` with `record LastHit(long tick, int attacker, float absorbed, float passed)` | ShieldEffectGuard |
| shield.ShieldCommandService | ManageShieldSettings | ShieldQueries, ShieldStore, DeviceStore, Equipment | – | ShieldStatusCommand status/radius/coverage/selected |
| shield.ThreatForecastService | ForecastThreats | ShieldQueries, ShieldStore, InterceptProjectiles, ProjectileControl | – | the per-projectile decisions of ShieldThreatTracker |
| combat.Relations | static helpers over views | – | – | ShieldCoverage.friendly, ShieldStrike.aggressive, ShieldBarrier.hostile |
| hive.HiveRoster | ManageSwarm | Equipment, DeviceStore, HiveStore, ManageDevice, ManagePower | – | HiveController.active/capacity/prepare/settle; HiveCombatController.strikeInterval/attackDamage; HiveUpgrades; HiveTaskController.settings |
| hive.HiveTaskService | ConfigureHive | Equipment, DeviceStore, HiveStore, ManageSwarm | – | HiveTaskController.configure/configureMode/locate/settings |
| hive.HiveHealingService | – | HiveStore, ManageSwarm, ManagePower, CreatureActions, ServerSettings | – | HiveTaskController.tickHealing |
| hive.ContainmentService | ContainTargets | EntityControl, CreatureActions, WorldQuery, EntityMemories | `EntityMemory<Hold>` | HiveContainment |
| hive.ChargeFlights | – | – | `Map<UUID, EnumMap<HiveType, List<Flight>>>`; Flight holds WorldRef, a Creature target, group, members, cost, damage, firedAt, expiresAt, start, position and velocity | HiveCombatController.FLIGHTS/Flight (keep `computeIfAbsent` in `flights(owner, type)`) |
| hive.HiveCombatService | RunSwarm | ManageSwarm, HiveStore, HiveHealingService, ContainmentService, ChargeFlights, WorldQuery, CreatureActions, ManagePower, Progress, Cues, ServerSettings | (ChargeFlights) | HiveCombatController; HiveController.onPlayerTick |

### 6.1 Transposition notes

The old code is authoritative; these notes cover the points that are easy to get wrong.

**Relations** (application.combat):

`friendly(owner, e)`:
```
e.equals(owner) || e.alliedTo(owner) || owner.alliedTo(e)
  || (e.tamable() && (e.tamedOwnerId().map(owner.uuid()::equals).orElse(false)
                      || e.tamedOwner().map(owner::alliedTo).orElse(false)))
```

`aggressive(owner, mob)`: returns false if friendly; true if `mob.targets(owner)`; if the mob is neutral, returns `angryAt(owner)`; otherwise returns `enemy && !piglin`.

`barrierHostile(owner, mob)`: returns false if friendly; otherwise `enemy || targets(owner)`.

**ShieldLocator** (ShieldQueries):
- `activeShield(p)`:
  - `!real || !alive || spectator` → empty.
  - Otherwise, for each role of `RelicRole.shields()`, then each charm slot in index order, the first slot that is active, holds that role, and passes `canOperate(p, device)`.
- `covers(owner, shield, victim)`:
  1. false unless owner and victim are both alive and not spectators;
  2. true if `owner.equals(victim)`;
  3. then `Coverage.of(settings(shield).coverage())`: OWNER → false; ALLIES → false unless `Relations.friendly(owner, victim)`;
  4. finally `CoverageRule.withinRadius(victim.bounds().center(), owner.position(), radius(shield))`.
- The upgrade queries apply the operability gate (`canOperate`) first and then call ShieldUpgradeEffects.

**PowerService**:
- `drain` and `canAfford`: no role → false; `points <= 0 || free(p)` → true, with no write.
- Otherwise `drain` calls `BatteryRules.drain` and writes `after` in both outcomes.
- `free(p)` = `!settings.batteriesRequired() || (p != null && p.instabuild())`.

**HiveRoster**:
- `active(p)`: `!real || !alive || spectator` → empty. Otherwise the first charm slot that is active, holds a hive, and passes `canOperate`; then break (at most one hive).
- `capacity(h)`: throws IllegalArgumentException when the role is absent or not a hive. The old code threw ClassCastException or IllegalArgumentException; no remaining caller reaches this case.

**ShieldDefenseService.onIncomingDamage**

The guards, in this order, return early:
- `victim.world().clientSide()`;
- `!alive`;
- `spectator`;
- the victim is a player and not `real`;
- `canceled`;
- `!(amount > 0)`;
- `!Float.isFinite(amount)`;
- `DamagePassPolicy.passesField(hit)`.

Owner loop:
- Owners are `WorldQuery.players(victim.world())`, plus the victim if it is a player not already in the list (`contains` uses equals).
- The list is stable-sorted by `owner.equals(victim) ? -1 : owner.distanceToSqr(victim)`.
- For each owner, in order:
  1. Skip if not real.
  2. Look up `activeShield`; skip if absent.
  3. Skip unless `shieldQueries.covers(owner, shield, victim)`.
  4. The own-side skip: `attacker != null && !friendly(owner, victim) && friendly(owner, attacker)`.
  5. For a projectile (`directProjectile` present): `prepaid = interception.consumePaidDamage(...)`. If it is > 0, `settle` and return. Skip if `alreadyAbsorbed || passedThrough`.
  6. If within immunity, return. This check may call `hit.setAmount(amount - window.absorbed())` and return false.
  7. If `tryShieldBlock` succeeds, return.
- Later owners see the reduced amount (quirk Q2).

`settle` order:
1. `effectGuard.recordHit(victim, attacker-or-direct, absorbed, amount - absorbed)`.
2. Update the immunity memory with `HitImmunity.record(previous, now, absorbed)`.
3. Cancel if `absorbed >= amount`, else `setAmount(amount - absorbed)`.

`tryShieldBlock` order:
1. Compute direction and cell with `CellSelection`:
   - the direction is the source position (or the direct entity's position) minus `feet + CENTER_Y`;
   - with neither, it is `Vec3d.directionFromRotation(0, yaw)`.
2. Gather, and set the state if it changed.
3. `cost = Math.clamp(Trig.ceil(hit.amount()), 1, 10000)` (the float overload); damage with `sharing`.
4. `absorbed = Math.min(amount, spent)`. If `absorbed <= 0`, return false.
5. `settle`.
6. Apply and set the state.
7. Build the impact; call `atDistance` when a source position existed and `direction.length() < radius`.
8. `ShieldImpacts.record`.
9. `Cues.shieldHit` at `feet + CENTER_Y + normal * (distance >= 0 ? distance : radius)`.
10. `award(absorbed)`.
11. `drain(Trig.ceil(absorbed * ABSORB_PER_HP))`. The float product is kept and the result is ignored.
12. `Notifier.shieldBlocked(absorbed, next.sharedBuffer(), capacity, next.cellHp(cell), 12)`.

**ShieldMaintenanceService.onPlayerTick**:
1. Client side or not real → return.
2. No active shield → return.
3. Clamp the buffer down to capacity and set the state if it was over.
4. `now % repairInterval != 0 || !needsRepair(capacity)` → return.
5. Repair. If `restored > 0` and the drain fails, return.
6. Set the repaired state, even when nothing changed.

**ProjectileInterceptionService**

`onProjectileTick`:
1. Return false if not `supported`.
2. `vicinity = p.bounds().expandTowards(p.velocity()).inflate(settings.shieldMaxRadius() + 1)`.
3. For each player: `vicinity.contains(player.position())` && real && alive && !spectator && `threatens` && a crossing exists.
4. Stable-sort the candidates by crossing time.
5. At the first `intercept` that returns true, return `p.removed()`.

`threatens(p, player)`: read `owner` first, then `supported && !owner.equals(player) && (owner empty || !friendly(player, owner)) && !absorbedFor && !passedFor && (owner is not a player || player.canHarm(owner))`.

`intercept` keeps today's order:
1. The guards.
2. The crossing, then `unobstructed`.
3. The shield.
4. `prepared` (gather).
5. The cell. On a hole with an empty buffer: `markPassed`, return false.
6. `cost`; damage; `stopped`; `absorbed`.
7. Apply and set the state.
8. Impact, with `atDistance` when `distance < radius - 1e-4`.
9. `record`.
10. Sound at the crossing point.
11. If `absorbed > 0`: `markAbsorbed`, `award`, `drain`.
12. Then one of: stopped → `stopAt`; arrow → scale its damage by `1 - spent / (double) cost`; otherwise mint an instance id if missing, then `setCredit`, then `markPassed`.

**ShieldBarrierService.onPlayerTick**

Guards: the world is client side, the owner is not alive, a spectator or not real, there is no active shield, or `totalIntegrity <= 0`.

Loop:
1. Area: `new Box(centre, centre).inflate(radius + 2)`.
2. `WorldQuery.mobs(area, m -> m.alive() && barrierHostile(owner, m))`.
3. For each mob:
   1. Skip if `distance >= reach`.
   2. If `drain(PUSH_COST)` fails, return from the whole method.
   3. Compute outward and horizontal.
   4. `move(horizontal.scale(step(depth)))` unless the mob is multipart.
   5. If `strike(...)` succeeds, continue.
   6. Drift if needed, then `markHurt` (always).

Strike order:
1. The aggressive check.
2. The cooldown memory.
3. Damage and knockback; return false if `!(damage > 0) && !(knockback > 0)`.
4. `canAfford(STRIKE)`, then `drain(STRIKE)` (the result is ignored).
5. Set ready-at to `now + strikeCooldown`.
6. `hurt` if damage > 0.
7. If `knockback > 0` and the mob is alive: knockback with `(-push.x, -push.z)` (CreatureActions.knockback also sets hurtMarked).
8. Record the strike impact: the panel of the selected cell, `max(1, damage)`, reach = `(float) mob.width() * .5F`.
9. Strike sound at `centre + normal * radius`.
10. `award(damage)` if damage > 0.

**EffectGuardService**

`onEffectApplicable` (the adapter checks the re-entrancy guard first):
1. `clientSide || !harmful || kept` → ALLOW.
2. `attacker = source.flatMap(responsible)`; empty, or equal to the victim → ALLOW.
3. Coverage loop: the sorted owners, skipping owners that are not real or are friendly to the attacker. The first owner with an active shield that `covers` the victim wins. None → ALLOW.
4. `struck` = the last hit has this tick and this attacker id. `struck && absorbed <= 0` → ALLOW.
5. Compute kept, removed and cost.
6. `!canAfford || !drain` → ALLOW.
7. Return `new EffectVerdict(true, kept)`.

`recordHit` stores `new LastHit(victim.world().gameTime(), dealer.id(), absorbed, Math.max(0, passed))`, and only when `source.flatMap(responsible)` is present.

`onDamageLanded`:
- return on the client side;
- no dealer → return;
- if the last hit has this tick and this dealer, keep it;
- otherwise store `new LastHit(now, id, 0, newDamage)` (no clamp).

**HiveCombatService**

- `combatTick`: `!p.serverPlayer() || !alive || spectator` → `forget`.
- `onPlayerTick`: return on the client side. Otherwise `combatTick`, then, when `gameTime % 10 == 0`, `settle(prepare(repair = true))` for each active hive. settle receives the state prepare returned, not a re-read.
- `travel` is `HiveFormation.travelTicks(owner.distanceTo(target))`, using `EntityView.distanceTo` (float).
- Blows, charges, swings and explosions keep every check in its original place:
  - `drain` before `hurt`, and `break` on a failed drain or `canAfford`;
  - the containment damage product `flying * perDrone * efficiency * .12F` in that order;
  - the explosion damage with `Math.ceil`;
  - `occupant` computed on the list already updated by earlier hits in the same explosion.
- The level RNG pitch jitter is drawn inside RelicSounds before its throttle check.

**ContainmentService**

Application-level methods used by HiveCombatService, besides the port:
- `hold(PlayerView owner, Creature target, HiveType type, int drones, long now)`;
- `release(Creature target)`;
- `releaseAll(UUID owner, HiveType type)`, where null means any type;
- `immune(Creature c)` = `c.isPlayer() || c.boss()`;
- `refill(Hold h, double amount)`;
- `drainIntercepted(Hold h)` and `drainReflected(Hold h)` (copy the list, then clear it).

`hold(owner, target, type, drones, now)`:
1. `existing = memory.get(target)`; `mine` = existing's owner is this owner and existing's type is this type.
2. `existing != null && joinsOther(mine, seen, now)` → return existing.
3. If not mine:
   1. `release(target)`: remove the entry and, only if an entry was removed, restore gravity.
   2. For TWINS: `anchor = groundBelow(target, GROUND_PROBE)`, `lift = headroom(target, anchor, LIFT)`, `startLift = Math.clamp(target.position().y - anchor.y, 0, lift)`. Otherwise `anchor = position` and `lift = startLift = 0`.
   3. Create the Hold and put it in the memory.
4. `seen = now`; `wardMax = wardMax(drones)`.
5. If the target is not immune: `pin(target, pinPoint(...), type == TWINS, type != MANA)`.

`onHeldAttack` is a single pass, not a loop:
1. The attacker (the source's `getEntity`) must be present, not on the client side, and not the victim.
2. `hold = held(attacker)`. Return if there is no hold or the attacker is immune (a player or a boss).
3. Non-Mana hold: cancel, return.
4. Mana hold: if `ward < amount`, return. Otherwise:
   1. `ward -= amount`.
   2. Cancel.
   3. `hurt(attacker, SWARM_REFLECT, owner via WorldQuery.player(attacker.world(), hold.owner()), amount)`.
   4. Add the attacker's bounds centre to the reflected list.

`onLevelTick(w)`:
- return if the memory is empty;
- for each key snapshot: skip keys not in `w`;
- release when `stale(alive, seen, now)`.

**UpkeepService.onPlayerTick** (the adapter only passes ServerPlayers):
1. `!real || !alive || spectator` → return.
2. When `gameTime % 20 == 0`:
   - drain SHIELD_UPKEEP from the active shield;
   - for each active hive, drain `HIVE_UPKEEP + Math.min(units, MAX_DEPLOYED) / 10`.
3. When `gameTime % 10 == 5` and not `free`: `chargeMana` for every charm slot, inactive slots included (quirk Q13).
   - Skip a slot with no mana role or not enabled.
   - Skip when `!manaOn || missing <= 0`.
   - `gained = ManaSupply.draw(..., min(missing, 250), source)`.
   - If `gained > 0`, set `energy.withMana(min(capacity, energy.mana() + gained))`, using the energy read before the draw and the capacity re-read after it.

## 7. Adapters

**Inbound (adapter.in)**

- **PowerEvents**(RunUpkeep): `onPlayerTick(PlayerTickEvent.Post)` passes only ServerPlayers.
- **ShieldDamageEvents**(AbsorbDamage): `onIncomingDamage` wraps the event in `McIncomingDamage`.
  - Its facts are lazy: `AddonConfig.PASSING_DAMAGE.matches(typeHolder)`, `typeHolder.is(ModDamageTypes.SHIELD_PASSES)`, `ABSORBED_DAMAGE.matches`, `is(ModDamageTypes.SHIELD_STRIKE)`.
  - `McDamageFacts.of(DamageSource)` builds the same facts outside an event, for GameTests and the S9 facade.
- **ShieldTickEvents**(MaintainShield, HoldBackHostiles): `onMaintenanceTick` and `onBarrierTick`. Each wraps the player and calls the service; the services do the guards.
- **ProjectileEvents**(InterceptProjectiles): `onEntityTick(EntityTickEvent.Pre)`.
  - Return if the event is canceled, the entity is not a Projectile, or the level is not a ServerLevel.
  - If `onProjectileTick(view)` returns true, `setCanceled(true)`.
- **EffectGuardEvents**(GuardEffects): holds the instance field `reapplying`.
  - `onEffectApplicable`:
    1. If `reapplying`, return.
    2. Get the verdict; if it does not deny, return.
    3. `setResult(DO_NOT_APPLY)`.
    4. If kept > 0: set the guard, `victim.addEffect(new MobEffectInstance(effect.getEffect(), kept, amp, ambient, visible, showIcon), event.getEffectSource())`, clear the guard in `finally`.
    5. If the victim is a player: `displayClientMessage(translatable(kept > 0 ? "message.relics_addon.shield_effect_trimmed" : "message.relics_addon.shield_effect_cut", displayName), true)`.
  - `onDamagePost` → `onDamageLanded`.
- **HiveEvents**(RunSwarm): `onPlayerTick`, `onPlayerLogout` / `onPlayerChangedDimension` (→ `forget`), and `onExplosion` (ServerLevel only).
- **ContainmentEvents**(ContainTargets): `onIncomingDamage` (HIGH), `onEntityJoin`, `onTeleport`, `onEntityInteract` and `onLevelTick`.
  - `onEntityJoin`:
    - Return on the client side.
    - A Projectile: if `swallow`, cancel; then return.
    - A LivingEntity: `onCreatureJoin`.
  - `onEntityInteract`: if not on the client and the target is pinned: `setCancellationResult(FAIL)`, then `setCanceled(true)`.
  - `onTeleport`: if pinned, cancel.
  - `onLevelTick`: ServerLevel only.
- **ShieldStatusCommand**(ManageShieldSettings): the same Brigadier tree, literals, argument `doubleArg(2.0, 24.0)`, Components and keys. It prints `slot + 1` and returns the number of lines, 0 or 1 like today.
- **AutonomousRelicItem**, **HiveRelicItem**, **ComponentItem**: ports are looked up lazily through `ModRuntime.get()`. `HiveRelicItem.canEquip` maps Curios `findCurios(HiveRelicItem)` results to WornSlot and calls `HiveWearRule`.
- **DeviceControlMenu** and **ConsoleButtons**, **OpenDevicePayload**, **DeviceEnergyStorage**: see the file map.

**Outbound (adapter.out)**
- **ItemComponentStore** implements DeviceStore, ShieldStore and HiveStore through lazy `ModDataComponents.X.get()`.
- **StackDevice**: `record StackDevice(ItemStack stack) implements DeviceHandle`, with `of(ItemStack)`, `stack(DeviceHandle)`, and explicit identity `equals`/`hashCode`.
- **RandomInstanceIds**.
- **CuriosEquipment**.
- **ConfigServerSettings**.
- **ManaSources**: implements ManaSupply through an instance method that unwraps the ServerPlayer and calls the static `draw`. The reflection bindings, the static lazy list and disable-on-failure are unchanged; it keeps reading its five `power.*` config values directly.
- **RelicSounds**: the static registry and throttle; `onServerStopped` is public (S7). **SoundCues** implements Cues.
- **McViews** with **McEntityBase**: all entity views extend it; equals is `other instanceof McEntityBase b && entity.equals(b.entity)`, and hashCode is `entity.hashCode()`.
  - `McViews.of(Entity)` returns the most specific wrapper: McPlayer, McMob, McCreature, McProjectile, else McEntity.
  - Also provides `creature()`, `player()`, `mob()`, `projectile()`, `effect()`, `explosion(Explosion, Level)`, `world(Level)` and `unwrap(...)`.
- **McVectors**: `toDomain(Vec3)` and `toMc(Vec3d)`, exact copies.
- **MinecraftWorldQuery**, **MinecraftCreatureActions**, **MinecraftEntityControl**, **MinecraftProjectiles**, **PersistentDataLedger**, **WeakEntityMemories**, **ActionBarNotifier** (`"message.relics_addon.shield_blocked"` with `String.format(Locale.ROOT, "%.1f", absorbed)`).

**Registry**

`ModDamageTypes` (S9) holds:
- the 7 `ResourceKey<DamageType>` constants, named as in DamageKind, and `key(DamageKind)`;
- `SHIELD_PASSES`, `SHIELD_STRIKE` and `SWARM_DAMAGE` damage-type tags;
- `SHIELD_INTERCEPTABLE_PROJECTILES`, an entity-type tag.

**AddonIds** (S7): `MOD_ID = "relics_addon"` (a compile-time constant), `LOGGER = LoggerFactory.getLogger(MOD_ID)`, `id(path)`.

## 8. Composition root and wiring

**ModRuntime**
- `private static volatile ModRuntime current`.
- `get()` throws `IllegalStateException("EX-twins runtime is not wired")` when nothing is installed. `install(ModRuntime)` is package-private and single-shot.
- Accessors (each added in the stage that creates it):

  | Accessor | Port | Stage |
  |---|---|---|
  | `devices()` | ManageDevice | S8 |
  | `progression()` | Progress | S8 |
  | `power()` | ManagePower | S8 |
  | `upkeep()` | RunUpkeep | S8 |
  | `deviceQueries()` | DeviceQueries | S8 |
  | `shieldQueries()` | ShieldQueries | S8 |
  | `swarms()` | ManageSwarm | S8 |
  | `absorbDamage()` | AbsorbDamage | S9 |
  | `shieldMaintenance()` | MaintainShield | S9 |
  | `barrier()` | HoldBackHostiles | S9 |
  | `projectiles()` | InterceptProjectiles | S9 |
  | `effectGuard()` | GuardEffects | S9 |
  | `shieldSettings()` | ManageShieldSettings | S9 |
  | `swarmCombat()` | RunSwarm | S10 |
  | `containment()` | ContainTargets | S10 |
  | `hiveTasks()` | ConfigureHive | S10 |
  | `console()` | OperateConsole | S10 |
  | `threatForecast()` | ForecastThreats | S11 |

- The package-private `events()` returns `EventAdapters`, with `power`, `shieldDamage`, `shieldTick`, `projectiles`, `effectGuard`, `hive`, `containment` and `shieldCommand`.
- The graph is built once per JVM in the @Mod constructor on both dists. That keeps the old static lifetimes: experience windows, the sound throttle, flights, holds and memories.
- Server-side state is touched only on the server thread; client code calls only stateless queries.

**RelicsAddon constructor** (from S7):
```java
ModRuntime runtime = ServerWiring.install();                 // RuntimeFactory.create() + ModRuntime.install
container.registerConfig(ModConfig.Type.SERVER, AddonConfig.SPEC);
container.registerConfig(ModConfig.Type.CLIENT, AddonClientConfig.SPEC);
ServerWiring.registerModBus(modEventBus);
ServerWiring.registerGameBus(NeoForge.EVENT_BUS, runtime);
registerClientOnly(modEventBus);                               // unchanged: FMLEnvironment.dist reflection + "dev.hurtify.relicsaddon.client.ClientEventRegistrar"
LOGGER.info("Loaded three shields and three typed defender hives");
```

**MOD_STEPS**, in order (`record ModBusStep(String name, Consumer<IEventBus> register)`). Every `register` is a lambda `bus -> ...`, never a bound method reference (R-9):

| # | Name | Registration |
|---|---|---|
| 1 | config.loading | `bus.addListener(ModConfigEvent.Loading.class, AddonConfig::onConfigLoad)` |
| 2 | config.reloading | `bus.addListener(ModConfigEvent.Reloading.class, AddonConfig::onConfigLoad)` |
| 3 | sounds | `RelicSounds.registerSounds(bus)` |
| 4 | dataComponents | `ModDataComponents.DATA_COMPONENTS.register(bus)` |
| 5 | items | `ModItems.ITEMS.register(bus)` |
| 6 | creativeTabs | `ModCreativeTabs.CREATIVE_MODE_TABS.register(bus)` |
| 7 | menus | `ModMenus.MENUS.register(bus)` |
| 8 | payloads | `bus.addListener(OpenDevicePayload::register)` |
| 9 | capabilities | `bus.addListener(DeviceEnergyStorage::register)` |

**GAME_HOOKS** (`record GameHook<E extends Event>(String name, Class<E> event, EventPriority priority, Function<ModRuntime, Consumer<E>> handler)`):
- Each is registered with `bus.addListener(priority, false, event, handler.apply(runtime))`, iterating the list in order.
- The order and priorities are a contract.
- In S7 every hook is bound to today's static handler. Later stages rebind hooks in place, never reordering.

| # | Name | Event | Priority | S7 binding | Final binding |
|---|---|---|---|---|---|
| 0 | sounds.serverStopped | ServerStoppedEvent | NORMAL | RelicSounds::onServerStopped (made public in S7) | same |
| 1 | power.playerTick | PlayerTickEvent.Post | NORMAL | DevicePower::onPlayerTick | PowerEvents (S8) |
| 2 | shield.incomingDamage | LivingIncomingDamageEvent | NORMAL | ShieldController::onIncomingDamage | ShieldDamageEvents (S9) |
| 3 | shield.effectApplicable | MobEffectEvent.Applicable | NORMAL | ShieldEffectGuard::onEffectApplicable | EffectGuardEvents (S9) |
| 4 | shield.damagePost | LivingDamageEvent.Post | NORMAL | ShieldEffectGuard::onDamagePost | EffectGuardEvents (S9) |
| 5 | shield.maintenanceTick | PlayerTickEvent.Post | NORMAL | ShieldController::onPlayerTick | ShieldTickEvents (S9) |
| 6 | shield.barrierTick | PlayerTickEvent.Post | NORMAL | ShieldBarrier::onPlayerTick | ShieldTickEvents (S9) |
| 7 | shield.projectileTick | EntityTickEvent.Pre | NORMAL | ShieldProjectileInterceptor::onEntityTick | ProjectileEvents (S9) |
| 8 | hive.playerTick | PlayerTickEvent.Post | NORMAL | HiveController::onPlayerTick | HiveEvents (S10) |
| 9 | hive.playerLogout | PlayerEvent.PlayerLoggedOutEvent | NORMAL | HiveCombatController::onPlayerLogout | HiveEvents (S10) |
| 10 | hive.explosion | ExplosionEvent.Detonate | NORMAL | HiveCombatController::onExplosion | HiveEvents (S10) |
| 11 | containment.incomingDamage | LivingIncomingDamageEvent | HIGH | HiveContainment::onIncomingDamage | ContainmentEvents (S10) |
| 12 | containment.entityJoin | EntityJoinLevelEvent | NORMAL | HiveContainment::onEntityJoin | ContainmentEvents |
| 13 | containment.teleport | EntityTeleportEvent.EnderEntity | NORMAL | HiveContainment::onTeleport | ContainmentEvents |
| 14 | containment.entityInteract | PlayerInteractEvent.EntityInteract | NORMAL | HiveContainment::onEntityInteract | ContainmentEvents |
| 15 | containment.levelTick | LevelTickEvent.Post | NORMAL | HiveContainment::onLevelTick | ContainmentEvents |
| 16 | hive.changedDimension | PlayerEvent.PlayerChangedDimensionEvent | NORMAL | HiveCombatController::onPlayerChangedDimension | HiveEvents (S10) |
| 17 | shield.commands | RegisterCommandsEvent | NORMAL | ShieldStatusCommand::register | ShieldStatusCommand instance (S9) |

`golden/wiring.txt` has one line per entry:
- `mod<TAB>index<TAB>name`
- `game<TAB>index<TAB>name<TAB>event binary name (Class.getName(), e.g. net.neoforged.neoforge.event.tick.PlayerTickEvent$Post)<TAB>priority<TAB>receiveCanceled=false`

WiringCheck reads `ServerWiring.MOD_STEPS` and `GAME_HOOKS` without invoking the handlers and compares them with the golden.

**Client wiring**: ClientEventRegistrar keeps its listener order.
- Game bus: ShieldVisualRenderer, ShieldThreatTracker, HiveVisualRenderer, EffectLights.onFrame, HiveMenuKey.tick, ShiftHoverOpener (client tick, mouse click, tooltip).
- Mod bus: HiveMenuKey.register, registerAdditionalModels, item extensions, screens, shaders, ExFx reload listener.

## 9. Client presentation boundary

- The client is an adapter. Its rendering maths, caches (static maps), render types, shader and Photon/LDL code stay where they are.
- It may use:
  - domain types;
  - `application.port.in` and `port.out` interfaces through `ModRuntime.get()`;
  - `adapter.out.world.McViews` and `McVectors`, and `adapter.out.persistence.StackDevice`;
  - `adapter.in.item`, `adapter.in.menu`, `adapter.in.network`;
  - `adapter.out.config.AddonClientConfig`;
  - `adapter.registry.ModItems` and `ModMenus`;
  - RelicsAddon's constants.
- From S11 it never touches legacy packages other than `registry` (ModItems/ModMenus move in S12), `ModDataComponents`, event or command adapters, or application service classes. From S12 rule C1 enforces this.

What changes:
- **S5**: vector swap.
  - Formation calls go through `client.SwarmMath`, a Vec3-typed facade. It has the same member names and parameter order as `HiveFormation` and `HiveShapes`, with Vec3 in place of Vec3d, and exact conversions.
  - It covers every member that client and gametest.client call: `IMPACT`, `FIRE`, `RETURN_TICKS`, `idle`, `belt`, `healing`, `groupPhase`, `core`, `muster`, `sortie`, `dropletCentre`, `shapeSize`, `clusterCentre`, `clusterLinks`, `clumpRadius`, `station`, `deployed`, `returning`, `TESSERACT_EDGES`, `tesseractCorner`, `axes`, `hexagonCentre`, `hexagonCount`, `hexagonPoint`, `rhombusPoint`, `riftCentre`, `riftSpin`, plus anything else the compiler reports.
  - `ShieldImpact.normal()` and `Crossing.normal()` are converted with `McVectors.toMc` at each use where a Vec3 is needed. Plain `.x/.y/.z` reads compile unchanged. `ShieldResponse.Threat` keeps its Vec3.
- **S11**: data acquisition.
  - **ShieldVisualRenderer**: `findRenderableShield` becomes `ModRuntime.get().shieldQueries().activeShield(McViews.player(p))`, still inside `try { } catch (RuntimeException e)` → empty. State, history, last impact, role (fallback RF_SHIELD), radius and capacity come from ShieldQueries.
  - **ShieldThreatTracker**:
    - collects `McViews.projectile` for every entity that is a Projectile with `threatForecast().supported(view)`;
    - returns if that list is empty;
    - for each player (alive, not a spectator, within 64 blocks of the local player) with an active shield, calls `forecast(...)` and converts to `ShieldResponse.Threat`;
    - stores the first 8 threats (forecast returns them stable-sorted by ticks).
  - **HiveVisualRenderer**: the `EquippedCache` holds `List<EquippedHive>`. Reads go through `swarms()` by handle every frame, so retained fading hives still read their stack. `strikeInterval` comes from `swarms()`.
  - **DeviceControlScreen**: every read goes through `deviceQueries()` with `StackDevice.of(menu.device())`, plus domain `BatteryRules`, `UpgradeRules` and `ProgressionRules`. `renderItem` and the hover name still use the ItemStack. `hiveCapacity()` is `deviceQueries().hiveCapacity(handle)` (D1).
  - **DeviceTargets**: `collect` → `deviceQueries().deviceSlots(view)`, mapped to `Target(charm, slot)`; `locate` → `addressOf`.

## 10. Complete file map

Stages in parentheses. "facade" means a one-line delegation per R-7 until S12 deletes it. Rows that say "imports only" also take the import edits of every move stage (S2 RelicRole/HiveType/AttackMode/DeviceUpgrade/ShieldTopology, S3–S5 records and codecs, S8 items, S10 menu/payload, S11 AddonClientConfig, S12 registry and config).

| Current file | Target | Stage and how |
|---|---|---|
| main/RelicsAddon.java | RelicsAddon (FQCN fixed) | S7 constructor per §8, MOD_ID and LOGGER alias AddonIds; S11 AddonClientConfig import |
| main/AddonConfig.java | adapter.out.config.AddonConfig | S12 move. Spec, keys, ranges, defaults, comments, isString and the five filters unchanged. Read live by ConfigServerSettings (S8) and adapter views (S9). |
| main/RegistryFilter.java | adapter.out.config.RegistryFilter | S12 move, unchanged (package-private parse and Parsed; moves together with AddonConfig and RegistryFilterCheck) |
| main/client/AddonClientConfig.java | adapter.out.config.AddonClientConfig | S11 move; the rename commit makes the five accessors public (R-5); spec and file relics_addon-client.toml unchanged |
| main/client/AnimatedRelicItemRenderer.java | client (stays) | imports only (S2 RelicRole, S8 AutonomousRelicItem) |
| main/client/ClientEventRegistrar.java | client (FQCN fixed) | imports only (S12 ModItems/ModMenus) |
| main/client/DeviceControlScreen.java | client (stays) | S11 per §9; lastTab static kept; D1 |
| main/client/DeviceTargets.java | client (stays) | S11 per §9 |
| main/client/EffectLights.java, GlowBrush.java, HoloPaint.java, HiveMenuKey.java, HiveShellPose.java, RelicAnimationPose.java, ShieldGeometry.java, ShieldHoneycomb.java, ShieldImpactPulse.java, ShieldSurfaceLighting.java, ShieldVisualQuality.java, TwinsFacetPose.java, TwinsShieldLayerPose.java | client (stay) | imports only (S11 AddonClientConfig) |
| main/client/HiveCombatVisual.java | client (stays) | imports only |
| main/client/HiveModeVisual.java | client (stays) | S5 SwarmMath |
| main/client/HiveVisualRenderer.java | client (stays) | S5 SwarmMath; S11 swarms(); S13 drop the 4-argument renderModel |
| main/client/ShieldCellVisual.java, ShieldCircuitTraces.java, ShieldGlow.java, ShieldRefraction.java, ShieldRipple.java | client (stay) | S5 normal conversions |
| main/client/ShieldResponse.java | client (stays) | S5 normal conversions; S13 drop the unused single-impact `at(...)` (optional) |
| main/client/ShieldShellVisual.java | client (stays) | S5 import (ShieldField moved); S13 drop dead triangle() |
| main/client/ShieldThreatTracker.java | client (stays) | S5 conversions; S11 ForecastThreats |
| main/client/ShieldVisualRenderer.java | client (stays) | S5 conversions; S11 ShieldQueries; S13 drop the two unused single-impact renderField overloads (renderWaves, flushGlow and renderType stay) |
| main/client/ShieldHexMesh.java | deleted | S13; ShieldHexMeshCheck iterates ShieldTopology directly, and build/reports/shield-geometry.json stays byte-identical |
| main/client/ShiftHoverOpener.java | client (stays) | S8 import only |
| main/client/fx/ExFx.java, ExFxLibrary.java, PointEffectExecutor.java | client.fx (stay) | imports only |
| main/client/light/DynamicLightsBridge.java, EffectLightBehavior.java, EffectLightPool.java | client.light (stay) | none |
| main/drone/AttackMode.java | domain.hive.AttackMode | S2 move; adds byId |
| main/drone/DroneStackState.java | adapter.out.persistence.LegacyDroneStackState | S3 move and rename in the rename commit (R-5), codecs unchanged (relics_addon:drone_stack_state); S13 drop withEnabled, withIntercept, ready |
| main/drone/HiveCombatState.java | domain.hive.HiveCombatState | S3: stream codec → HiveCodecs; the unused CODEC and Shot.CODEC dropped (retire their field-name literals). S4 move. |
| main/drone/HiveFormation.java | domain.hive.HiveFormation | S5 Vec3d |
| main/drone/HiveOrbit.java | deleted | S2 (no callers) |
| main/drone/HiveSettings.java | domain.hive.HiveSettings | S3 codecs → HiveCodecs (modeById → AttackMode.byId); S4 move; S13 drop healer() |
| main/drone/HiveShapes.java | domain.hive.HiveShapes | S5 Vec3d |
| main/drone/HiveSlots.java | domain.hive.HiveSlots | S4 move; S13 drop lane() |
| main/drone/HiveStackState.java | domain.hive.HiveStackState | S3: ARRAYS, LEGACY, LegacyUnit, fromArrays, masks and stream → HiveCodecs; S4 move |
| main/drone/HiveSupportState.java | domain.hive.HiveSupportState | S3 stream → HiveCodecs; S4 move |
| main/drone/HiveType.java | domain.hive.HiveType | S2 move; tools HIVE_TYPE_SOURCE path updated |
| main/gametest/DeviceGameTests.java, DeviceTestSupport.java, HiveModeGameTests.java, ShieldConfigGameTests.java, ShieldDefenseGameTests.java | stay | import edits in move stages; S5 and S10 named edits; S12 API switch (§11) |
| main/gametest/ShieldSaveDiagnostic.java | stays, unchanged | inspectShieldSave mainClass fixed |
| main/gametest/client/NativeHiveGallery.java | stays | S5 SwarmMath; S12 `HoldPolicy.LIFT` |
| main/gametest/client/NativeModelSmoke.java | stays | S12 import only (ModItems moved) |
| main/gametest/client/NativeShieldGallery.java | stays | S5: NORMAL and fixtures as Vec3d (`new Vec3d(.35,.1,.93).normalize()`), Threat via `McVectors.toMc`; timeline unchanged |
| main/menu/DeviceControlMenu.java | adapter.in.menu.DeviceControlMenu + ConsoleButtons | S10: see below |
| main/network/OpenDevicePayload.java | adapter.in.network.OpenDevicePayload | S10 move; TYPE, version "2", codec and handler unchanged |
| main/power/DeviceEnergy.java | domain.energy.DeviceEnergy | S3 codecs → DeviceCodecs, `ManaSource.byId` public; S4 move |
| main/power/DeviceEnergyStorage.java | adapter.in.capability.DeviceEnergyStorage | S8 move. Delegates to `ModRuntime.get().power()`; provider `item instanceof AutonomousRelicItem i && BatteryRules.hasRf(i.role())`; receive-only; same four items |
| main/power/DevicePower.java | facade → deleted | S6 delegates to BatteryRules and EnergyCosts; S8 facade (hook 1 moves to PowerEvents; onPlayerTick, required() and role() have no remaining callers and go); S12 delete |
| main/power/ManaSources.java | adapter.out.mana.ManaSources | S6 pointsForLevel → ExperienceCurve; S8 move (the rename commit makes the class and `draw` public, R-5), implements ManaSupply |
| main/registry/ModCreativeTabs.java, ModItems.java, ModMenus.java | adapter.registry | S12 move, unchanged (tools REGISTRY_SOURCE path updated); ModMenus' factory keeps `new DeviceControlMenu(id, inv, buf.readBoolean(), buf.readVarInt())` |
| main/registry/ModDataComponents.java | adapter.registry.ModDataComponents | S3 points at the codec classes (same 12 ids, flags, declaration order); S12 move |
| main/relic/AutonomousRelicItem.java | adapter.in.item.AutonomousRelicItem | S8 move and ports (see S8); static ensureState facade until S12, deleted in S12 |
| main/relic/ComponentItem.java | adapter.in.item.ComponentItem | S8 move, unchanged |
| main/relic/DeviceProgression.java | domain.device.DeviceProgression | S3 codecs → DeviceCodecs; S4 move |
| main/relic/DeviceUpgrade.java | domain.device.DeviceUpgrade | S2 move, ids inlined |
| main/relic/ExperienceLimiter.java | deleted | S8, replaced by application.device.ExperienceWindows |
| main/relic/HiveRelicItem.java | adapter.in.item.HiveRelicItem | S6 HiveWearRule; S8 move |
| main/relic/HiveUpgrades.java | facade → deleted | S2 ids alias; S6 → HiveUpgradeEffects; S8 facade → swarms() for healingMultiplier and rebuildMultiplier (still called by the legacy hive controllers until S10); damageMultiplier and repairAmount have no caller once HiveCombatController.attackDamage delegates, and are removed; S12 delete |
| main/relic/RelicProgression.java | deleted | S2 (no callers; retire "_activity") |
| main/relic/RelicRole.java | domain.device.RelicRole | S2 move; S13 drop abilityId, slot and their constructor arguments |
| main/relic/RelicRuntime.java | facade → deleted | S6 → DeviceStats, ProgressionRules, UpgradeRules; S8 facade; S12 delete |
| main/relic/ShieldUpgrades.java | facade → deleted | S2 ids alias; S6 → ShieldUpgradeEffects; S8 facade → shieldQueries(); S12 delete |
| main/server/EquippedRelicSetResolver.java | facade → deleted | S8: findFirstActive (only ever called with `RelicRole.EQUIPMENT_SLOT, RelicRole.shields()`; any other argument throws IllegalArgumentException) → activeShield; isRealPlayer → `McViews.player(p).real()`; findEquipped, findFirstEquipped and isRole have no callers and are removed (this is what lets S13 drop RelicRole.slot()); S12 delete |
| main/server/HiveCombatController.java | facade → deleted | S5 conversions; S6 rules; S8 attackDamage and strikeInterval become one-line delegations to swarms(); S10 facade (tick, onExplosion, strikeInterval, and DRONE_SHOT, SWARM_STRIKE, SWARM_REFLECT aliased to ModDamageTypes; selectTarget, validTarget, attackDamage, clear, SWARM_VOID and SWARM_DAMAGE have no outside callers and go); S12 delete |
| main/server/HiveContainment.java | facade → deleted | S6 HoldPolicy; S10 facade (LIFT, pinned, held returns the domain Hold); S12 delete |
| main/server/HiveController.java | facade → deleted | S6 SwarmRules; S8 facade (active returns the legacy Equipped record, plus capacity and prepare; onPlayerTick and its private settle stay verbatim, R-7 exception); S10 onPlayerTick → swarmCombat(); S12 delete |
| main/server/HiveTaskController.java | facade → deleted | S6 HealingPolicy; S10 facade (settings, still read by the client until S11); S12 delete |
| main/server/ShieldBarrier.java | facade → deleted | S6 BarrierPush; S9 facade (onPlayerTick → barrier()); S12 delete |
| main/server/ShieldController.java | facade → deleted | S5 conversions; S6 DamagePassPolicy, HitImmunity, CellSelection; S9 facade (passesField via McDamageFacts, selectCell → CellSelection); reduction, selectPanel, formatDamage and PASSES_SHIELD have no outside callers and go (the format lives on in ActionBarNotifier, the tag in ModDamageTypes.SHIELD_PASSES); S12 delete |
| main/server/ShieldCoverage.java | deleted | S6 CoverageRule; S9 delete in commit (c), when its last callers migrate |
| main/server/ShieldEffectGuard.java | facade → deleted | S6 EffectTrim; S9 facade (recordHit); S12 delete |
| main/server/ShieldProjectileInterceptor.java | facade → deleted | S5 conversions; S6 ProjectilePolicy; S9 facade (supported, threatens, intercept, impactCost delegate to InterceptProjectiles; crossing and unobstructed keep their verbatim bodies until S11, R-7 exception); S12 delete |
| main/server/ShieldStatusCommand.java | adapter.in.command.ShieldStatusCommand | S6 ShieldStatus; S9 move and port |
| main/server/ShieldStrike.java | facade → deleted | S6 StrikeRules; S9 facade (DISCHARGE, MANA_BURST, TWIN_SURGE, STRIKES aliases; aggressive → Relations, used by the legacy HiveCombatController until S10); S12 delete |
| main/shield/ShieldCellDefense.java, ShieldCellMove.java, ShieldSettings.java, ShieldStackState.java | domain.shield | S3 codecs → ShieldCodecs; S4 move; S13 dead helpers (§4.5) |
| main/shield/ShieldField.java, ShieldImpact.java, ShieldImpactHistory.java | domain.shield | S3 codecs → ShieldCodecs; S5 move with Vec3d |
| main/shield/ShieldParameters.java | facade → deleted | S6 → ShieldStats; S8 facade → shieldQueries(); S12 delete |
| main/shield/ShieldTopology.java | domain.shield.ShieldTopology | S2 move (the rename commit makes legacyRegionFor public; a later S2 commit moves PANEL_* in) |
| main/sound/RelicSounds.java | adapter.out.sound.RelicSounds (+ SoundCues) | S7: split `register` into `registerSounds(modBus)`; `onServerStopped` becomes public and is bound as hook 0; S8 move; S13 drop attack(), impact() and their event helpers (the 16 events stay registered) |
| test/RegistryFilterCheck.java | test/adapter/out/config/RegistryFilterCheck | S12, with verifyRegistryFilter's mainClass |
| test/client/HiveFormationCheck.java | test/domain/hive/HiveFormationCheck | S5, Vec3d; verifyHiveFormation mainClass and classpath |
| test/drone/NetworkCodecCheck.java | test/adapter/out/persistence/NetworkCodecCheck | S3, codec references; verifyNetworkCodecs mainClass; S4 imports; S5 its ShieldImpact fixtures use Vec3d (same values) |
| test/power/DevicePowerCheck.java | test/domain/energy/BatteryRulesCheck | S6; same assertions against BatteryRules.split and ExperienceCurve.pointsForLevel |
| test/client/ShieldHexMeshCheck.java | stays | S2 import; S13 reads ShieldTopology instead of ShieldHexMesh; report unchanged |
| test/client/TwinsShieldLayerAnimationCheck.java | stays | S0 wired as verifyTwinsShieldLayers |
| test/client/EffectLightsCheck, HiveLayerAnimationCheck, RelicAnimationPoseCheck, ShieldImpactPulseCheck, ShieldRippleCheck, TwinsFacetPoseCheck, light/EffectLightPoolCheck | stay, unchanged | none |
| probe/ReleaseProbe.java | stays | S0: `Class.forName("dev.hurtify.relicsaddon.RelicsAddon")`; log texts unchanged |
| tools/validate_hive_assets.py | path constants only | S2 HIVE_TYPE_SOURCE; S4 HIVE_STATE_SOURCE; S12 REGISTRY_SOURCE. The tool already fails today (it expects MAX_DRONES 250, MODEL_BUDGET 750 and two missing GameTest files), so it is not a gate. |

**DeviceControlMenu (S10)**
- Constants unchanged: `BUTTON_TOGGLE=0`, `BUTTON_HEALERS_MINUS_10=1`, `MINUS_1=2`, `PLUS_1=3`, `PLUS_10=4`, `BUTTON_RF_BATTERY=5`, `BUTTON_MANA_BATTERY=6`, `BUTTON_MANA_SOURCE_BASE=7`, `BUTTON_UPGRADE_BASE=20`, `BUTTON_MODE_BASE=40`, `CHARGE_X=204`, `CHARGE_Y=102`, `CHARGE_SLOT=0`, `INVENTORY_START=1`, `INVENTORY_X=36`, `INVENTORY_Y=146`.
- The public constructor `(int, Inventory, boolean charm, int slot)` is kept and delegates to a package-private one taking `(int, Inventory, SlotAddress, OperateConsole)`, with `ModRuntime.get().console()`. `identity = console.identity(console.device(view, address))`.
- `static open(ServerPlayer, boolean, int)`: `console.prepareOpen(...)`; if present, `openMenu` with the same provider, title and extra data.
- `device()` returns `StackDevice.stack(console.device(...))`. `charm()`, `deviceSlot()` and `setChargeVisible` stay.
- `stillValid` → `console.stillValid(...)`.
- `clickMenuButton`: `console.stillValid && real`, then `ConsoleButtons.decode(id)`, then `press`.
- `broadcastChanges`: the same FE pour through `ModRuntime.get().power().receiveFe(...)`, gated by `console.deviceValid(...)`.
- `quickMoveStack`, `removed`, ChargeSlot and `chargeVisible` are unchanged.

**ConsoleButtons.decode**:

| Id | Command |
|---|---|
| 0 | Toggle |
| 1..4 | AdjustHealers(-10, -1, +1, +10) |
| 5 | ToggleBattery(true) |
| 6 | ToggleBattery(false) |
| 7..9 | SelectManaSource(values[id-7]) |
| 40..42 | SelectMode(values[id-40]) |
| 20..26 | Purchase(values[id-20]) |
| anything else | empty → false |

**DeviceConsoleService.press** keeps each branch:
- Toggle: `setEnabled(!enabled)`, then `ui(TOGGLE)`, then true.
- AdjustHealers: non-hive → false. Otherwise `healers = Math.clamp(settings.healerCount(capacity) + delta, 0, capacity)`, then return `configureHealers(...)`.
- ToggleBattery: no matching battery → false. Otherwise flip it, `ui(TOGGLE)`, true.
- SelectManaSource: no mana battery → false. Otherwise set it and return true (no sound).
- SelectMode: non-hive → false. Otherwise `changed = configureMode(...)`. That is true whenever validation passes, even for the mode already selected (Q19). If changed, `ui(TOGGLE)`; return changed.
- Purchase: if it succeeds, `ui(UPGRADE)` and return true; otherwise false.

**AutonomousRelicItem (S8)**
- `use`:
  - client → success;
  - not a real player → pass;
  - otherwise `e = !devices.enabled(d)`, `setEnabled(view, d, e)`, then `displayClientMessage("message.relics_addon." + (hive ? "hive" : "shield") + (e ? "_enabled" : "_disabled"), true)` and consume.
- `inventoryTick` (server only) and `verifyComponentsAfterLoad` → `ensureDefaults`.
- The bar and tooltip read `deviceQueries()` with the same keys, formatting and ChatFormatting.

## 11. Stages

Every stage ends with the standard gate (R-2) and `git diff --quiet hexagon-baseline -- src/main/resources src/generated`.

ArchitectureCheck `--max-legacy` values per stage: S0 56, S2 49, S3 48, S4 37, S5 32, S6 32, S7 32, S8 25, S9 23, S10 21, S11 21, S12 0, S13 0. `--client-strict` is added in S12.

The `--min-*` flags are set to the counts the checker prints at the end of the stage.

Client runs, run one at a time:
- `./gradlew runReleaseCheckClient`: log line `Packaged release verified: TitleScreen, no preview`.
- `./gradlew runStartupCheckClient`: `Normal startup: TitleScreen, no preview`, asserted from S0.

Visual gate:
```
rm -rf run-feature-gif/screenshots && ./gradlew runFeatureGifClient
node tools/compare_frames.mjs --baseline /c/dev/EX-twins-baselines/s0-a --candidate run-feature-gif/screenshots --mask /c/dev/EX-twins-baselines/mask.json
```
The run produces 290 `relics-shield-gif-%03d.png` frames and then 300 `relics-hive-gif-%03d.png` frames. The comparison must exit 0.

Stills to eyeball:
- `rm -f run-verification/screenshots/relics-*.png && ./gradlew runShieldCaptureClient` → 11 × `relics-shields-1920x1080-<stage>.png`.
- `./gradlew runHiveCaptureClient` → `relics-hive-{deploy,droplet,barrage,containment}.png`, which are timed by the wall clock.

Compare them with `/c/dev/EX-twins-baselines/s0-stills`.

### S0: Safety net (tests, build and tooling only; production untouched except the ReleaseProbe string)

1. `git tag hexagon-baseline`.
2. Add `test/contract/ClassFile.java`, `ArchitectureCheck.java` (§3.3; the source pass skips missing directories), `ContractCheck.java`, `EntrypointCheck.java`, `CodecGoldenCheck.java` and `GeometryGoldenCheck.java`.
3. **CodecGoldenCheck** writes `golden/codecs.txt` as `label<TAB>SNBT|-|ERROR:<Class>:<message><TAB>stream-hex|-|ERROR:<Class>`.
   - Use `NbtOps` with `Tag.toString()`, which sorts the keys.
   - Use `Unpooled` buffers, and `RegistryFriendlyByteBuf(buf, RegistryAccess.EMPTY)` for DeviceProgression and OpenDevicePayload.
   - Reference codec constants directly, never ModDataComponents.
   - Catch both DataResult errors and thrown exceptions (the ShieldStackState constructor throws inside decode, Q15).
   - "→ ERROR" stream fixtures are hand-written byte sequences, because the records clamp and so cannot encode them.
   - Fixtures:
     - **hive_settings**: DEFAULT; (37, each mode); (750, CONTAINMENT); NBT `IntTag 12`; stream healers 751 → ERROR.
     - **hive_stack_state**: DEFAULT; 12 fresh units; the 750-unit mix from NetworkCodecCheck; the legacy `units` compound list; stream count 751 → ERROR.
     - **hive_combat_state** (stream): DEFAULT; active with 100 shots of kinds 4..10 for each mode; 101 shots → ERROR.
     - **hive_support_state**: DEFAULT; (true, 123456789).
     - **shield_settings**: DEFAULT; (5.5, "owner"); (24, "all"); NBT `{}`; NBT `{coverage:"bogus"}`.
     - **shield_stack_state**: DEFAULT; damaged (3 local cells, buffer 100, 3 moves, gatherTime 77); legacy NBT without cells (front 3, left 12, right 7, back 0); legacy 42 cells with 2 moves; NBT with 41 cells → ERROR; stream with 4 moves → ERROR.
     - **shield_impact**: an absorbed hit with normal (1,0,0), brokenCells [3], distance 1.5; a strike; normal (.3,.4,.5); a zero normal; a NaN distance.
     - **shield_impacts**: EMPTY; 12 impacts; 13 impacts.
     - **device_energy**: EMPTY; (1000000, 100000, false, true, each source); NBT without optional fields; NBT with source "bogus".
     - **device_progression**: DEFAULT; (100, 3, 2, 0b10_01_11); stream level 99; NBT level 11 → ERROR.
     - **instance_id**: "0f8fad5b-d9cb-469f-a165-70867728950e".
     - **drone_stack_state**: DEFAULT; (false, 42, 1.5f).
     - **open_device payload**: (true, 0); (false, 40).
4. **GeometryGoldenCheck** writes `golden/geometry.txt` as `function<TAB>sha256`, one digest per function, over `Double.doubleToLongBits` and int outputs (booleans as 0/1).
   - Inputs:
     - owner (3, 64, -2) and target (-4, 63, 7);
     - yaws {0, -0.0f, 35, 89.99f, 90, 180, -180, 271.5f, 1e6f, -1e-7f};
     - times {0, .5, 187.25, 5000.5, 3000000.75};
     - every AttackMode × HiveType;
     - slots {1, 2, 12, 16, 17, 100, 250};
     - units {1, 12, 60, 250, 750};
     - intervals {20, 60, 100}.
   - Functions:
     - every public HiveFormation and HiveShapes method;
     - HiveSlots;
     - ShieldField `intercept`, `incoming`, `focus` and `fade` over a grid;
     - ShieldTopology `cells` (centres, perimeters, panels), `nearestTo`, `neighbors`, `adjacent`, `migrateLegacyCell`, and `nearest` over a sphere grid;
     - the cell selection written out as `Vec3.directionFromRotation(0, yaw)` plus `nearest`;
     - ShieldCellDefense damage, gather and repair driven by `new Random(42)` over 1000 operations from DEFAULT.
5. Record the goldens with `./gradlew verifyContracts verifyCodecGolden verifyGeometryGolden -PgoldenRecord`. Read the SNBT, then commit `golden/`, including an empty `literals-retired.txt` with a header line.
6. **build.gradle**:
   - `def goldenRecord = providers.gradleProperty('goldenRecord').isPresent()`;
   - the tasks in §3.4 (S0 rows) added to `check`;
   - `def architectureArgs = ['--max-legacy','56','--min-domain','0','--min-application','0','--min-adapter','0']`; the task prepends `build/classes/java/main` and `--sources src/main/java/dev/hurtify/relicsaddon`;
   - `def expectedGameTests = 36`, with the runGameTestServer doLast matching `/All (\d+) required tests passed/` and requiring `group(1) == expectedGameTests` on a fresh log;
   - a runStartupCheckClient doFirst/doLast asserting `Normal startup: TitleScreen, no preview` in a fresh `run-verification/logs/latest.log`.
7. `probe/ReleaseProbe.java`: change the Class.forName target to `"dev.hurtify.relicsaddon.RelicsAddon"`.
8. **tools/compare_frames.mjs** (Node, pngjs from tools/node_modules; run `npm install` in tools if it is missing):
   - `--make-mask <out.json> <dirA> <dirB> [<dirC> ...] [--dilate <px>]` records, per `relics-*-gif-*.png`, the union of pixels that differ between any pair of the given runs, dilated by `--dilate` pixels (default 4), as ranges.
   - `--baseline <dir> --candidate <dir> [--mask <json>]` exits 1 on a missing frame, a size difference or any unmasked pixel difference, and prints per-file counts.
9. Run directories: `mkdir -p run-release-check && cp run-verification/options.txt run-release-check/`. A fresh run directory would otherwise wait at the accessibility screen.
10. Baselines:
    1. Run runFeatureGifClient three times, clearing `run-feature-gif/screenshots` first each time, and copy the frames to `/c/dev/EX-twins-baselines/s0-a`, `/s0-b` and `/s0-c`.
    2. `node tools/compare_frames.mjs --make-mask .../mask.json .../s0-a .../s0-b .../s0-c`. The hive frames animate item models on wall-clock time (AnimatedRelicItemRenderer falls back to `Util.getMillis()` without a level). The dilated three-run mask absorbs that, including pixels that happened to match in two runs.
    3. Run runShieldCaptureClient and runHiveCaptureClient and copy the stills to `.../s0-stills`.
11. If TwinsShieldLayerAnimationCheck fails on the unmodified code, fix the check (test code only) and report it.

Verify:
- the gate (36);
- runReleaseCheckClient and runStartupCheckClient;
- mutation spot checks, made locally and reverted:
  - renaming the `lastAbsorbed` codec field fails verifyCodecGolden;
  - swapping DROPLET and BARRAGE fails verifyCodecGolden;
  - a throwaway `main/domain/Probe.java` that references `net.minecraft.world.phys.Vec3` fails verifyArchitecture;
  - an edited lang file fails verifyContracts.

### S1: Characterization GameTests (gametest only)

Add three classes under `main/gametest/`: `ContractGameTests`, `HiveCharacterizationGameTests` and `DeviceCharacterizationGameTests`.
- They are `@GameTestHolder("relics_addon")` and `@PrefixGameTestTemplate(false)`, and reuse DeviceTestSupport.
- Fixtures private to HiveModeGameTests are re-implemented locally.
- Each test asserts what the current code does.

- **ContractGameTests.registryContractsHold** (test_room):
  - the 15 item ids are in `BuiltInRegistries.ITEM`;
  - the 12 component ids are in `DATA_COMPONENT_TYPE`: 9 with `codec() != null`, and `hive_support_state`, `hive_combat_state` and `shield_impacts` with a null codec; all 12 with a stream codec;
  - there are 54 `SOUND_EVENT` keys in namespace relics_addon;
  - menu `device_control` and tab `main` exist;
  - the 7 damage types are in the level's `DAMAGE_TYPE` registry;
  - the tags shield_passes, shield_strike, swarm_damage and shield_interceptable_projectiles are present.
- **HiveCharacterizationGameTests.healersMendTheOwner** (field_arena, timeoutTicks 200):
  - Setup: an RF hive with `HiveSettings(3, BARRAGE)`; `player.setHealth(10)`; record the rf.
  - Each tick, run `HiveCombatController.tick(player)`.
  - Succeed when health > 10, the support state is active, and rf has dropped.
- **HiveCharacterizationGameTests.explosionsDamageDronesInReach**:
  - Setup: a fight with RF DROPLET, the same fixture as HiveModeGameTests. Tick once; assert combat is active.
  - Within the same tick, construct `new Explosion(level, null, x, y+.86, z, 1F, false, Explosion.BlockInteraction.KEEP)` at the owner's feet + .86, and call `HiveCombatController.onExplosion(new ExplosionEvent.Detonate(level, explosion, new ArrayList<>()))`.
  - Assert that some unit has `lastHit == now` and `hp < 3`.
- **HiveCharacterizationGameTests.swarmRecallsWhenTheTargetIsGone**:
  - Fight with RF BARRAGE; tick until combat is active.
  - After `runAfterDelay(5)`: `husk.discard()`, then tick.
  - Assert inactive, `changedAt == now` and `targetId == -1`.
- **HiveCharacterizationGameTests.hiveRepairsAndSettlesOnTheTenthTick**:
  - Setup: an RF hive; `prepare(false)`; set unit 0 to `Unit(1, 0, -1, 0)`; record the rf.
  - On the first tick with `gameTime % 10 == 0`, call `HiveController.onPlayerTick(new PlayerTickEvent.Post(player))` directly (not through the bus, so upkeep cannot interfere).
  - Assert unit 0 equals `Unit.fresh()` and rf dropped by exactly `2 * HIVE_REPAIR_PER_HP * FE_PER_POINT`.
- **DeviceCharacterizationGameTests.consoleConfiguresHealersAndMode**:
  - An RF hive in charm slot 0; `menu = new DeviceControlMenu(1, inv, true, 0)`.
  - Button 4 gives healers 10; button 2 gives 9; button 4 gives 12 (capped at capacity); `40 + CONTAINMENT.ordinal()` gives mode CONTAINMENT.
  - `BUTTON_MANA_SOURCE_BASE + 1` returns false.
  - After the INSTANCE_ID is changed, every button returns false.
  - An RF shield in slot 1 with a menu on (true, 1): button 3 returns false.
- **DeviceCharacterizationGameTests.upkeepAndRefillRunOnTheirTicks** (drives the real PlayerTick listeners):
  - An RF shield in slot 0 and a Mana shield in slot 1. Set the mana to `capacity - 100` with source EXPERIENCE, and `giveExperiencePoints(1000)`.
  - On the first tick with `% 20 == 0`, post `new PlayerTickEvent.Post(player)` on `NeoForge.EVENT_BUS`. This runs hooks 1, 5, 6 and 8 through whatever they are bound to. The RF shield loses exactly `SHIELD_UPKEEP * FE_PER_POINT`, and its integrity is unchanged.
  - Then, on the first tick with `% 10 == 5`, post it again: the mana reaches capacity and `player.totalExperience` drops by 10.
  - This test never changes afterwards: it proves the S8–S10 rebinding of the PlayerTick hooks. If another mod's PlayerTickEvent listener fails on the fixture player, fall back to calling `DevicePower.onPlayerTick` directly, report it, and keep that call in the S12 table.
- **DeviceCharacterizationGameTests.shieldCommandsChangeTheWornShield**, using `server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), …)`:
  - `relics_addon shield_radius 5` at level 0 leaves the radius at 2.
  - After setting the progression to level 3, it sets 5.
  - `relics_addon shield_coverage owner` sets "owner".

Set `expectedGameTests = 44`. If a test is dropped as unreliable, use the actual count and report it. Run runGameTestServer three times in a row; all runs must pass. Legacy count: 56.

### S2: Math kernel and pure enums and topology

- Create `domain/math/Vec3d.java`, `Box.java` and `Trig.java` (§4.1).
- Add `test/domain/math/MathKernelCheck.java` and the verifyMathKernel task.
  - Compare against Vec3, AABB and Mth bit for bit.
  - Inputs: all 65536 table indices; 10^6 random floats from `new Random(0x5EED)`, including ±0, NaN, ±Infinity and 1e±30; 10^6 random and edge vectors, including tiny, huge and NaN.
  - Cover every Vec3d operation, including equals and hashCode, `directionFromRotation` over float grids, the Box constructor, inflate, expandTowards, contains and center, and Trig.ceil and floor.
- `git mv` to `domain.device`: RelicRole and DeviceUpgrade. A later commit inlines the ids, and the ShieldUpgrades and HiveUpgrades constants become `= DeviceUpgrade.X.id()`.
- `git mv` to `domain.hive`: AttackMode (+ byId) and HiveType.
- `git mv` ShieldTopology to `domain.shield`. The rename commit makes `legacyRegionFor` public, because ShieldStackState still lives in `shield` (R-5). A later commit moves PANEL_* in and makes `ShieldStackState.PANEL_*` aliases.
- Delete HiveOrbit and RelicProgression, and retire "_activity".
- Fix imports everywhere, tests included, and update the tools HIVE_TYPE_SOURCE path.
- Arguments: `--max-legacy 49`, and `--min-domain` = the printed count.

Verify: the gate, including verifyMathKernel, and geometry, codec and contract goldens unchanged. Legacy count: 49.

### S3: Codecs move to adapter.out.persistence (records stay)

- Create:
  - `adapter/out/persistence/HiveCodecs.java`: `SETTINGS`, `SETTINGS_STREAM`, `STACK_STATE`, `STACK_STATE_STREAM`, `COMBAT_STATE_STREAM`, `SUPPORT_STATE_STREAM`;
  - `ShieldCodecs.java`: `CELL_MOVE`, `STACK_STATE`, `STACK_STATE_STREAM`, `IMPACT`, `IMPACT_STREAM`, `IMPACT_HISTORY`, `IMPACT_HISTORY_STREAM`, `SETTINGS`, `SETTINGS_STREAM`;
  - `DeviceCodecs.java`: `ENERGY`, `ENERGY_STREAM`, `PROGRESSION`, `PROGRESSION_STREAM`.
- Declare the static fields in dependency order: the legacy unit codec before `STACK_STATE`, `CELL_MOVE` before `STACK_STATE`, `IMPACT` before `IMPACT_HISTORY`.
- Move every Codec and StreamCodec out of the records verbatim, with the same field order, names, defaults, alternatives and exceptions. Field access inside anonymous codecs becomes record accessors; the bytes are identical.
- ShieldCodecs ends with `static { Objects.requireNonNull(ShieldStackState.DEFAULT); }`. This keeps the ShieldTopology build inside the data-component RegisterEvent. It now happens at the shield_settings registration, the first to touch ShieldCodecs.
- Drop the unused `HiveCombatState.CODEC` and `Shot.CODEC`, and retire the literals that disappear.
- `git mv` DroneStackState to LegacyDroneStackState. The type rename is part of the rename commit (R-5).
- ModDataComponents references the new constants, with the same 12 ids, flags and order.
- `git mv` the NetworkCodecCheck test and update the verifyNetworkCodecs mainClass. Update NetworkCodecCheck's and CodecGoldenCheck's references.

Verify: the gate with codec goldens identical; runReleaseCheckClient. Legacy count: 48.

### S4: Pure records move to domain (rename only)

- `git mv` to `domain.hive`: HiveSettings, HiveStackState, HiveCombatState, HiveSupportState, HiveSlots.
- `git mv` to `domain.shield`: ShieldCellMove, ShieldStackState, ShieldCellDefense, ShieldSettings.
- `git mv` DeviceEnergy to `domain.energy` and DeviceProgression to `domain.device`.
- Imports only, tests included. Update the tools HIVE_STATE_SOURCE path.

Verify: the gate, with `git diff -M` showing only renames. Legacy count: 37.

### S5: Vector swap (formation, field and impacts move to domain)

- `git mv` HiveFormation and HiveShapes to `domain.hive`, and ShieldField, ShieldImpact and ShieldImpactHistory to `domain.shield` (rename-only commit first).
- Rename Vec3 to Vec3d and `Vec3.directionFromRotation` to `Vec3d.directionFromRotation`. No arithmetic changes.
- Add `adapter/out/world/McVectors.java` and `client/SwarmMath.java` (§9).
- ShieldCodecs: `IMPACT` uses `Vec3.CODEC.xmap(McVectors::toDomain, McVectors::toMc).fieldOf("normal")`.
- Convert at the call sites in the legacy server classes, the client and both galleries.
- Named GameTest edit, ShieldDefenseGameTests: `last.normal().dot(outward)` becomes `last.normal().dot(McVectors.toDomain(outward))`.
- Update the test fixtures that build ShieldImpacts:
  - NetworkCodecCheck (`new ShieldImpact(new Vec3d(1, 0, 0), …)`, `strike(new Vec3d(0, 0, 1), …)`, `new ShieldImpact(new Vec3d(0, 1, 0), …)`);
  - CodecGoldenCheck's shield_impact and shield_impacts fixtures.
  The values are identical, and the codec goldens must stay identical.
- `git mv` HiveFormationCheck to `test/domain/hive`, with Vec3d types and unchanged assertions. verifyHiveFormation gets the new mainClass and the test runtime classpath only.
- Update GeometryGoldenCheck to the Vec3d API. This includes its cell-selection function, which switches to `Vec3d.directionFromRotation`, proven identical by verifyMathKernel. The inputs and hashes stay identical, and verifyGeometryGolden moves to the test runtime classpath only.

Verify:
- the gate, with every golden identical;
- runReleaseCheckClient;
- the visual gate, which must exit 0;
- eyeball the stills against s0-stills and send them.

Legacy count: 32.

### S6: Domain rules extracted (legacy classes delegate; no call-site changes)

- Create the S6 classes of §4.2–4.6: DeviceStat, DeviceStats, ProgressionRules, UpgradeRules, WornSlot, HiveWearRule, EnergyCosts, BatteryRules, ExperienceCurve, DamageKind, Coverage, ShieldStats, ShieldUpgradeEffects, CellSelection, DamageFacts, DamagePassPolicy, HitImmunity, EffectTrim, ProjectileFacts, ProjectilePolicy, CoverageRule, BarrierPush, StrikeRules, ShieldStatus, HiveUpgradeEffects, SwarmRules, DroneDamage, SwarmStrike, ShotLog, ChargeFlight, HoldPolicy (primitive methods) and HealingPolicy.
- The legacy classes keep every public signature and call these rules.
- `RelicRuntime.stat(String id, …)` maps ids with `DeviceStat.byId`; an unknown id gives `Double.isFinite(fallback) ? Math.clamp(fallback, min, max) : fallback`.
- `ShieldController.passesField` builds an anonymous `DamageFacts` over the DamageSource, and each fact stays lazy.
- ProjectileFacts is built from the Projectile as an anonymous class.
- HiveRelicItem uses HiveWearRule.
- `git mv` DevicePowerCheck to `test/domain/energy/BatteryRulesCheck` (verifyDevicePower: new mainClass, test runtime only).
- Add `test/domain/DomainRulesCheck.java` and the verifyDomainRules task. It asserts at least:
  - XP 60, 2040 and a total of 8100 to level 10;
  - `gain(20) == 3`;
  - stat values for every stat at levels 0, 5 and 10 for every role. attack_damage and cooldown must throw IllegalArgumentException for a non-hive role and return the clamped 0 for an empty role;
  - split and drain shortfall semantics;
  - capacity 25000/100000;
  - strike damage per role and level;
  - the upgrade tables, including Twins gathering = 0 at every rank and the MANA/TWINS rebuild values at rank 3 computed as `r * (cap / 3D)`;
  - the pass-policy truth table (16 rows);
  - immunity 1, then +2, then +1, and a float tick at 2^24 + 1;
  - trim 200 → 50 with 6/2, infinite → 1200;
  - projectile costs 8/5/1/4 and arrow crit;
  - barrier geometry samples;
  - explosion damage `ceil(3(1-d/2r))`;
  - knockback;
  - healing stagger, request and cost;
  - hold join and stale;
  - lift easing at t = 0, .5 and 1;
  - ShieldStatus precedence;
  - HiveWearRule.

Verify: the gate. Legacy count: 32.

### S7: Composition root and wiring (no behaviour moved)

- Create `adapter/AddonIds.java` and `bootstrap/ModRuntime.java`, which has no ports yet.
- Create `RuntimeFactory.java`, `ServerWiring.java`, `GameHook.java`, `ModBusStep.java` and `EventAdapters.java`. MOD_STEPS entries are lambdas (R-9).
- Rewrite the RelicsAddon constructor per §8. Every hook is bound to today's static handler.
- RelicSounds: `register(modBus)` becomes `registerSounds(modBus)`. `onServerStopped` becomes public and is bound as hook 0.
- Author `golden/wiring.txt` from the §8 tables. Add `test/contract/WiringCheck.java` and the verifyWiring task.

Verify:
- the gate: the GameTests exercise the event-driven hooks, including the PlayerTick hooks through upkeepAndRefillRunOnTheirTicks; verifyWiring proves order and priority;
- runReleaseCheckClient and runStartupCheckClient;
- optional: `./gradlew runServer` until it prints "Done", then stop it.

Legacy count: 32.

### S8: Device, energy and progression slice

**Ports and application**
- port.out: WorldRef, EntityView, Creature, MobView, PlayerView, DeviceHandle, SlotAddress, CharmSlot, DeviceStore, ShieldStore, HiveStore, Equipment, ServerSettings, ManaSupply, IdSource, Cues, UiCue.
- port.in: ManageDevice, Progress, ManagePower, RunUpkeep, DeviceQueries, ShieldQueries (including `covers`), ManageSwarm, EquippedHive.
- application: DeviceService, ProgressionService, ExperienceWindows, DeviceQueryService, PowerService, UpkeepService, ShieldLocator, HiveRoster.

**Adapters**
- ItemComponentStore, StackDevice, RandomInstanceIds;
- CuriosEquipment;
- ConfigServerSettings;
- McViews and the entity, creature, mob, world and player wrappers;
- SoundCues;
- PowerEvents.

**Moves** (rename-only commit first):
- AutonomousRelicItem, HiveRelicItem and ComponentItem → adapter.in.item;
- DeviceEnergyStorage → adapter.in.capability;
- ManaSources → adapter.out.mana. The rename commit makes the class and `draw` public, because DevicePower still calls `draw` until the facade commit (R-5). A later commit makes it implement ManaSupply;
- RelicSounds → adapter.out.sound;
- delete ExperienceLimiter.

**Facades** per the file map: RelicRuntime, DevicePower (without onPlayerTick), ShieldUpgrades, HiveUpgrades (healingMultiplier, rebuildMultiplier), EquippedRelicSetResolver (findFirstActive, isRealPlayer), ShieldParameters, HiveController (onPlayerTick unchanged for now, R-7 exception), and `AutonomousRelicItem.ensureState`.
- HiveCombatController.attackDamage and strikeInterval become one-line delegations to `ModRuntime.get().swarms()`, with the same numbers.
- HiveUpgrades.damageMultiplier and repairAmount then have no callers and are removed.

**Rebinding**: hook 1 → PowerEvents.

**Tests**: add `test/application/ApplicationRulesCheck.java` (verifyApplication, test runtime only), with in-memory fakes of DeviceStore, ShieldStore, HiveStore, ServerSettings, Equipment, Cues and IdSource, and fake PlayerView/WorldRef. It must cover:
- PowerService: free when batteries are not required or the player has instabuild; no write when free or `points <= 0`; drain on a shortfall empties the usable batteries and returns false; the split; the receiveFe clamp and 20000 cap.
- ProgressionService: award rounding; the 30-per-1200-tick cap; creative ignored; a clock that goes back resets the window; an empty instance id gains nothing.
- DeviceService: `ensureDefaults` writes only the missing id, progression and energy, and re-sets the swarm or shield state; `setEnabled` clears the combat state when a hive is disabled and cues the summon only on the server side.
- The ShieldLocator role-then-slot priority, and `covers` for each coverage mode.
- The HiveRoster single-hive break.

GameTests: import edits only (items moved).

Verify: the gate, runReleaseCheckClient and runStartupCheckClient. Manual (optional; ask the user): in runClient, toggle a device, use its tooltip, and charge it from an FE item. Legacy count: 25.

### S9: Shield slice

**Ports**
- port.out: ProjectileView, EffectView, WorldQuery (without firstHit), CreatureActions, ProjectileControl, ProjectileLedger, EntityMemories, EntityMemory, Notifier.
- port.in: IncomingDamage, AbsorbDamage, MaintainShield, HoldBackHostiles, InterceptProjectiles, EffectApplication, EffectVerdict, GuardEffects, ManageShieldSettings, ShieldStatusLine, RadiusChange, CoverageChange.

**Application**: Relations, ShieldImpacts, ShieldDefenseService, ShieldMaintenanceService, ShieldBarrierService, ProjectileInterceptionService, EffectGuardService, ShieldCommandService.

**Adapters**
- McProjectile, McEffect, MinecraftWorldQuery, MinecraftCreatureActions, MinecraftProjectiles, PersistentDataLedger, WeakEntityMemories, ActionBarNotifier;
- ShieldDamageEvents with McIncomingDamage and McDamageFacts; ShieldTickEvents; ProjectileEvents; EffectGuardEvents with McEffectApplication;
- adapter.registry.ModDamageTypes.

**Moves and deletions**: `git mv` ShieldStatusCommand to adapter.in.command in the rename commit, then make it an instance with the port. Delete ShieldCoverage in commit (c).

**Facades**, per the file map:
- ShieldController (`passesField(DamageSource)`, `selectCell(Player, Vec3)`);
- ShieldBarrier;
- ShieldStrike;
- ShieldEffectGuard;
- ShieldProjectileInterceptor, whose crossing and unobstructed keep verbatim bodies until S11.

**Rebinding**: hooks 2–7 and 17. Add EventAdapters fields and ModRuntime accessors.

**Commits**, one per step, the gate after each:
- (a) effect guard;
- (b) barrier and strike;
- (c) interception, absorb and maintenance, together. The legacy ShieldController calls the interceptor's consumePaidDamage, alreadyAbsorbed and passedThrough, which have no port, so they must migrate in the same commit;
- (d) command.

Verify:
- the gate: ShieldDefenseGameTests 17/17, ShieldConfigGameTests 5/5, 44 in total;
- review a side-by-side diff of each old handler against its service;
- runReleaseCheckClient.

Legacy count: 23.

### S10: Hive slice and console

**Ports**
- port.out: ExplosionView, SegmentHit, WorldQuery.firstHit, EntityControl.
- port.in: RunSwarm, ContainTargets, ConfigureHive, OperateConsole, ConsoleCommand.

**Domain**: Hold, plus the HoldPolicy Hold overloads listed in §4.6.

**Application**: ChargeFlights, HiveHealingService, HiveTaskService, ContainmentService (with the application-level methods of §6.1), HiveCombatService, DeviceConsoleService.

**Adapters**: McExplosion, MinecraftEntityControl, HiveEvents, ContainmentEvents.

**Moves**: `git mv` DeviceControlMenu to adapter.in.menu, then add ConsoleButtons and the port (file map). `git mv` OpenDevicePayload to adapter.in.network.

**Facades**: HiveCombatController, HiveContainment, HiveController (onPlayerTick → `swarmCombat()`), HiveTaskController (settings), per the file map.

**Rebinding**: hooks 8–16.

**Named GameTest edit**, HiveModeGameTests.manaWardTurnsBlowsBack: `HiveContainment.Hold hold` becomes `Hold hold`, importing `dev.hurtify.relicsaddon.domain.hive.Hold`. The facade returns the domain Hold or null.

**Commits**: the rename-only commit, then one behaviour commit covering containment, healing, tasks, combat and flights, console, menu and payload, then the gate. The legacy HiveCombatController and DeviceControlMenu call non-port members of each other's classes: HiveContainment.hold, release, releaseAll, immune, refill, drainIntercepted and drainReflected and the Hold type; HiveTaskController.tickHealing and locate. So these cannot migrate separately without logic in facades.

Verify:
- the gate: HiveModeGameTests 8/8, DeviceGameTests 6/6, the S1 hive and console tests, 44 in total;
- runReleaseCheckClient.

Manual, optional (ask the user): in runClient, one hive per mode; save and reload while a Twins target is lifted, and check its gravity comes back on load.

Legacy count: 21.

### S11: The client reads through ports

- Add port.in `ForecastThreats` and `ShieldThreat`, and `application.shield.ThreatForecastService`, with the ModRuntime accessor `threatForecast()`.
- Change the client classes per §9. After this, ShieldProjectileInterceptor.crossing and unobstructed and ShieldController.selectCell have no callers left; they go with their facades in S12.
- `git mv` AddonClientConfig to adapter.out.config. The rename commit makes the five accessors public (R-5). Update the RelicsAddon import.
- Do not add `--client-strict` yet. ClientEventRegistrar still references `registry.ModItems` and `registry.ModMenus`, which are LEGACY until S12. Run the check once with the flag locally and confirm that those two references are its only C1 hits.

Verify:
- the gate;
- runReleaseCheckClient and runStartupCheckClient;
- the visual gate, which must exit 0;
- eyeball and send the stills.

Manual in-world check, optional (ask the user), with LambDynamicLights:
- each shield absorbs a melee hit and an arrow;
- threat anticipation shows before arrows land;
- a cell breaks and the shield collapses;
- strike arcs appear;
- each hive mode works, including the recall fade;
- every console tab works;
- the H key and Shift-hover open the console.

Legacy count: 21.

### S12: Relocation and cutover

**Moves** (rename-only commit):
- `registry/*` → `adapter/registry/`;
- AddonConfig and RegistryFilter → `adapter/out/config/`;
- `test/RegistryFilterCheck` → `test/adapter/out/config/` (verifyRegistryFilter mainClass);
- update the tools REGISTRY_SOURCE path;
- import edits in ClientEventRegistrar, NativeModelSmoke, the GameTests and the rest.

**DeviceTestSupport helpers**:
- `static ModRuntime runtime()`
- `static PlayerView view(ServerPlayer)`
- `static DeviceHandle device(ItemStack)`
- `static DeviceProgression progression(ItemStack)` (component read)
- `static DeviceEnergy energy(ItemStack)` (via `power().energy`)

**GameTest API switch.** Only these qualifier changes are allowed; messages and numbers are unchanged.

| Old call | New call |
|---|---|
| `RelicRuntime.experienceToNext(n)` | `runtime().progression().experienceToNext(n)` |
| `RelicRuntime.awardAbsorption(p, s, v)` | `runtime().progression().award(view(p), device(s), v)` |
| `RelicRuntime.progression(s)` | `progression(s)` |
| `RelicRuntime.enabled(s)` | `runtime().devices().enabled(device(s))` |
| `RelicRuntime.setEnabled(p, s, b)` | `runtime().devices().setEnabled(view(p), device(s), b)` |
| `HiveController.active(p)` | `runtime().swarms().active(view(p))` |
| `HiveController.prepare(p, s, b)` | `runtime().swarms().prepare(view(p), device(s), b)` |
| `HiveController.onPlayerTick(ev)` | `runtime().swarmCombat().onPlayerTick(view(p))` |
| `HiveCombatController.tick(p)` | `runtime().swarmCombat().combatTick(view(p))` |
| `HiveCombatController.onExplosion(ev)` | `runtime().swarmCombat().onExplosion(McViews.explosion(explosion, level))` |
| `HiveCombatController.SWARM_STRIKE`, `DRONE_SHOT`, `SWARM_REFLECT` | `ModDamageTypes.*` |
| `HiveContainment.pinned(e)` | `runtime().containment().pinned(McViews.of(e))` |
| `HiveContainment.held(e)` | `runtime().containment().held(McViews.of(e)).orElse(null)` |
| `HiveContainment.LIFT` | `HoldPolicy.LIFT` |
| `DevicePower.energy(s)` | `energy(s)` |
| `DevicePower.drain`, `powered`, `full` | `runtime().power().…` |
| `DevicePower.FE_PER_POINT`, `STRIKE`, `SHIELD_UPKEEP`, `HIVE_REPAIR_PER_HP` | `EnergyCosts.*` |
| `AutonomousRelicItem.ensureState(s)` | `runtime().devices().ensureDefaults(device(s))` |
| `ShieldController.passesField(src)` | `runtime().absorbDamage().passesField(McDamageFacts.of(src))` |
| `ShieldProjectileInterceptor.supported`, `intercept`, `impactCost` | `runtime().projectiles().…(McViews.projectile(p)[, view(player)])` |
| `ShieldBarrier.onPlayerTick(ev)` | `runtime().barrier().onPlayerTick(view(p))` |
| `ShieldEffectGuard.recordHit(v, a, x, y)` | `runtime().effectGuard().recordHit(McViews.creature(v), Optional.of(McViews.of(a)), x, y)` |
| `ShieldParameters.radius(p, s)` | `runtime().shieldQueries().radius(device(s))` |
| `ShieldParameters.strikeDamage(s)` | `runtime().deviceQueries().strikeDamage(device(s))` |
| `ShieldParameters.strikeCooldown()` | `runtime().shieldQueries().strikeCooldown()` |
| `ShieldStrike.DISCHARGE`, `MANA_BURST`, `TWIN_SURGE` | `ModDamageTypes.SHIELD_DISCHARGE`, `SHIELD_MANA_BURST`, `SHIELD_TWIN_SURGE` |

`upkeepAndRefillRunOnTheirTicks` posts on the bus and needs no switch. Only if S1 had to fall back to a direct call, also switch `DevicePower.onPlayerTick(ev)` → `runtime().upkeep().onPlayerTick(view(p))`.

Also update NativeHiveGallery's LIFT.

**Deletions**:
- all facades: the remaining `server/*` classes, `relic/RelicRuntime`, `ShieldUpgrades`, `HiveUpgrades`, `power/DevicePower`, `shield/ShieldParameters`, and the static `AutonomousRelicItem.ensureState`;
- any legacy classes left;
- the empty legacy packages.

**Arguments**: `--max-legacy 0` and `--client-strict`.

Verify:
- the gate: 44 tests with identical names;
- runReleaseCheckClient and runStartupCheckClient;
- the visual gate.

Legacy count: 0.

### S13: Cleanup, docs and the optional core source set

**Delete the dead code listed in the file map.** Each removal needs a grep with no callers, and the compiler proves it.
- ShieldStackState helpers (including the unused 8-argument constructor); ShieldTopology.neighborsOf; ShieldField 3-argument `incoming`; ShieldCellDefense 4-argument `damage`.
- HiveSettings.healer; HiveSlots.lane; EnergyCosts.SHOT.
- RelicRole abilityId and slot; LegacyDroneStackState dead methods.
- RelicSounds attack() and impact() (the events stay registered).
- ShieldShellVisual.triangle; the ShieldVisualRenderer unused overloads; HiveVisualRenderer's 4-argument renderModel; ShieldResponse.at (optional).
- ShieldHexMesh. Its check switches to ShieldTopology, and `build/reports/shield-geometry.json` must stay byte-identical: compare `sha256sum` before and after.

Retire the literals that disappear, with reasons. Update the `.claude/skills/ex-twins-modding/SKILL.md` architecture map, and add a "historical" line at the top of `REFACTOR_PLAN.md`.

**Optional hardening** (revert if any dev run misbehaves):
- `git mv src/main/java/dev/hurtify/relicsaddon/{domain,application}` into `src/core/java/...`;
- `sourceSets { core }` with no dependencies;
- `dependencies { implementation sourceSets.core.output }`;
- `testImplementation sourceSets.core.output` (and add it to the pure-check classpaths);
- the MDG block `mods { "${mod_id}" { sourceSet(sourceSets.main); sourceSet(sourceSets.core) } }`;
- `tasks.named('jar') { from sourceSets.core.output }`;
- verifyReleaseContents also iterates `sourceSets.core.output.classesDirs`;
- ArchitectureCheck gets the core classes directory and `--sources src/core/java/dev/hurtify/relicsaddon`;
- ContractCheck scans the core classes directory too, or every domain and application literal is reported missing;
- keep `sourceSets.core.output` off `releaseProbeRuntimeClasspath`, which extends `implementation`. Otherwise the packaged-jar check sees the core packages both in the jar and on the classpath.

Verify: the gate, runReleaseCheckClient, runStartupCheckClient and the visual gate. With the optional hardening, also check that `./gradlew runClient` reaches the title screen.

## 12. Invariants, preserved quirks and the one deviation

### 12.1 Contracts that must stay identical after every stage

**1. Loading and names**
- `@Mod("relics_addon")` on `dev.hurtify.relicsaddon.RelicsAddon`, whose FQCN never changes.
- The reflective `dev.hurtify.relicsaddon.client.ClientEventRegistrar.register(IEventBus)`.
- The mods.toml yumi entrypoint `dev.hurtify.relicsaddon.client.light.DynamicLightsBridge`.
- The gametest package path: the jar excludes `dev/hurtify/relicsaddon/gametest/**`, `test_room.nbt` and `field_arena.nbt`, and verifyReleaseContents checks it.
- `gametest.ShieldSaveDiagnostic` (inspectShieldSave).
- Every build.gradle mainClass string is edited in the commit that moves its class. ReleaseProbe loads RelicsAddon from S0 on.

**2. Registry ids**
- Items (15): rf_shield, mana_shield, twins_shield, rf_hive, mana_hive, twins_hive, resonant_circuit, energy_cell, mana_cell, rf_shield_core, mana_shield_core, twins_shield_core, rf_drone_frame, mana_drone_shell, twins_drone_plate. The ModItems field names are unchanged.
- Creative tab `relics_addon:main`: shields, then hives, then components.
- Menu `relics_addon:device_control`.
- Payload `relics_addon:open_device`, registrar version "2".
- The 54 sound ids, including the 16 attack and impact events that are never played.
- Data components, in declaration order:

  | Component | Persistent | Synced |
  |---|---|---|
  | hive_settings | yes | yes |
  | hive_support_state | no | yes |
  | hive_combat_state | no | yes |
  | shield_settings | yes | yes |
  | hive_stack_state | yes | yes |
  | instance_id | yes | yes |
  | device_progression | yes | yes |
  | device_energy | yes | yes |
  | shield_stack_state | yes | yes |
  | drone_stack_state | yes | yes |
  | shield_impact | yes | yes |
  | shield_impacts | no | yes |

- Damage types: drone_shot, swarm_strike, swarm_void, swarm_reflect, shield_discharge, shield_mana_burst, shield_twin_surge.
- Tags: damage_type shield_passes, shield_strike and swarm_damage; entity_type shield_interceptable_projectiles; `c:bosses`.
- Curios slot `charm`.

**3. Save formats (NBT)**
- hive_settings: `{healers 0..750, mode id (default barrage)}`, or a bare int.
- hive_stack_state: `{enabled, hp[], ready[]}`, or legacy `{enabled, units[{hp, ready_at?}]}`.
- shield_stack_state:
  - required: enabled, front, left, right, back, lastHitPanel, lastAbsorbed, lastActiveGameTime;
  - optional: sharedBuffer (0), cells (at most 420), moves (at most 3, 0..419), gatherTime (-1);
  - the 4-sector and 42-cell migrations.
- shield_impact: normal (3 doubles), gameTime, panel, absorbed, broken; optional brokenCells (at most 3), distance (-1), strike (0).
- shield_settings: radius (2.0), coverage ("allies").
- device_energy: rf (in FE), mana, rf_on (true), mana_on (true), source ("auto").
- device_progression: experience, level 0..10, points, upgrades (2 bits per upgrade bit).
- drone_stack_state: enabled, lastInterceptGameTime, lastAbsorbed.
- instance_id: a UUID string.

**4. Wire formats**
- hive_stack_state: bool, VarInt count (at most 750, else DecoderException), then per unit a flag byte (hp in bits 0–3; 0x10 ready, 0x20 hit, 0x40 attack) followed by the present VarLongs.
- hive_combat_state: bool, int, long, 3 doubles, byte mode, short travel, ubyte count (at most 100). Each shot: VarInt, long, byte kind, 6 doubles, long. Kinds are 4..10.
- hive_settings: u16 then u8 (more than 750 → IllegalArgumentException).
- hive_support_state: bool then long.
- shield_stack_state: 445 + 4m bytes.
- shield_impact(s), shield_settings and drone_stack_state: NBT through `fromCodec`.
- instance_id: `ByteBufCodecs.STRING_UTF8`.
- device_energy: VarInt, VarInt, a flags byte (ordinal << 2).
- device_progression: 4 normalized VarInts.
- open_device: BOOL, VAR_INT.
- Menu extra data: boolean, then VarInt.

**5. Protocol numbers**
- Console buttons: 0; 1–4; 5; 6; 7+ManaSource ordinal (AUTO, MAGIC, EXPERIENCE); 20+DeviceUpgrade ordinal (DISTRIBUTION, GATHER, RESTORATION, STABILIZATION, COMBAT, SUPPORT, RECOVERY); 40+AttackMode ordinal (DROPLET, BARRAGE, CONTAINMENT). Button return values and UI sounds as today, including Q19.
- Slot layout constants (§10).
- SlotAddress: charm → Curios `charm` index; otherwise the Inventory index.
- Mode ids droplet, barrage, containment. Mana source ids auto, magic, experience. Coverage ids owner, allies, all.
- Stat ids (§4.2). Upgrade ids, bits and levels (§4.2).
- HiveType order RF, MANA, TWINS with its tuning; MAX_DRONES 750, MAX_DEPLOYED 250, DRONE_HP 3.
- RelicRole ids, colours and repair intervals 20/10/30.

**6. Entity NBT keys**
- `relics_addon:held_gravity`.
- `relics_addon:field_absorbed_for` and its per-UUID variant.
- `relics_addon:field_passed_for` + UUID.
- `relics_addon:field_credit_for` + UUID.
- `relics_addon:field_credit_stack` + UUID.

**7. Config**
- `serverconfig/relics_addon-server.toml`, 20 keys:
  - `shield.maxRadius`, `strikeDamage`, `strikeKnockback`, `strikeCooldownTicks`, `passingDamageTypes`, `absorbedDamageTypes`, `interceptedProjectiles`, `ignoredProjectiles`, `keptEffects`;
  - `hive.targetRange`, `pursuitRange`, `strikeEfficiency`, `maxHealingPerSecond`;
  - `power.requireBatteries`, `experiencePointValue`, `experienceReserveLevels`, `playerManaValue`, `playerManaReserve`, `botaniaManaPerPoint`;
  - `progression.maxExperiencePerMinute`.
- `config/relics_addon-client.toml`, 5 keys: `shield.refraction`, `rippleStrength`, `refractionStrength`, `idleOpacity`, `lights.dynamic`.
- Ranges, defaults and comments unchanged. Every read is live, with the same `isLoaded` guard or lack of one.

**8. Text and commands**
- Every lang key in en_us and ru_ru, with the same argument order.
- Commands: `/relics_addon shield_status | shield_radius <2..24> | shield_coverage owner|allies|all`, with no permission level.
- Action-bar messages use overlay = true.

**9. Client resources**
- `item/animated/<itemId>_<part>` models with shell counts 4, 6, 12 or 20.
- RenderTypes `relic_shield`, `relic_shield_glow`, `relic_hive_combat`.
- Shader `relics_addon:shield_refraction`, uniforms RefractionGain and Tint, and the R/A vertex protocol.
- Photon effect and override names; upgrade textures.
- Key `key.relics_addon.device_control`, default H.

**10. Wiring**
- Mod bus steps and the 18 game hooks, in the order and priorities of §8, all with receiveCanceled = false.
- Containment's damage handler (HIGH) runs before the shield's (NORMAL).
- PlayerTick order: power, shield maintenance, barrier, hive.
- The client listener order.

**11. Determinism**
- Vec3d, Trig and Box reproduce Vec3, Mth and AABB exactly. HiveFormation and HiveShapes formulas, constants and hash salts (11/12/13/21/31/32) are unchanged, as are `type.ordinal()`, `IMPACT .7`, `FIRE .8`, `RETURN_TICKS 24` and `STAGGER .45`.
- The ShieldTopology generator is untouched, and it is still built during component registration.
- `CENTER_Y .92`, `RADIUS 2`, `PREVIEW_TICKS 4`, `MOVE_TICKS 10`, `GATHER_COOLDOWN 40`, impact window 36, ripple front `age*π/28`.
- travel = `travelTicks(Entity.distanceTo)`, computed in float.

**12. State and threading**
- JVM-lifetime holders are built once: experience windows, the sound throttle (cleared on ServerStopped), the mana bindings, flights, holds, hit memories and the strike cooldown.
- Weak keys keep Entity.equals semantics.
- `ensureDefaults` touches only the stack and the IdSource (`UUID.randomUUID`).

**13. Tests and tooling**
- The GameTest ids and templates; the log lines `All N required tests passed` and `Packaged release verified: TitleScreen, no preview`.
- Frames `relics-shield-gif-%03d.png` (290) and `relics-hive-gif-%03d.png` (300, 64 ms apart), the still names and the `relics_addon.*` system properties.
- The report files `build/reports/*.json`.

### 12.2 How the contracts are protected

| Protection | Covers |
|---|---|
| verifyCodecGolden | items 3, 4 and 5 |
| verifyContracts | resources and literals: items 2, 6, 7, 8 and 9 |
| verifyGeometryGolden and verifyMathKernel | item 11 |
| verifyWiring | item 10 |
| verifyEntrypoints | item 1 |
| GameTests (44) | behaviour, including the PlayerTick hooks through the bus |
| The visual gate | pixels |

### 12.3 Quirks preserved on purpose (follow-ups after S13, each with a failing test first)

- **Q1.** The hit-immunity tick is stored as a float.
- **Q2.** In the owner loop, a later owner sees an amount already reduced by the immunity window, and can reduce it again.
- **Q3.** The absorb paths ignore the drain result.
- **Q4.** The effect guard calls canAfford, then drain.
- **Q5.** Purchase lacks the Twins-only STABILIZATION check.
- **Q6.** Config guards are inconsistent: unguarded keys throw before the config loads.
- **Q7.** shield_impacts is never cleared, and the client re-adds the latest impact through trackImpacts.
- **Q8.** HiveCombatVisual.STARTED is never cleared on a level change.
- **Q9.** Experience windows live for the whole JVM.
- **Q10.** The barrier stops the whole loop at the first unpaid push.
- **Q11.** The strike cooldown is per mob and shared by all owners.
- **Q12.** Joined containment holds share the ward and the events.
- **Q13.** The mana refill scans every charm slot, including inactive ones.
- **Q14.** ShieldTopology uses Math, not StrictMath.
- **Q15.** The ShieldStackState constructor throws inside decode.
- **Q16.** The sound pitch RNG is drawn before the throttle check.
- **Q17.** Experience keeps accruing at level 10.
- **Q18.** The buffer capacity clamps at 5500 while the buffer maximum is 5000.
- **Q19.** Selecting the attack mode that is already set still returns true and plays the toggle sound: configureMode does not compare modes.
- **Q20.** When no shield is active, the shield commands fall back to the first shield in any charm slot, inactive slots included, then the main hand, then the off hand.

### 12.4 The one permitted deviation

**D1.** `DeviceQueries.hiveCapacity` returns 0 when the addressed stack is not a hive. DeviceControlScreen therefore no longer throws ClassCastException (or IllegalArgumentException for a shield) when a hive leaves its slot while the swarm tab is open. Its numbers are otherwise identical. `ManageSwarm.capacity` keeps throwing for a non-hive.

## 13. Risks and mitigations

1. **Side-effect order drifting while handlers become services.** Mitigations:
   - line-by-line transposition (R-3) and the §6.1 notes;
   - sub-commits with the gate after each, each of which compiles (R-5);
   - the 44 GameTests (including S1), ApplicationRulesCheck, and a side-by-side review.
2. **Bit-exactness of the vector swap and of the rules.** Mitigations:
   - MathKernelCheck (differential, in the same JVM);
   - GeometryGoldenCheck recorded before any move;
   - DomainRulesCheck values computed with the code's association and ceil variant;
   - the visual gate.
3. **Save and sync corruption.** Mitigations: verbatim codec moves and byte and NBT goldens with legacy and malformed inputs. A mismatch in the field would show up as "Failed to decode packet".
4. **Listener order and priority.** Mitigations: wiring as data, verifyWiring, hooks rebound in place, and the bus-driven upkeep GameTest.
5. **Class-initialisation timing.** Mitigations: the static touch in ShieldCodecs, lambda-only MOD_STEPS, and R-9 lazy adapters.
6. **Static-to-instance lifetime and threading.** Mitigations: one graph per JVM, server state only on the server thread, and client queries that are stateless.
7. **GameTests are both the net and code to edit.** Mitigations: facades until S12, named edits only, and a pinned exact count.
8. **Coverage gaps.** Client data acquisition in a live world, dynamic lights, multiplayer overlaps and save/reload of lifted mobs are not automated. Mitigation: the optional manual checklists in S8, S10 and S11 (ask the user).
9. **Performance.** Views allocate one wrapper per call, and SwarmMath converts vectors per drone. Accepted: this is small next to the existing allocations. Allocation-free formation frames are future work.
10. **Tool and environment.**
    - No Python (only the Store stub): the frame tool is Node.
    - Fresh run directories need options.txt.
    - Capture runs are slow (up to 10 min each).
    - Wall-clock item animation in the galleries: masks come from three runs, dilated.
    - The working tree uses CRLF: resource hashes are line-ending-normalised.
    - validate_hive_assets.py is stale and not a gate.
11. **The optional core source set** touches MDG runs, the jar, ContractCheck's class roots and the release-probe classpath. It is done last and reverted on any misbehaviour.