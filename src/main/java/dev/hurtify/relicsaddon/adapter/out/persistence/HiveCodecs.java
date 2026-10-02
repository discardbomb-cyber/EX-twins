package dev.hurtify.relicsaddon.adapter.out.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveCombatState;
import dev.hurtify.relicsaddon.domain.hive.HiveSettings;
import dev.hurtify.relicsaddon.domain.hive.HiveStackState;
import dev.hurtify.relicsaddon.domain.hive.HiveSupportState;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import dev.hurtify.relicsaddon.domain.hive.HiveSettings.Notice;
import dev.hurtify.relicsaddon.domain.hive.HiveCombatState.Wing;
import dev.hurtify.relicsaddon.domain.hive.HiveCombatState.Shot;
import dev.hurtify.relicsaddon.domain.hive.HiveTarget;
import dev.hurtify.relicsaddon.domain.hive.ArmageddonState;
import dev.hurtify.relicsaddon.domain.math.Vec3d;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;

/**
 * Saved and synced forms of the hive components (hive_settings, hive_stack_state, hive_combat_state and
 * hive_support_state), including the released multi-wing allocations, retargeting and Armageddon state. Codec checks
 * cover the versioned wire format and backward-compatible saved forms.
 */
public final class HiveCodecs {
        private static final Codec<Notice.Kind> NOTICE_KIND = Codec.STRING.xmap(id -> {
            for (Notice.Kind kind : Notice.Kind.values()) if (kind.name().toLowerCase(Locale.ROOT).equals(id)) return kind;
            return Notice.Kind.CUT;
        }, kind -> kind.name().toLowerCase(Locale.ROOT));
        // Its own codecs: the notice class is first loaded while HiveSettings.DEFAULT is built, before HiveSettings's own are.
        static final Codec<Notice> NOTICE = RecordCodecBuilder.create(i -> i.group(
                NOTICE_KIND.fieldOf("kind").forGetter(Notice::kind),
                Codec.STRING.xmap(AttackMode::byId, AttackMode::id).fieldOf("mode").forGetter(Notice::mode),
                Codec.intRange(0, HiveType.MAX_DRONES).optionalFieldOf("had", 0).forGetter(Notice::had),
                Codec.intRange(0, HiveType.MAX_DRONES).optionalFieldOf("need", 0).forGetter(Notice::need)
        ).apply(i, Notice::new));


    private static final Codec<AttackMode> MODE = Codec.STRING.xmap(AttackMode::byId, AttackMode::id);
    private static final Codec<Integer> COUNT = Codec.intRange(0, HiveType.MAX_DRONES);
    private static final Codec<HiveSettings> RECORD = RecordCodecBuilder.create(i -> i.group(
            COUNT.fieldOf("healers").forGetter(HiveSettings::healers),
            COUNT.fieldOf("droplet").forGetter(HiveSettings::droplet),
            COUNT.fieldOf("barrage").forGetter(HiveSettings::barrage),
            COUNT.fieldOf("containment").forGetter(HiveSettings::containment),
            NOTICE.optionalFieldOf("notice").forGetter(settings -> Optional.ofNullable(settings.notice()))
    ).apply(i, (healers, droplet, barrage, containment, notice) -> new HiveSettings(healers, droplet, barrage, containment, notice.orElse(null))));
    /** Saves from before the modes could be mixed: the healer count and the one mode every fighter used. */
    private static final Codec<HiveSettings> SINGLE_MODE = RecordCodecBuilder.create(i -> i.group(
            COUNT.fieldOf("healers").forGetter(HiveSettings::healers),
            MODE.optionalFieldOf("mode", AttackMode.BARRAGE).forGetter(settings -> AttackMode.BARRAGE)
    ).apply(i, HiveSettings::legacy));
    /** Older saves still stored only the healer count, as a bare number. */
    private static final Codec<HiveSettings> BARE = COUNT.xmap(healers -> HiveSettings.legacy(healers, AttackMode.BARRAGE), HiveSettings::healers);
    public static final Codec<HiveSettings> SETTINGS = Codec.withAlternative(RECORD, Codec.withAlternative(SINGLE_MODE, BARE));

