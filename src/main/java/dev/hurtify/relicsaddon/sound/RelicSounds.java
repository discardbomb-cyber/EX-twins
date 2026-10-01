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
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_TESSERACT = sound("hive.rf_tesseract");
    // The attacks' layers (see tools/build_combat_sounds.mjs): wind-ups, blows, shots bursting, constructs
    // locking and coming apart, a caught shot grounded, the loops the client plays, and the far boom.
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_RAM_WINDUP = sound("hive.rf_ram_windup");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_RAM_IMPACT = sound("hive.rf_ram_impact");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_DROP_FALL = sound("hive.mana_drop_fall");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_DROP_SPLASH = sound("hive.mana_drop_splash");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_RIFT_OPEN = sound("hive.twins_rift_open");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_RING_BURST = sound("hive.rf_ring_burst");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_GLASS_SHATTER = sound("hive.twins_glass_shatter");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_CAGE_LOCK = sound("hive.rf_cage_lock");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_LOTUS_LOCK = sound("hive.mana_lotus_lock");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_RIFT_LOCK = sound("hive.twins_rift_lock");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_RELEASE = sound("hive.rf_release");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_RELEASE = sound("hive.mana_release");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_RELEASE = sound("hive.twins_release");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_GROUND_BOLT = sound("hive.rf_ground_bolt");
    public static final DeferredHolder<SoundEvent, SoundEvent> RF_CAGE_HUM = sound("hive.rf_cage_hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> MANA_LOTUS_HUM = sound("hive.mana_lotus_hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> TWINS_RIFT_HUM = sound("hive.twins_rift_hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> RF_FLIGHT = sound("hive.rf_flight");
    public static final DeferredHolder<SoundEvent, SoundEvent> MANA_FLIGHT = sound("hive.mana_flight");
    public static final DeferredHolder<SoundEvent, SoundEvent> TWINS_FLIGHT = sound("hive.twins_flight");
    private static final DeferredHolder<SoundEvent, SoundEvent> FAR_BOOM = sound("hive.far_boom");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_DROPLET = sound("hive.mana_droplet");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_PULSAR = sound("hive.twins_pulsar");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_CHARGE_FIRE = sound("hive.rf_charge_fire");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_CHARGE_FIRE = sound("hive.mana_charge_fire");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_CHARGE_FIRE = sound("hive.twins_charge_fire");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_BLAST = sound("hive.rf_lightning_blast");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_BLAST = sound("hive.mana_lightning_blast");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_BLAST = sound("hive.twins_lightning_blast");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_SEAL = sound("hive.rf_seal");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_WARD = sound("hive.mana_ward");
    private static final DeferredHolder<SoundEvent, SoundEvent> TWINS_RIFT = sound("hive.twins_rift");
    private static final DeferredHolder<SoundEvent, SoundEvent> WARD_REFLECT = sound("hive.ward_reflect");
    private static final DeferredHolder<SoundEvent, SoundEvent> ARMAGEDDON_CHARGE = sound("hive.armageddon_charge");
    private static final DeferredHolder<SoundEvent, SoundEvent> ARMAGEDDON_FIRE = sound("hive.armageddon_fire");
    private static final DeferredHolder<SoundEvent, SoundEvent> ARMAGEDDON_DEVOUR = sound("hive.armageddon_devour");
    /** Each client plays the blast itself, at full strength wherever it stands (see {@code ArmageddonVisual}). */
    public static final DeferredHolder<SoundEvent, SoundEvent> ARMAGEDDON_BLAST = sound("hive.armageddon_blast");
    /** Played by each client as the blast's shock wave reaches it. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ARMAGEDDON_SHOCK = sound("hive.armageddon_shock");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_ARMAGEDDON_CHARGE = sound("hive.mana_armageddon_charge");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_ARMAGEDDON_FIRE = sound("hive.mana_armageddon_fire");
    private static final DeferredHolder<SoundEvent, SoundEvent> MANA_ARMAGEDDON_COLLISION = sound("hive.mana_armageddon_collision");
    /**
     * Each client plays the rest of Mana Armageddon itself, heard wherever it stands (see {@code ManaArmageddonVisual}):
     * the sphere of runes cracking round the sun, the blast (exactly as long as its column of light grows), and the
     * dome of light passing over it.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> MANA_ARMAGEDDON_SPHERE = sound("hive.mana_armageddon_sphere");
    public static final DeferredHolder<SoundEvent, SoundEvent> MANA_ARMAGEDDON_BLAST = sound("hive.mana_armageddon_blast");
    public static final DeferredHolder<SoundEvent, SoundEvent> MANA_ARMAGEDDON_SHOCK = sound("hive.mana_armageddon_shock");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_ARMAGEDDON_CHARGE = sound("hive.rf_armageddon_charge");
    private static final DeferredHolder<SoundEvent, SoundEvent> RF_ARMAGEDDON_FIRE = sound("hive.rf_armageddon_fire");
    /**
     * Each client plays the rest of RF Armageddon itself, heard wherever it stands (see {@code RfArmageddonVisual}): the
     * ball's heavy flight and the bolts it strikes the ground with (following the ball), the dome of glass heating, and
     * the atomic blast, exactly as long as its light takes to fade.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> RF_ARMAGEDDON_FLIGHT = sound("hive.rf_armageddon_flight");
    public static final DeferredHolder<SoundEvent, SoundEvent> RF_ARMAGEDDON_DOME = sound("hive.rf_armageddon_dome");
    public static final DeferredHolder<SoundEvent, SoundEvent> RF_ARMAGEDDON_BLAST = sound("hive.rf_armageddon_blast");

    private static final int MAX_THROTTLE_ENTRIES = 2_048;
    private static final long STALE_TICKS = 1_200L;
    /** Weak keys ensure a closed integrated-server level cannot be retained by sound state. */
    private static final Map<ServerLevel, Map<SpatialKey, Long>> LAST_PLAYED = new WeakHashMap<>();

    private RelicSounds() {
    }

    /** Call from the mod constructor: {@code RelicSounds.register(modEventBus)}. */
    /** The ship hives' sounds; the lance's beam is a loop a client plays for as long as the beam burns. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SHIP_LANCE_BEAM = sound("ship.lance_beam");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_LANCE_IGNITE = sound("ship.lance_ignite");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_LANCE_OVERHEAT = sound("ship.lance_overheat");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_AEGIS_BLOCK = sound("ship.aegis_block");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_AEGIS_BREAK = sound("ship.aegis_break");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_AEGIS_RAISE = sound("ship.aegis_raise");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_ESCORT_ARC = sound("ship.escort_arc");
    private static final DeferredHolder<SoundEvent, SoundEvent> SHIP_ESCORT_LAUNCH = sound("ship.escort_launch");

    public enum Ship { LANCE_IGNITE, LANCE_OVERHEAT, AEGIS_BLOCK, AEGIS_BREAK, AEGIS_RAISE, ESCORT_ARC, ESCORT_LAUNCH }

    /** A ship hive's sound at a point of the world (where the ship really is, not its plot). */
    public static void ship(ServerLevel level, Vec3 at, Ship sound, float volume, float pitch) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (sound) {
            case LANCE_IGNITE -> SHIP_LANCE_IGNITE;
            case LANCE_OVERHEAT -> SHIP_LANCE_OVERHEAT;
            case AEGIS_BLOCK -> SHIP_AEGIS_BLOCK;
            case AEGIS_BREAK -> SHIP_AEGIS_BREAK;
            case AEGIS_RAISE -> SHIP_AEGIS_RAISE;
            case ESCORT_ARC -> SHIP_ESCORT_ARC;
            case ESCORT_LAUNCH -> SHIP_ESCORT_LAUNCH;
        };
        level.playSound(null, at.x, at.y, at.z, event.get(), SoundSource.BLOCKS, volume, pitch);
    }

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

    /** A strike group's single blow (tesseract, droplet or pulsar), followed by its lightning blast. */
    public static void swarmStrike(ServerLevel level, Vec3 position, HiveType type) {
        swarmStrike(level, position, type, 0);
    }

    /** Semitones of a major pentatonic scale: the strike groups' blows, one after another, play it as an arpeggio. */
    private static final int[] PENTATONIC = {0, 2, 4, 7, 9, 12, 14, 16};

    /** The pitch of strike group {@code group}'s blow: its step on the pentatonic scale, round {@code base}. */
    public static float groupPitch(int group, float base) {
        return base * (float) Math.pow(2, PENTATONIC[Math.floorMod(group, PENTATONIC.length)] / 12.0);
    }

    /**
     * Strike group {@code group}'s blow: the RF tesseract ramming home in metal, the Mana drop splashing, the
     * Twins pulsar, each on its own step of the scale; then the lightning blast. Listeners far off hear a dull
     * boom instead of the crack.
     */
    public static void swarmStrike(ServerLevel level, Vec3 position, HiveType type, int group) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_RAM_IMPACT;
            case MANA -> MANA_DROP_SPLASH;
            case TWINS -> TWINS_PULSAR;
        };
        if (!allow(level, position, Category.SWARM, 3)) return;
        nearAndFar(level, position, event, .9F, groupPitch(group, type == HiveType.TWINS ? .8F : .75F));
        swarmExplosion(level, position, type);
    }

    /** A strike group sets off: the RF tesseract closing into a cube, a Mana drop's falling whistle, a Twins rift tearing open. */
    public static void launch(ServerLevel level, Vec3 position, HiveType type, int group) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_RAM_WINDUP;
            case MANA -> MANA_DROP_FALL;
            case TWINS -> TWINS_RIFT_OPEN;
        };
        play(level, position, event, Category.CHARGE, 2, .6F, groupPitch(group, .85F));
    }

    /** A barrage shot breaks on its target: the RF ring bursts, the Twins icosahedron shatters, then the blast; Mana's is just the blast, as before. */
    public static void chargeLanded(ServerLevel level, Vec3 position, HiveType type) {
        if (type == HiveType.RF) play(level, position, RF_RING_BURST, Category.IMPACT, 2, .8F, .95F + (float) level.getRandom().nextGaussian() * .05F);
        if (type == HiveType.TWINS) play(level, position, TWINS_GLASS_SHATTER, Category.IMPACT, 2, .8F, 1F + (float) level.getRandom().nextGaussian() * .05F);
        swarmExplosion(level, position, type);
    }

    /** A construct locks shut round what it holds: the cage's clunk, the lotus's chord, the rift cracking open. */
    public static void constructLock(ServerLevel level, Vec3 position, HiveType type) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_CAGE_LOCK;
            case MANA -> MANA_LOTUS_LOCK;
            case TWINS -> TWINS_RIFT_LOCK;
        };
        play(level, position, event, Category.CONTAIN, 4, .9F, 1F);
    }

    /** A construct comes apart into its drones. */
    public static void constructRelease(ServerLevel level, Vec3 position, HiveType type) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_RELEASE;
            case MANA -> MANA_RELEASE;
            case TWINS -> TWINS_RELEASE;
        };
        play(level, position, event, Category.CONTAIN, 2, .8F, 1F);
    }

    /** A shot the RF cage caught runs off it into the ground. */
    public static void groundBolt(ServerLevel level, Vec3 position) {
        play(level, position, RF_GROUND_BOLT, Category.IMPACT, 3, .75F, .95F + (float) level.getRandom().nextGaussian() * .05F);
    }

    /** Within this many blocks a blow is heard close up; up to {@link #FAR} blocks off, only its boom carries. */
    private static final double NEAR = 24, FAR = 120;

    /**
     * Plays {@code near} to every player close by and the far boom to those further off: in the open air the
     * high crack dies away long before the low roll, and a sound played the same for all cannot do that.
     */
    /** The moments of the Noctis weapons that are heard. */
    public enum Spear { THROW, ANCHOR, STICK, STRIKE, RETURN, PULL, COLLAPSE, CUT, BEAM_CHARGE, BEAM_FIRE, HALO, SCYTHE_OPEN, SCYTHE_CLOSE,
        SCYTHE_SWING, OVERDRIVE, WORMHOLE_SCAN, WORMHOLE_BURST }
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_THROW = sound("noctis.spear_throw");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_ANCHOR = sound("noctis.spear_anchor");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_RETURN = sound("noctis.spear_return");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_COLLAPSE = sound("noctis.spear_collapse");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_CUT = sound("noctis.light_cut");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_QUANTUM_CHARGE = sound("noctis.quantum_charge");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_QUANTUM_FIRE = sound("noctis.quantum_fire");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_HALO = sound("noctis.black_halo");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_SCYTHE_UNFOLD = sound("noctis.scythe_unfold");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_SCYTHE_SWING = sound("noctis.scythe_swing");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_OVERDRIVE = sound("noctis.overdrive");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_WORMHOLE_SCAN = sound("noctis.wormhole_scan");
    private static final DeferredHolder<SoundEvent, SoundEvent> NOCTIS_WORMHOLE_BURST = sound("noctis.wormhole_burst");

    /**
     * The Twins spear's sounds, from the Twins family's own glass and rifts for now: a high glassy throw, a lock and a
     * shatter as it pins, a light glass tick for each drone's blow, and a rush on the way home and on a pull.
     */
    public static void spear(ServerLevel level, Vec3 position, Spear moment) {
        float pitch = 1 + (float) level.getRandom().nextGaussian() * .03F;
        switch (moment) {
            case THROW -> level.playSound(null, position.x, position.y, position.z, NOCTIS_THROW.get(), SoundSource.PLAYERS, 1, pitch);
            case ANCHOR -> nearAndFar(level, position, NOCTIS_ANCHOR, 1.2F, pitch);
            case STICK -> level.playSound(null, position.x, position.y, position.z, NOCTIS_ANCHOR.get(), SoundSource.PLAYERS, .8F, .9F * pitch);
            // Silent Gospel: the halo's blades are all but silent, a glass tick each.
            case STRIKE -> level.playSound(null, position.x, position.y, position.z, TWINS_BOLT_IMPACT_1.get(), SoundSource.PLAYERS, .15F,
                    groupPitch((int) (level.getGameTime() / 5), 1.3F));
            case RETURN -> level.playSound(null, position.x, position.y, position.z, NOCTIS_RETURN.get(), SoundSource.PLAYERS, 1, pitch);
            case PULL -> level.playSound(null, position.x, position.y, position.z, NOCTIS_RETURN.get(), SoundSource.PLAYERS, 1, 1.25F * pitch);
            case COLLAPSE -> nearAndFar(level, position, NOCTIS_COLLAPSE, 1, pitch);
            case CUT -> nearAndFar(level, position, NOCTIS_CUT, 1.1F, pitch);
            case BEAM_CHARGE -> level.playSound(null, position.x, position.y, position.z, NOCTIS_QUANTUM_CHARGE.get(), SoundSource.PLAYERS, 1.2F, 1);
            case BEAM_FIRE -> nearAndFar(level, position, NOCTIS_QUANTUM_FIRE, 2, 1);
            case HALO -> nearAndFar(level, position, NOCTIS_HALO, 1.4F, 1);
            case SCYTHE_OPEN -> level.playSound(null, position.x, position.y, position.z, NOCTIS_SCYTHE_UNFOLD.get(), SoundSource.PLAYERS, 1, pitch);
            case SCYTHE_CLOSE -> level.playSound(null, position.x, position.y, position.z, NOCTIS_SCYTHE_UNFOLD.get(), SoundSource.PLAYERS, .8F, .7F * pitch);
            case SCYTHE_SWING -> level.playSound(null, position.x, position.y, position.z, NOCTIS_SCYTHE_SWING.get(), SoundSource.PLAYERS, .9F, pitch);
            case OVERDRIVE -> level.playSound(null, position.x, position.y, position.z, NOCTIS_OVERDRIVE.get(), SoundSource.PLAYERS, 1.1F, 1);
            case WORMHOLE_SCAN -> level.playSound(null, position.x, position.y, position.z, NOCTIS_WORMHOLE_SCAN.get(), SoundSource.PLAYERS, 1.2F, 1);
            case WORMHOLE_BURST -> nearAndFar(level, position, NOCTIS_WORMHOLE_BURST, 2, 1);
        }
    }

    private static void nearAndFar(ServerLevel level, Vec3 position, DeferredHolder<SoundEvent, SoundEvent> near, float volume, float pitch) {
        long seed = level.getRandom().nextLong();
        var nearHolder = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(near.get());
        var farHolder = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(FAR_BOOM.get());
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            double distance = player.position().distanceTo(position);
            if (distance > FAR) continue;
            boolean close = distance <= NEAR;
            // A far listener gets just enough range to hear the boom, quietly: it fades in over the last fifth of it.
            float reach = close ? (float) Math.max(volume, NEAR / 16) : (float) Math.max(1, distance / 16 * 1.25);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(close ? nearHolder : farHolder, SoundSource.PLAYERS,
                    position.x, position.y, position.z, reach, close ? pitch : .9F + (float) level.getRandom().nextGaussian() * .05F, seed));
        }
    }

    /** A cluster lets its charge go. */
    public static void chargeFire(ServerLevel level, Vec3 position, HiveType type) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_CHARGE_FIRE;
            case MANA -> MANA_CHARGE_FIRE;
            case TWINS -> TWINS_CHARGE_FIRE;
        };
        play(level, position, event, Category.CHARGE, 2, .7F, 1F);
    }

    /** The crack of lightning after a blow or a charge lands. */
    public static void swarmExplosion(ServerLevel level, Vec3 position, HiveType type) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_BLAST;
            case MANA -> MANA_BLAST;
            case TWINS -> TWINS_BLAST;
        };
        play(level, position, event, Category.BLAST, 3, .9F, .92F + (float) level.getRandom().nextGaussian() * .05F);
    }

    /** A containment construct at work: RF sealing zaps, the Mana ward's hum, the Twins rift. */
    public static void containment(ServerLevel level, Vec3 position, HiveType type) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case RF -> RF_SEAL;
            case MANA -> MANA_WARD;
            case TWINS -> TWINS_RIFT;
        };
        play(level, position, event, Category.CONTAIN, 10, .7F, 1F);
    }

    /**
     * The sounds of an Armageddon before its blast: charging, the shot leaving, and the shot reaching its target
     * (the Twins black hole devouring the land, the Mana streams colliding; the RF ball meets the ground in a
     * silence every client near fills with its dome's own sound).
     */
    public enum Cannon { CHARGE, FIRE, ARRIVE }

    /** A {@code type} hive's Armageddon charging (heard about 64 blocks off), firing and reaching its target (about 128). */
    public static void armageddon(ServerLevel level, Vec3 position, HiveType type, Cannon sound) {
        DeferredHolder<SoundEvent, SoundEvent> event = switch (type) {
            case MANA -> switch (sound) {
                case CHARGE -> MANA_ARMAGEDDON_CHARGE;
                case FIRE -> MANA_ARMAGEDDON_FIRE;
                case ARRIVE -> MANA_ARMAGEDDON_COLLISION;
            };
            case RF -> switch (sound) {
                case CHARGE -> RF_ARMAGEDDON_CHARGE;
                case FIRE -> RF_ARMAGEDDON_FIRE;
                case ARRIVE -> null;
            };
            case TWINS -> switch (sound) {
                case CHARGE -> ARMAGEDDON_CHARGE;
                case FIRE -> ARMAGEDDON_FIRE;
                case ARRIVE -> ARMAGEDDON_DEVOUR;
            };
        };
        if (event == null) return;
        level.playSound(null, position.x, position.y, position.z, event.get(), SoundSource.PLAYERS, sound == Cannon.CHARGE ? 4F : 8F, 1F);
    }

    /**
     * Hushes a {@code type} Armageddon's charging for everyone who could hear it at {@code position}: a shot called
     * off before it fires must not go on humming for the rest of its minute-long recording.
     */
    public static void hushCharge(ServerLevel level, Vec3 position, HiveType type) {
        SoundEvent charge = (switch (type) {
            case MANA -> MANA_ARMAGEDDON_CHARGE;
            case RF -> RF_ARMAGEDDON_CHARGE;
            case TWINS -> ARMAGEDDON_CHARGE;
        }).get();
        var stop = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(charge.getLocation(), SoundSource.PLAYERS);
        // Heard out to 16 blocks per unit of its volume of 4; a little further, for someone walking away as it stops.
        for (var player : level.players()) {
            if (player.position().distanceToSqr(position) < 80 * 80) player.connection.send(stop);
        }
    }

    /** The Mana ward turns a blow back on its attacker. */
    public static void reflect(ServerLevel level, Vec3 position) {
        play(level, position, WARD_REFLECT, Category.REFLECT, 3, .75F, 1F);
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
        STRIKE,
        SWARM,
        CHARGE,
        BLAST,
        CONTAIN,
        REFLECT
    }

    public enum Ui {
        TOGGLE,
        UPGRADE
    }

    private record SpatialKey(int x, int y, int z, Category category) {
    }
}
