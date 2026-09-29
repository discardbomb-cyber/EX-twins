package dev.hurtify.relicsaddon.sound;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.relic.RelicRole;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.phys.Vec3;

/** Server-side sound catalogue and rate-limited playback for Relics combat effects. */
public final class RelicSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, RelicsAddon.MOD_ID);

    private static final DeferredHolder<SoundEvent, SoundEvent> RF_ATTACK_1 = sound("rf.attack_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_ATTACK_2 = sound("rf.attack_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_IMPACT_1 = sound("rf.impact_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_IMPACT_2 = sound("rf.impact_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_ATTACK_1 = sound("mana.attack_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_ATTACK_2 = sound("mana.attack_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_IMPACT_1 = sound("mana.impact_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_IMPACT_2 = sound("mana.impact_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_LIGHTNING_ATTACK_1 = sound("twins.lightning_attack_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_LIGHTNING_ATTACK_2 = sound("twins.lightning_attack_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_BOLT_ATTACK_1 = sound("twins.bolt_attack_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_BOLT_ATTACK_2 = sound("twins.bolt_attack_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_LIGHTNING_IMPACT_1 = sound("twins.lightning_impact_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_LIGHTNING_IMPACT_2 = sound("twins.lightning_impact_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_BOLT_IMPACT_1 = sound("twins.bolt_impact_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_BOLT_IMPACT_2 = sound("twins.bolt_impact_2");

    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SUMMON = sound("rf.summon");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_DISMISS = sound("rf.dismiss");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SUMMON = sound("mana.summon");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_DISMISS = sound("mana.dismiss");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SUMMON = sound("twins.summon");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_DISMISS = sound("twins.dismiss");

    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SHIELD_ABSORB_1 = sound("shield.rf_absorb_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SHIELD_ABSORB_2 = sound("shield.rf_absorb_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SHIELD_CELL_BREAK = sound("shield.rf_cell_break");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SHIELD_COLLAPSE = sound("shield.rf_collapse");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SHIELD_ABSORB_1 = sound("shield.mana_absorb_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SHIELD_ABSORB_2 = sound("shield.mana_absorb_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SHIELD_CELL_BREAK = sound("shield.mana_cell_break");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SHIELD_COLLAPSE = sound("shield.mana_collapse");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SHIELD_ABSORB_1 = sound("shield.twins_absorb_1");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SHIELD_ABSORB_2 = sound("shield.twins_absorb_2");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SHIELD_CELL_BREAK = sound("shield.twins_cell_break");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SHIELD_COLLAPSE = sound("shield.twins_collapse");

    private static final DeferredHolder<SoundEvent, SoundEvent> UI_TOGGLE = sound("ui.toggle");
    private static final DeferredHolder<SoundEvent, SoundEvent> UI_UPGRADE = sound("ui.upgrade");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SHIELD_RIPPLE = sound("shield.mana_ripple");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SHIELD_RIPPLE = sound("shield.twins_ripple");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SHIELD_STRIKE = sound("shield.rf_strike");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_SHIELD_STRIKE = sound("shield.mana_strike");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_SHIELD_STRIKE = sound("shield.twins_strike");

    private static final int MAX_THROTTLE_ENTRIES = 2_048;
    private static final long STALE_TICKS = 1_200L;
    /** Weak keys ensure a closed integrated-server level cannot be retained by sound state. */
    private static final Map<ServerLevel, Map<SpatialKey, Long>> LAST_PLAYED = new WeakHashMap<>();

    private RelicSounds() {
    }

    /** Call from the mod constructor: {@code RelicSounds.register(modEventBus)}. */
    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(RelicSounds::onServerStopped);
    }

    /** Hive combat uses kind 2 for Twins lightning and kind 3 for its violet mana bolt. */
    public static void attack(ServerLevel level, Vec3 position, HiveType type, int kind) {
        play(level, position, attackEvent(level, position, type, kind), Category.ATTACK, 5, 0.58F, 0.96F);
    }

    /** kind mirrors {@link #attack(ServerLevel, Vec3, HiveType, int)} for impact families. */
    public static void impact(ServerLevel level, Vec3 position, HiveType type, int kind) {
        play(level, position, impactEvent(level, position, type, kind), Category.IMPACT, 4, 0.64F, 0.98F);
    }

    public static void summon(ServerLevel level, Vec3 position, HiveType type, boolean enabled) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> enabled ? RF_SUMMON : RF_DISMISS;
            case MANA -> enabled ? MANA_SUMMON : MANA_DISMISS;
            case TWINS -> enabled ? TWINS_SUMMON : TWINS_DISMISS;
        };
        play(level, position, event, Category.SUMMON, 12, 0.72F, 1.0F);
    }

    /** Exhaustion has priority over a cell break so one hit never produces a noisy stack. */
    public static void shield(ServerLevel level, Vec3 position, RelicRole role, boolean broken, boolean exhausted) {
        if (!role.isShield()) {
            return;
        }
        DeferredHolder<SoundEvent, SoundEvent> event = shieldEvent(level, position, role, broken, exhausted);
        int cooldown = exhausted ? 20 : broken ? 8 : 4;
        float volume = exhausted ? 0.88F : broken ? 0.72F : 0.50F;
        play(level, position, event, Category.SHIELD, cooldown, volume, exhausted ? 0.82F : 1.0F);
        if (!exhausted) ripple(level, position, role);
    }

    /** The shell striking a hostile mob and throwing it back. */
    public static void strike(ServerLevel level, Vec3 position, RelicRole role) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (role) {
            case RF_SHIELD -> RF_SHIELD_STRIKE;
            case MANA_SHIELD -> MANA_SHIELD_STRIKE;
            case TWINS_SHIELD -> TWINS_SHIELD_STRIKE;
            default -> null;
        };
        if (event != null) play(level, position, event, Category.STRIKE, 4, .66F, .94F + (float) level.getRandom().nextGaussian() * .04F);
    }

    /** Console feedback is private to the player using the device menu. */
    public static void ui(net.minecraft.world.entity.player.Player player, Ui sound) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) return;
        DeferredHolder<SoundEvent, SoundEvent> event = switch (sound) {
            case TOGGLE -> UI_TOGGLE;
            case UPGRADE -> UI_UPGRADE;
        };
        serverPlayer.playNotifySound(event.get(), SoundSource.PLAYERS, .7F, 1F);
    }

    /** Distortion wave that follows an absorbed hit on the Mana and Twins shells. */
    public static void ripple(ServerLevel level, Vec3 position, RelicRole role) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (role) {
            case MANA_SHIELD -> MANA_SHIELD_RIPPLE;
            case TWINS_SHIELD -> TWINS_SHIELD_RIPPLE;
            default -> null;
        };
        if (event != null) play(level, position, event, Category.RIPPLE, 10, .42F, 1F);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> attackEvent(ServerLevel level, Vec3 position, HiveType type, int kind) {
        boolean second = variant(level, position, type.ordinal() * 5 + kind);
        return switch (type) {
            case RF -> second ? RF_ATTACK_2 : RF_ATTACK_1;
            case MANA -> second ? MANA_ATTACK_2 : MANA_ATTACK_1;
            case TWINS -> kind == 2 ? (second ? TWINS_LIGHTNING_ATTACK_2 : TWINS_LIGHTNING_ATTACK_1)
                    : (second ? TWINS_BOLT_ATTACK_2 : TWINS_BOLT_ATTACK_1);
        };
    }

    private static DeferredHolder<SoundEvent, SoundEvent> impactEvent(ServerLevel level, Vec3 position, HiveType type, int kind) {
        boolean second = variant(level, position, 23 + type.ordinal() * 5 + kind);
        return switch (type) {
            case RF -> second ? RF_IMPACT_2 : RF_IMPACT_1;
            case MANA -> second ? MANA_IMPACT_2 : MANA_IMPACT_1;
            case TWINS -> kind == 2 ? (second ? TWINS_LIGHTNING_IMPACT_2 : TWINS_LIGHTNING_IMPACT_1)
                    : (second ? TWINS_BOLT_IMPACT_2 : TWINS_BOLT_IMPACT_1);
        };
    }

    private static DeferredHolder<SoundEvent, SoundEvent> shieldEvent(
            ServerLevel level, Vec3 position, RelicRole role, boolean broken, boolean exhausted) {
        return switch (role) {
            case RF_SHIELD -> exhausted ? RF_SHIELD_COLLAPSE : broken ? RF_SHIELD_CELL_BREAK
                    : variant(level, position, 71) ? RF_SHIELD_ABSORB_2 : RF_SHIELD_ABSORB_1;
            case MANA_SHIELD -> exhausted ? MANA_SHIELD_COLLAPSE : broken ? MANA_SHIELD_CELL_BREAK
                    : variant(level, position, 73) ? MANA_SHIELD_ABSORB_2 : MANA_SHIELD_ABSORB_1;
            case TWINS_SHIELD -> exhausted ? TWINS_SHIELD_COLLAPSE : broken ? TWINS_SHIELD_CELL_BREAK
                    : variant(level, position, 79) ? TWINS_SHIELD_ABSORB_2 : TWINS_SHIELD_ABSORB_1;
            default -> throw new IllegalArgumentException("Not a shield: " + role);
        };
    }

    private static void play(ServerLevel level, Vec3 position, DeferredHolder<SoundEvent, SoundEvent> event,
            Category category, int cooldownTicks, float volume, float pitch) {
        if (!allow(level, position, category, cooldownTicks)) {
            return;
        }
        level.playSound(null, position.x, position.y, position.z, event.get(), SoundSource.PLAYERS, volume, pitch);
    }

    private static boolean variant(ServerLevel level, Vec3 position, int salt) {
        long grid = ((long) Math.floor(position.x) * 734_287L) ^ ((long) Math.floor(position.z) * 912_271L);
        return ((level.getGameTime() / 7L + grid + salt) & 1L) != 0L;
    }

    private static boolean allow(ServerLevel level, Vec3 position, Category category, int cooldownTicks) {
        long now = level.getGameTime();
        Map<SpatialKey, Long> byLocation = LAST_PLAYED.get(level);
        SpatialKey key = new SpatialKey((int) Math.floor(position.x / 3.0),
                (int) Math.floor(position.y / 3.0), (int) Math.floor(position.z / 3.0), category);
        Long previous = byLocation == null ? null : byLocation.get(key);
        // Integrated worlds may restart at tick zero. A backwards clock resets this key.
        if (previous != null && now >= previous && now - previous < cooldownTicks) {
            return false;
        }
        if (previous == null && totalEntries() >= MAX_THROTTLE_ENTRIES) {
            prune(now);
            if (totalEntries() >= MAX_THROTTLE_ENTRIES) {
                return false;
            }
        }
        if (byLocation == null) {
            byLocation = LAST_PLAYED.computeIfAbsent(level, ignored -> new HashMap<>());
        }
        byLocation.put(key, now);
        return true;
    }

    private static int totalEntries() {
        return LAST_PLAYED.values().stream().mapToInt(Map::size).sum();
    }

    private static void prune(long now) {
        Iterator<Map.Entry<ServerLevel, Map<SpatialKey, Long>>> levels = LAST_PLAYED.entrySet().iterator();
        while (levels.hasNext()) {
            Map<SpatialKey, Long> values = levels.next().getValue();
            values.entrySet().removeIf(entry -> now < entry.getValue() || now - entry.getValue() > STALE_TICKS);
            if (values.isEmpty()) {
                levels.remove();
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        LAST_PLAYED.clear();
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String id) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, id);
        return SOUNDS.register(id, () -> SoundEvent.createVariableRangeEvent(location));
    }

    private enum Category {
        ATTACK,
        IMPACT,
        SUMMON,
        SHIELD,
        RIPPLE,
        STRIKE
    }

    public enum Ui {
        TOGGLE,
        UPGRADE
    }

    private record SpatialKey(int x, int y, int z, Category category) {
    }
}