    public static final StreamCodec<ByteBuf, HiveSettings> SETTINGS_STREAM = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSettings value) {
            VarInt.write(buffer, value.healers());
            VarInt.write(buffer, value.droplet());
            VarInt.write(buffer, value.barrage());
            VarInt.write(buffer, value.containment());
            if (value.notice() == null) {
                VarInt.write(buffer, 0);
                return;
            }
            VarInt.write(buffer, value.notice().kind().ordinal() + 1);
            VarInt.write(buffer, value.notice().mode().ordinal());
            VarInt.write(buffer, value.notice().had());
            VarInt.write(buffer, value.notice().need());
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


    /** Saved as parallel arrays (NBT int and long arrays); hit and attack timings do not outlive a reload. */
    private static final Codec<HiveStackState> ARRAYS = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("enabled").forGetter(HiveStackState::enabled),
            Codec.INT.listOf().fieldOf("hp").forGetter(state -> state.units().stream().map(HiveStackState.Unit::hp).toList()),
            Codec.LONG.listOf().fieldOf("ready").forGetter(state -> state.units().stream().map(HiveStackState.Unit::readyAt).toList())
    ).apply(i, HiveCodecs::fromArrays));

    /** Saves from before the 750-drone hive kept one compound per drone; only health and repair time carry over. */
    private static final Codec<HiveStackState> LEGACY = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("enabled").forGetter(HiveStackState::enabled),
            LegacyUnit.CODEC.listOf().fieldOf("units").forGetter(state -> List.of())
    ).apply(i, (enabled, units) -> new HiveStackState(enabled, units.stream().map(LegacyUnit::unit).toList())));

    public static final Codec<HiveStackState> STACK_STATE = Codec.withAlternative(ARRAYS, LEGACY);

    public static final StreamCodec<ByteBuf, HiveStackState> STACK_STATE_STREAM = new StreamCodec<>() {
        @Override public HiveStackState decode(ByteBuf buffer) {
            boolean enabled = buffer.readBoolean();
            int count = VarInt.read(buffer);
            if (count < 0 || count > HiveType.MAX_DRONES) throw new DecoderException("Hive unit count exceeds limit");
            var units = new ArrayList<HiveStackState.Unit>(count);
            while (units.size() < count) {
                int run = VarInt.read(buffer);
                if (run < 1 || run > count - units.size()) throw new DecoderException("Invalid hive unit run");
                int packed = buffer.readUnsignedByte();
                if ((packed & 0x80) != 0 || (packed & HP_MASK) > HiveType.DRONE_HP)
                    throw new DecoderException("Invalid hive unit flags");
                long ready = (packed & READY) != 0 ? VarLong.read(buffer) : 0;
                long hit = (packed & HIT) != 0 ? VarLong.read(buffer) : -1;
                long attack = (packed & ATTACK) != 0 ? VarLong.read(buffer) : 0;
                if (ready < 0 || hit < -1 || attack < 0) throw new DecoderException("Invalid hive unit timing");
                HiveStackState.Unit unit = packed == HiveType.DRONE_HP ? HiveStackState.Unit.fresh()
                        : new HiveStackState.Unit(packed & HP_MASK, ready, hit, attack);
                for (int index = 0; index < run; index++) units.add(unit);
            }
            return new HiveStackState(enabled, units);
        }

        @Override public void encode(ByteBuf buffer, HiveStackState value) {
            buffer.writeBoolean(value.enabled());
            VarInt.write(buffer, value.units().size());
            // Wire v5: bounded equal-unit runs, exact timings. Persistence stays unchanged.
            // Client and server require the same JAR; Armageddon handshake is version 5.
            for (int start = 0; start < value.units().size();) {
                HiveStackState.Unit unit = value.units().get(start);
                int end = start + 1;
                while (end < value.units().size() && unit.equals(value.units().get(end))) end++;
                VarInt.write(buffer, end - start);
                buffer.writeByte(unit.hp() | (unit.readyAt() > 0 ? READY : 0) | (unit.lastHit() >= 0 ? HIT : 0) | (unit.attackReadyAt() > 0 ? ATTACK : 0));
                if (unit.readyAt() > 0) VarLong.write(buffer, unit.readyAt());
                if (unit.lastHit() >= 0) VarLong.write(buffer, unit.lastHit());
                if (unit.attackReadyAt() > 0) VarLong.write(buffer, unit.attackReadyAt());
                start = end;
            }
        }
    };
    private static final int HP_MASK = 0x0F, READY = 0x10, HIT = 0x20, ATTACK = 0x40;

    public static final Codec<HiveTarget> TARGET = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(HiveTarget::id),
            Codec.DOUBLE.fieldOf("x").forGetter(HiveTarget::x),
            Codec.DOUBLE.fieldOf("y").forGetter(HiveTarget::y),
            Codec.DOUBLE.fieldOf("z").forGetter(HiveTarget::z),
            Codec.DOUBLE.optionalFieldOf("width", .6).forGetter(HiveTarget::width),
            Codec.DOUBLE.optionalFieldOf("height", 1.8).forGetter(HiveTarget::height)
    ).apply(i, HiveTarget::new));


        public static final Codec<Wing> WING = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("out").forGetter(Wing::out),
                Codec.LONG.optionalFieldOf("since", 0L).forGetter(Wing::since),
                Codec.INT.optionalFieldOf("layout", 0).forGetter(Wing::layout)
        ).apply(i, Wing::new));


        public static final Codec<Shot> SHOT = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, HiveType.MAX_DRONES - 1).fieldOf("unit").forGetter(Shot::unit),
                Codec.LONG.fieldOf("fired_at").forGetter(Shot::firedAt),
                Codec.intRange(0, 15).fieldOf("kind").forGetter(Shot::kind),
                Codec.DOUBLE.fieldOf("start_x").forGetter(Shot::startX),
                Codec.DOUBLE.fieldOf("start_y").forGetter(Shot::startY),
                Codec.DOUBLE.fieldOf("start_z").forGetter(Shot::startZ),
                Codec.DOUBLE.fieldOf("end_x").forGetter(Shot::endX),
                Codec.DOUBLE.fieldOf("end_y").forGetter(Shot::endY),
                Codec.DOUBLE.fieldOf("end_z").forGetter(Shot::endZ),
                Codec.LONG.fieldOf("impact_at").forGetter(Shot::impactAt)
        ).apply(i, Shot::new));


    public static final Codec<HiveCombatState> COMBAT_STATE = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(HiveCombatState::active),
            Codec.INT.fieldOf("target_id").forGetter(HiveCombatState::targetId),
            Codec.LONG.fieldOf("changed_at").forGetter(HiveCombatState::changedAt),
            Codec.DOUBLE.fieldOf("target_x").forGetter(HiveCombatState::targetX),
            Codec.DOUBLE.fieldOf("target_y").forGetter(HiveCombatState::targetY),
            Codec.DOUBLE.fieldOf("target_z").forGetter(HiveCombatState::targetZ),
            WING.listOf(0, HiveCombatState.WINGS).optionalFieldOf("wings", List.of()).forGetter(HiveCombatState::wings),
            Codec.INT.optionalFieldOf("travel", 20).forGetter(HiveCombatState::travel),
            SHOT.listOf(0, HiveCombatState.MAX_SHOTS).fieldOf("shots").forGetter(HiveCombatState::shots),
            TARGET.listOf(0, HiveCombatState.MAX_TARGETS).optionalFieldOf("targets", List.of()).forGetter(HiveCombatState::targets),
            TARGET.listOf(0, HiveCombatState.MAX_TARGETS).optionalFieldOf("previous", List.of()).forGetter(HiveCombatState::previous),
            Codec.LONG.optionalFieldOf("retargeted_at", 0L).forGetter(HiveCombatState::retargetedAt),
            Codec.intRange(0, HiveCombatState.MAX_TARGETS).optionalFieldOf("previous_held", 0).forGetter(HiveCombatState::previousHeld)
    ).apply(i, HiveCombatState::new));
    public static final StreamCodec<ByteBuf, HiveCombatState> COMBAT_STATE_STREAM = new StreamCodec<>() {
        @Override public void encode(ByteBuf buffer, HiveCombatState state) {
            buffer.writeBoolean(state.active()).writeInt(state.targetId()).writeLong(state.changedAt())
                    .writeDouble(state.targetX()).writeDouble(state.targetY()).writeDouble(state.targetZ())
                    .writeShort(state.travel());
            VarInt.write(buffer, state.shots().size());
            VarInt.write(buffer, state.wings().size());
            for (Wing wing : state.wings()) {
                buffer.writeBoolean(wing.out());
                VarLong.write(buffer, wing.since());
                VarInt.write(buffer, wing.layout());
            }
            for (Shot shot : state.shots()) VarInt.write(buffer, shot.unit()).writeLong(shot.firedAt()).writeByte(shot.kind())
                    .writeDouble(shot.startX()).writeDouble(shot.startY()).writeDouble(shot.startZ())
                    .writeDouble(shot.endX()).writeDouble(shot.endY()).writeDouble(shot.endZ()).writeLong(shot.impactAt());
            writeTargets(buffer, state.targets());
            writeTargets(buffer, state.previous());
            VarLong.write(buffer, state.retargetedAt());
            VarInt.write(buffer, state.previousHeld());
        }
        @Override public HiveCombatState decode(ByteBuf buffer) {
            boolean active = buffer.readBoolean(); int target = buffer.readInt(); long changed = buffer.readLong();
            double x = buffer.readDouble(), y = buffer.readDouble(), z = buffer.readDouble();
            int travel = buffer.readUnsignedShort();
            int size = VarInt.read(buffer);
            if (size < 0 || size > HiveCombatState.MAX_SHOTS) throw new DecoderException("Invalid hive shot count");
            int wingCount = VarInt.read(buffer);
            if (wingCount < 0 || wingCount > HiveCombatState.WINGS) throw new IllegalArgumentException("Oversized hive wing list");
            var wings = new ArrayList<Wing>(wingCount);
            for (int index = 0; index < wingCount; index++) wings.add(new Wing(buffer.readBoolean(), VarLong.read(buffer), VarInt.read(buffer)));
            if (size > HiveCombatState.MAX_SHOTS) throw new IllegalArgumentException("Oversized hive shot packet");
            var shots = new ArrayList<Shot>(size);
            for (int index = 0; index < size; index++) shots.add(new Shot(VarInt.read(buffer), buffer.readLong(), buffer.readUnsignedByte(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readLong()));
            List<HiveTarget> targets = readTargets(buffer), previous = readTargets(buffer);
            long retargeted = VarLong.read(buffer);
            int previousHeld = VarInt.read(buffer);
            if (previousHeld < 0 || previousHeld > HiveCombatState.MAX_TARGETS) throw new IllegalArgumentException("Invalid held target count");
            return new HiveCombatState(active, target, changed, x, y, z, wings, travel, shots, targets, previous, retargeted, previousHeld);
        }
    };

    private static void writeTargets(ByteBuf buffer, List<HiveTarget> targets) {
        VarInt.write(buffer, targets.size());
        for (HiveTarget target : targets) {
            VarInt.write(buffer, target.id());
            buffer.writeDouble(target.x()).writeDouble(target.y()).writeDouble(target.z())
                    .writeDouble(target.width()).writeDouble(target.height());
        }
    }

    private static List<HiveTarget> readTargets(ByteBuf buffer) {
        int count = VarInt.read(buffer);
        if (count < 0 || count > HiveCombatState.MAX_TARGETS) throw new DecoderException("Oversized hive target list");
        var targets = new ArrayList<HiveTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new HiveTarget(VarInt.read(buffer), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble()));
        }
        return targets;
    }


    public static final StreamCodec<ByteBuf, ArmageddonState> ARMAGEDDON_STREAM = new StreamCodec<>() {
        public void encode(ByteBuf buffer, ArmageddonState value) {
            VarInt.write(buffer, value.type().ordinal());
            VarLong.write(buffer, value.startedAt());
            write(buffer, value.origin());
            write(buffer, value.target());
            VarInt.write(buffer, value.face().get3DDataValue());
            buffer.writeFloat((float) value.room());
            buffer.writeBoolean(value.shieldLinked());
        }

        public ArmageddonState decode(ByteBuf buffer) {
            int type = VarInt.read(buffer);
            return new ArmageddonState(type >= 0 && type < HiveType.values().length ? HiveType.values()[type] : HiveType.TWINS,
                    VarLong.read(buffer), read(buffer), read(buffer), ArmageddonState.Face.from3DDataValue(VarInt.read(buffer)), buffer.readFloat(), buffer.readBoolean());
        }
    };

    private static void write(ByteBuf buffer, Vec3d value) {
        buffer.writeDouble(value.x).writeDouble(value.y).writeDouble(value.z);
    }

    private static Vec3d read(ByteBuf buffer) {
        return new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public static final StreamCodec<ByteBuf, HiveSupportState> SUPPORT_STATE_STREAM = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSupportState value) { buffer.writeBoolean(value.active()).writeLong(value.changedAt()); }
        public HiveSupportState decode(ByteBuf buffer) { return new HiveSupportState(buffer.readBoolean(), buffer.readLong()); }
    };

    private static HiveStackState fromArrays(boolean enabled, List<Integer> hp, List<Long> ready) {
        var units = new ArrayList<HiveStackState.Unit>(hp.size());
        for (int index = 0; index < hp.size(); index++) units.add(new HiveStackState.Unit(hp.get(index), index < ready.size() ? ready.get(index) : 0, -1, 0));
        return new HiveStackState(enabled, units);
    }

    private record LegacyUnit(int hp, long readyAt) {
        static final Codec<LegacyUnit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("hp").forGetter(LegacyUnit::hp),
                Codec.LONG.optionalFieldOf("ready_at", 0L).forGetter(LegacyUnit::readyAt)
        ).apply(i, LegacyUnit::new));

        HiveStackState.Unit unit() { return new HiveStackState.Unit(hp > 0 ? HiveType.DRONE_HP : 0, readyAt, -1, 0); }
    }

    private HiveCodecs() {
    }
}
