package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;

/**
 * A hive's standing orders: how many of its last drones heal, and how many of the others each attack
 * mode gets. The modes fight at once; a mode with no drones is off, and a mode with drones has at least
 * one per corner of its figure ({@link HiveFigures}). Drones given to no one wait in the hive. Survives
 * damage, repair and reloads.
 *
 * <p>{@code notice} tells the owner why the hive changed their orders by itself (an old save moved to
 * another mode, a mode switched off when the hive shrank), until they next change them. An old save not
 * yet seen by the server carries a {@link Notice.Kind#LEGACY} notice instead: its whole swarm fought in one
 * mode, and {@link #resolve} turns that into counts once the hive's family and size are known.
 */
public record HiveSettings(int healers, int droplet, int barrage, int containment, Notice notice) {
    /** A new hive: every fighter in Barrage, as before the modes could be mixed. */
    public static final HiveSettings DEFAULT = legacy(0, AttackMode.BARRAGE);

    private static final Codec<AttackMode> MODE = Codec.STRING.xmap(HiveSettings::modeById, AttackMode::id);
    private static final Codec<Integer> COUNT = Codec.intRange(0, HiveType.MAX_DRONES);
    private static final Codec<HiveSettings> RECORD = RecordCodecBuilder.create(i -> i.group(
            COUNT.fieldOf("healers").forGetter(HiveSettings::healers),
            COUNT.fieldOf("droplet").forGetter(HiveSettings::droplet),
            COUNT.fieldOf("barrage").forGetter(HiveSettings::barrage),
            COUNT.fieldOf("containment").forGetter(HiveSettings::containment),
            Notice.CODEC.optionalFieldOf("notice").forGetter(settings -> Optional.ofNullable(settings.notice))
    ).apply(i, (healers, droplet, barrage, containment, notice) -> new HiveSettings(healers, droplet, barrage, containment, notice.orElse(null))));
    /** Saves from before the modes could be mixed: the healer count and the one mode every fighter used. */
    private static final Codec<HiveSettings> SINGLE_MODE = RecordCodecBuilder.create(i -> i.group(
            COUNT.fieldOf("healers").forGetter(HiveSettings::healers),
            MODE.optionalFieldOf("mode", AttackMode.BARRAGE).forGetter(settings -> AttackMode.BARRAGE)
    ).apply(i, HiveSettings::legacy));
    /** Older saves still stored only the healer count, as a bare number. */
    private static final Codec<HiveSettings> BARE = COUNT.xmap(healers -> legacy(healers, AttackMode.BARRAGE), HiveSettings::healers);
    public static final Codec<HiveSettings> CODEC = Codec.withAlternative(RECORD, Codec.withAlternative(SINGLE_MODE, BARE));

