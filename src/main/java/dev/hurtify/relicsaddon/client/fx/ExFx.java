package dev.hurtify.relicsaddon.client.fx;

import com.lowdragmc.photon.client.fx.FX;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.lowdragmc.photon.client.fx.FXRuntime;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.relic.RelicRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Client-only Photon accents for shields and hives. Every entry point is fire-and-forget, safe to call
 * each frame, and never throws. Effects are built in code, unless {@code assets/relics_addon/fx/<name>.fx}
 * exists, in which case the artist's file wins (names are listed on each method).
 */
public final class ExFx {
    private static final int MAX_LIVE = 128;
    private static final double CULL_DISTANCE_SQR = 64 * 64, LOD_DISTANCE_SQR = 24 * 24;
    private static final float LOD_DENSITY = .5f;
    private static final List<PointEffectExecutor> LIVE = new ArrayList<>();
    /** Artist overrides by name; empty = no file, so the resource manager is asked once per name, not per call. */
    private static final Map<String, Optional<FX>> AUTHORED = new HashMap<>();
    /** Code-built FX by name + palette; empty = the build failed, which is not retried every call. */
    private static final Map<String, Optional<FX>> BUILT = new HashMap<>();
    private static boolean warned;

    /** {@code shield_absorb_<rf|mana|twins>}: tangential sparks along the surface plus a flash. */
    public static void shieldAbsorb(Level level, Vec3 point, Vec3 outwardNormal, RelicRole role, float strength) {
        try {
            float power = Mth.clamp(Float.isFinite(strength) ? strength : 1, .2f, 2);
            play(level, point, point, 0, "shield_absorb_" + family(role), role.color(), ExFxLibrary::shieldAbsorb,
                    executor -> executor.orient(outwardNormal).onStarted(runtime -> {
                        ExFxLibrary.each(runtime, "sparks", emitter -> ExFxLibrary.scaleCount(emitter, power));
                        ExFxLibrary.each(runtime, "flash", emitter -> ExFxLibrary.size(emitter, .35f + .3f * power));
                    }));
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** {@code shield_cell_break_<family>}: shards thrown outward under gravity, a flash and lingering dust. */
    public static void shieldCellBreak(Level level, Vec3 point, Vec3 outwardNormal, RelicRole role) {
        try {
            play(level, point, point, 0, "shield_cell_break_" + family(role), role.color(), ExFxLibrary::shieldCellBreak,
                    executor -> executor.orient(outwardNormal));
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** {@code shield_collapse_<family>}: an expanding shell, equatorial ring and nova, with dust drawn inward. */
    public static void shieldCollapse(Level level, Vec3 center, double radius, RelicRole role) {
        try {
            float r = (float) Mth.clamp(Double.isFinite(radius) ? radius : 2, .5, 16);
            float count = Mth.clamp(r / 2.5f, .6f, 2);
            play(level, center, center, 0, "shield_collapse_" + family(role), role.color(), ExFxLibrary::shieldCollapse,
                    executor -> executor.onStarted(runtime -> {
                        for (String name : new String[]{"shell", "ring", "dust"}) {
                            ExFxLibrary.each(runtime, name, emitter -> {
                                ExFxLibrary.shapeScale(emitter, r, r, r);
                                ExFxLibrary.scaleCount(emitter, count);
                            });
                        }
                        // Dust starts on the shell and travels ~60-90% of the radius over its ~20 tick life.
                        ExFxLibrary.each(runtime, "dust", emitter -> ExFxLibrary.speed(emitter, -r * .6f, -r * .9f));
                        ExFxLibrary.each(runtime, "flash", emitter -> ExFxLibrary.size(emitter, r * .8f));
                        ExFxLibrary.each(runtime, "nova", emitter -> ExFxLibrary.size(emitter, r * 1.1f));
                    }));
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /**
     * kind 0 {@code hive_beam_<type>}, 1/3 {@code hive_bolt_<type>}, 2 {@code hive_lightning_<type>}.
     * Bolts travel from→to over {@code travelTicks}; beams and lightning are instantaneous.
     */
    public static void hiveShot(Level level, Vec3 from, Vec3 to, HiveType type, int kind, int travelTicks) {
        try {
            String suffix = type.name().toLowerCase(Locale.ROOT);
            int color = type.role.color();
            Vec3 path = to.subtract(from);
            float length = (float) path.length();
            if (!(length > 1e-3f) || length > 128) return;
            switch (kind) {
                case 0 -> play(level, from.lerp(to, .5), to, 0, "hive_beam_" + suffix, color, ExFxLibrary::hiveBeam,
                        executor -> executor.orient(path).onStarted(runtime -> {
                            float count = Mth.clamp(length / 12, .15f, 1.5f);
                            ExFxLibrary.each(runtime, "beam", emitter -> {
                                ExFxLibrary.shapeScale(emitter, .08f, length, .08f);
                                ExFxLibrary.scaleCount(emitter, count);
                            });
                            ExFxLibrary.each(runtime, "core", emitter -> {
                                ExFxLibrary.shapeScale(emitter, .015f, length, .015f);
                                ExFxLibrary.scaleCount(emitter, count);
                            });
                        }));
                case 1, 3 -> {
                    int travel = Math.max(1, travelTicks);
                    play(level, from, to, travel, "hive_bolt_" + suffix, color, ExFxLibrary::hiveBolt,
                            executor -> executor.onStarted(runtime -> {
                                ExFxLibrary.each(runtime, "head", emitter -> {
                                    ExFxLibrary.lifetime(emitter, travel);
                                    ExFxLibrary.trailFraction(emitter, Math.min(1, 5f / travel));
                                });
                                ExFxLibrary.each(runtime, "halo", emitter -> ExFxLibrary.lifetime(emitter, travel));
                                ExFxLibrary.each(runtime, "sparkle", emitter -> emitter.runtime().duration.set(travel));
                            }));
                }
                case 2 -> play(level, from, to, 0, "hive_lightning_" + suffix, color, ExFxLibrary::hiveLightning,
                        executor -> executor.onStarted(runtime -> placeLightning(runtime, level, path, length)));
                default -> { }
            }
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** {@code hive_impact_bolt_<type>} for bolts (kind 1/3), {@code hive_impact_zap_<type>} for beams and lightning. */
    public static void hiveImpact(Level level, Vec3 point, HiveType type, int kind) {
        try {
            boolean bolt = kind == 1 || kind == 3;
            String name = "hive_impact_" + (bolt ? "bolt_" : "zap_") + type.name().toLowerCase(Locale.ROOT);
            play(level, point, point, 0, name, type.role.color(), color -> ExFxLibrary.hiveImpact(color, bolt), executor -> { });
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** {@code hive_summon_<type>} (inward spiral, follows the owner) or {@code hive_dismiss_<type>} (outward scatter). */
    public static void hiveSummon(Level level, Entity owner, HiveType type, boolean enabled) {
        try {
            if (owner == null || !owner.isAlive()) return;
            String suffix = type.name().toLowerCase(Locale.ROOT);
            Vec3 center = owner.position().add(0, owner.getBbHeight() * .5, 0);
            play(level, center, center, 0, (enabled ? "hive_summon_" : "hive_dismiss_") + suffix, type.role.color(),
                    enabled ? ExFxLibrary::hiveSummon : ExFxLibrary::hiveDismiss, executor -> executor.follow(owner));
        } catch (RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    /** Drops cached definitions; hook to resource reloads so edited {@code .fx} overrides are picked up. */
    public static void clearCaches() {
        AUTHORED.clear();
        BUILT.clear();
    }

    /** Wire on the mod bus: {@code modEventBus.addListener(ExFx::registerReloadListener)}. */
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> clearCaches());
    }

    /** Live runtimes started here and not yet finished; exposed for debugging overlays. */
    public static int liveCount() {
        LIVE.removeIf(PointEffectExecutor::isDone);
        return LIVE.size();
    }

    private static void play(Level level, Vec3 from, Vec3 to, int travelTicks, String name, int color,
            IntFunction<FX> builder, Consumer<PointEffectExecutor> setup) {
        if (level == null || !level.isClientSide() || from == null || to == null) return;
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level) return;
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double distance = Math.min(camera.distanceToSqr(from), camera.distanceToSqr(to));
        if (distance > CULL_DISTANCE_SQR) return;
        if (liveCount() >= MAX_LIVE) return;
        FX fx = resolve(name, color, builder);
        if (fx == null) return;
        var executor = new PointEffectExecutor(fx, level, from, to, travelTicks);
        setup.accept(executor);
        if (distance > LOD_DISTANCE_SQR) executor.density(LOD_DENSITY);
        executor.start();
        if (executor.getRuntime() != null) LIVE.add(executor);
    }

    @Nullable
    private static FX resolve(String name, int color, IntFunction<FX> builder) {
        var authored = AUTHORED.computeIfAbsent(name, ExFx::loadAuthored);
        if (authored.isPresent()) return authored.get();
        return BUILT.computeIfAbsent(name + '#' + Integer.toHexString(color), key -> build(key, color, builder)).orElse(null);
    }

    private static Optional<FX> loadAuthored(String name) {
        var file = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, FXHelper.FX_PATH + name + FX.SUFFIX);
        // FXHelper logs a stack trace for every missing file, so probe the resource manager first.
        if (Minecraft.getInstance().getResourceManager().getResource(file).isEmpty()) return Optional.empty();
        return Optional.ofNullable(FXHelper.getFX(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, name)));
    }

    private static Optional<FX> build(String key, int color, IntFunction<FX> builder) {
        try {
            return Optional.of(builder.apply(color));
        } catch (RuntimeException | LinkageError error) {
            RelicsAddon.LOGGER.warn("Failed to build Photon effect {}", key, error);
            return Optional.empty();
        }
    }

    /** Jagged polyline: interior points jitter perpendicular to the shot, each segment emitter spans one leg. */
    private static void placeLightning(FXRuntime runtime, Level level, Vec3 path, float length) {
        int segments = ExFxLibrary.LIGHTNING_SEGMENTS;
        Vec3 axis = path.normalize();
        Vec3 side = axis.cross(Math.abs(axis.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 lift = axis.cross(side);
        double jitter = Math.min(.6, length * .08);
        var random = level.getRandom();
        Vec3[] points = new Vec3[segments + 1];
        points[0] = Vec3.ZERO;
        points[segments] = path;
        for (int index = 1; index < segments; index++) {
            double angle = random.nextDouble() * Math.PI * 2, offset = jitter * (.4 + .6 * random.nextDouble());
            points[index] = path.scale(index / (double) segments)
                    .add(side.scale(Math.cos(angle) * offset)).add(lift.scale(Math.sin(angle) * offset));
        }
        for (int index = 0; index < segments; index++) {
            Vec3 start = points[index], leg = points[index + 1].subtract(start);
            float legLength = (float) leg.length();
            Vec3 middle = start.add(leg.scale(.5));
            ExFxLibrary.each(runtime, "seg" + index, emitter -> {
                emitter.transform().localPosition(middle.toVector3f());
                emitter.transform().localRotation(PointEffectExecutor.alignY(leg));
                ExFxLibrary.shapeScale(emitter, .03f, legLength, .03f);
                ExFxLibrary.scaleCount(emitter, Mth.clamp(legLength / 3, .2f, 1.5f));
            });
        }
    }

    private static String family(RelicRole role) {
        String name = role.name().toLowerCase(Locale.ROOT);
        int split = name.indexOf('_');
        return split < 0 ? name : name.substring(0, split);
    }

    private static void fail(Throwable error) {
        if (warned) return;
        warned = true;
        RelicsAddon.LOGGER.error("Photon effect failed; further effect errors are suppressed", error);
    }

    private ExFx() { }
}