    public static final StreamCodec<ByteBuf, HiveSettings> STREAM_CODEC = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSettings value) {
            VarInt.write(buffer, value.healers);
            VarInt.write(buffer, value.droplet);
            VarInt.write(buffer, value.barrage);
            VarInt.write(buffer, value.containment);
            if (value.notice == null) {
                VarInt.write(buffer, 0);
                return;
            }
            VarInt.write(buffer, value.notice.kind().ordinal() + 1);
            VarInt.write(buffer, value.notice.mode().ordinal());
            VarInt.write(buffer, value.notice.had());
            VarInt.write(buffer, value.notice.need());
        }

        public HiveSettings decode(ByteBuf buffer) {
            int healers = count(buffer), droplet = count(buffer), barrage = count(buffer), containment = count(buffer);
            int kind = VarInt.read(buffer);
            if (kind < 0 || kind > Notice.Kind.values().length) throw new DecoderException("Invalid hive notice");
            Notice notice = null;
            if (kind > 0) {
                int mode = VarInt.read(buffer);
                if (mode < 0 || mode >= AttackMode.values().length) throw new DecoderException("Invalid hive notice mode");
                notice = new Notice(Notice.Kind.values()[kind - 1], AttackMode.values()[mode], count(buffer), count(buffer));
            }
            return new HiveSettings(healers, droplet, barrage, containment, notice);
        }

        private static int count(ByteBuf buffer) {
            int count = VarInt.read(buffer);
            if (count < 0 || count > HiveType.MAX_DRONES) throw new DecoderException("Invalid hive allocation");
            return count;
        }
    };

    public HiveSettings {
        healers = Math.clamp(healers, 0, HiveType.MAX_DRONES);
        droplet = Math.clamp(droplet, 0, HiveType.MAX_DRONES);
        barrage = Math.clamp(barrage, 0, HiveType.MAX_DRONES);
        containment = Math.clamp(containment, 0, HiveType.MAX_DRONES);
    }

    public HiveSettings(int healers, int droplet, int barrage, int containment) {
        this(healers, droplet, barrage, containment, null);
    }

    /** An old save's orders: {@code healers} healers and every other drone in {@code mode}, to be counted out by {@link #resolve}. */
    public static HiveSettings legacy(int healers, AttackMode mode) {
        return new HiveSettings(healers, 0, 0, 0, new Notice(Notice.Kind.LEGACY, mode == null ? AttackMode.BARRAGE : mode, 0, 0));
    }

    public boolean pending() { return notice != null && notice.kind() == Notice.Kind.LEGACY; }

    /** Drones given to {@code mode}. */
    public int allocated(AttackMode mode) {
        return switch (mode) {
            case DROPLET -> droplet;
            case BARRAGE -> barrage;
            case CONTAINMENT -> containment;
        };
    }

    /** Drones given to any mode. */
    public int assigned() { return droplet + barrage + containment; }

    public int healerCount(int capacity) { return Math.min(healers, Math.clamp(capacity, 0, HiveType.MAX_DRONES)); }
    /** Drones that do not heal: every mode's and the free ones. */
    public int fighters(int capacity) { return Math.clamp(capacity, 0, HiveType.MAX_DRONES) - healerCount(capacity); }
    public boolean healer(int index, int capacity) { return index >= fighters(capacity) && index < capacity; }
    /** Drones neither healing nor given to a mode (of settings already {@link #resolve resolved} for {@code capacity}). */
    public int free(int capacity) { return Math.max(0, fighters(capacity) - assigned()); }

    /** The same orders with {@code count} drones in {@code mode}; the owner changed them, so any notice is spent. */
    public HiveSettings with(AttackMode mode, int count) {
        return new HiveSettings(healers, mode == AttackMode.DROPLET ? count : droplet, mode == AttackMode.BARRAGE ? count : barrage,
                mode == AttackMode.CONTAINMENT ? count : containment, null);
    }

    public HiveSettings withHealers(int value) { return new HiveSettings(value, droplet, barrage, containment, null); }

    /**
     * These orders made valid for a hive of {@code type} holding {@code capacity} drones. Healers come first;
     * then Droplet, Barrage and Containment in turn keep what still fits. A mode left with fewer drones than
     * its figure has corners is switched off, its drones freed, and a notice says why. An old single-mode
     * save puts every fighter in its mode, or in Barrage if that mode's figure cannot be built from them, or
     * nowhere if not even Barrage's can. Returns this very object when nothing needs to change.
     */
    public HiveSettings resolve(HiveType type, int capacity) {
        capacity = Math.clamp(capacity, 0, HiveType.MAX_DRONES);
        int healerCount = Math.min(healers, capacity), fighters = capacity - healerCount;
        if (pending()) {
            AttackMode mode = notice.mode();
            if (fighters == 0) return new HiveSettings(healerCount, 0, 0, 0, null);
            if (HiveFigures.allowed(type, mode, fighters)) return new HiveSettings(healerCount, 0, 0, 0, null).with(mode, fighters);
            boolean moved = HiveFigures.allowed(type, AttackMode.BARRAGE, fighters);
            return new HiveSettings(healerCount, 0, moved ? fighters : 0, 0,
                    new Notice(moved ? Notice.Kind.MOVED : Notice.Kind.EMPTY, mode, fighters, HiveFigures.minimum(type, mode)));
        }
        int left = fighters;
        int[] kept = new int[AttackMode.values().length];
        Notice cut = null;
        for (AttackMode mode : AttackMode.values()) {
            int count = Math.min(allocated(mode), left);
            if (!HiveFigures.allowed(type, mode, count)) {
                if (cut == null) cut = new Notice(Notice.Kind.CUT, mode, count, HiveFigures.minimum(type, mode));
                count = 0;
            }
            kept[mode.ordinal()] = count;
            left -= count;
        }
        if (healerCount == healers && kept[0] == droplet && kept[1] == barrage && kept[2] == containment) return this;
        return new HiveSettings(healerCount, kept[0], kept[1], kept[2], cut != null ? cut : notice);
    }

    /** Whether these orders already fit a hive of {@code type} holding {@code capacity} drones. */
    public boolean valid(HiveType type, int capacity) {
        return !pending() && resolve(type, capacity) == this;
    }

    /** Why a change to the orders is refused. */
    public enum Refusal { NONE_FREE, NOTHING_LEFT, BELOW_MINIMUM }

    /** A proposed count for a mode (or the healers), or why it cannot be had. */
    public record Change(int value, Refusal refusal) {
        public boolean allowed() { return refusal == null; }
    }

    /**
     * What a {@code delta} button does to {@code mode} (in a hive of {@code type} and {@code capacity}): from
     * off, adding jumps straight to the figure's corner count; from exactly that count, taking away switches
     * the mode off; anything else that would leave 1 to corners - 1 drones is refused. Only free drones can
     * be added.
     */
    public Change adjust(HiveType type, int capacity, AttackMode mode, int delta) {
        HiveSettings current = resolve(type, capacity);
        int now = current.allocated(mode), free = current.free(capacity), minimum = HiveFigures.minimum(type, mode);
        int next;
        if (delta > 0) {
            if (free <= 0) return new Change(now, Refusal.NONE_FREE);
            next = Math.min(now == 0 ? Math.max(delta, minimum) : now + delta, now + free);
        } else if (delta < 0) {
            if (now == 0) return new Change(now, Refusal.NOTHING_LEFT);
            next = now == minimum ? 0 : Math.max(0, now + delta);
        } else next = now;
        return HiveFigures.allowed(type, mode, next) ? new Change(next, null) : new Change(next, Refusal.BELOW_MINIMUM);
    }

    /** Every fighter into {@code mode} and none into the others; refused if they are too few for its figure. */
    public Change allInto(HiveType type, int capacity, AttackMode mode) {
        int fighters = resolve(type, capacity).fighters(capacity);
        if (fighters == 0) return new Change(0, Refusal.NONE_FREE);
        return HiveFigures.allowed(type, mode, fighters) ? new Change(fighters, null) : new Change(fighters, Refusal.BELOW_MINIMUM);
    }

    /** What a healer button does: healers only come from free drones and go back to them. */
    public Change adjustHealers(HiveType type, int capacity, int delta) {
        HiveSettings current = resolve(type, capacity);
        int now = current.healerCount(capacity), free = current.free(capacity);
        if (delta > 0 && free <= 0) return new Change(now, Refusal.NONE_FREE);
        if (delta < 0 && now == 0) return new Change(now, Refusal.NOTHING_LEFT);
        return new Change(Math.clamp(now + delta, 0, now + free), null);
    }

    /**
     * Why the hive changed its owner's orders by itself: an old save's mode could not be built from its
     * fighters ({@code MOVED} to Barrage, or {@code EMPTY}: no mode at all), or {@code mode} was switched
     * off ({@code CUT}) with {@code had} drones where its figure needs {@code need}. {@code LEGACY} marks
     * an old save not yet counted out.
     */
    public record Notice(Kind kind, AttackMode mode, int had, int need) {
        public enum Kind { LEGACY, MOVED, EMPTY, CUT }

        private static final Codec<Kind> KIND = Codec.STRING.xmap(id -> {
            for (Kind kind : Kind.values()) if (kind.name().toLowerCase(Locale.ROOT).equals(id)) return kind;
            return Kind.CUT;
        }, kind -> kind.name().toLowerCase(Locale.ROOT));
        // Its own codecs: the notice class is first loaded while HiveSettings.DEFAULT is built, before HiveSettings's own are.
        static final Codec<Notice> CODEC = RecordCodecBuilder.create(i -> i.group(
                KIND.fieldOf("kind").forGetter(Notice::kind),
                Codec.STRING.xmap(HiveSettings::modeById, AttackMode::id).fieldOf("mode").forGetter(Notice::mode),
                Codec.intRange(0, HiveType.MAX_DRONES).optionalFieldOf("had", 0).forGetter(Notice::had),
                Codec.intRange(0, HiveType.MAX_DRONES).optionalFieldOf("need", 0).forGetter(Notice::need)
        ).apply(i, Notice::new));

        public Notice {
            if (kind == null) kind = Kind.CUT;
            if (mode == null) mode = AttackMode.BARRAGE;
            had = Math.clamp(had, 0, HiveType.MAX_DRONES);
            need = Math.clamp(need, 0, HiveType.MAX_DRONES);
        }
    }

    private static AttackMode modeById(String id) {
        for (AttackMode mode : AttackMode.values()) if (mode.id().equals(id)) return mode;
        return AttackMode.BARRAGE;
    }
}
