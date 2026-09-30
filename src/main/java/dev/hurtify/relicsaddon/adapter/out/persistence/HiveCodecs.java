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
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;

/**
 * Saved and synced forms of the hive components (hive_settings, hive_stack_state, hive_combat_state and
 * hive_support_state), moved unchanged out of the records they encode. The bytes and NBT are frozen by
 * golden/codecs.txt.
 */
public final class HiveCodecs {
    private static final Codec<AttackMode> MODE = Codec.STRING.xmap(AttackMode::byId, AttackMode::id);
    private static final Codec<HiveSettings> SETTINGS_RECORD = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, HiveType.MAX_DRONES).fieldOf("healers").forGetter(HiveSettings::healers),
            MODE.optionalFieldOf("mode", AttackMode.BARRAGE).forGetter(HiveSettings::mode)
    ).apply(i, HiveSettings::new));
    /** Older saves stored only the healer count as a bare number. */
    public static final Codec<HiveSettings> SETTINGS = Codec.withAlternative(SETTINGS_RECORD,
            Codec.intRange(0, HiveType.MAX_DRONES).xmap(healers -> new HiveSettings(healers, AttackMode.BARRAGE), HiveSettings::healers));
    public static final StreamCodec<ByteBuf, HiveSettings> SETTINGS_STREAM = new StreamCodec<>() {
        public void encode(ByteBuf buffer, HiveSettings value) { buffer.writeShort(value.healers()).writeByte(value.mode().ordinal()); }
        public HiveSettings decode(ByteBuf buffer) {
            int count = buffer.readUnsignedShort();
            if (count > HiveType.MAX_DRONES) throw new IllegalArgumentException("Invalid healer allocation");
            return new HiveSettings(count, AttackMode.byOrdinal(buffer.readUnsignedByte()));
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
            for (int index = 0; index < count; index++) {
                int packed = buffer.readUnsignedByte();
                long ready = (packed & READY) != 0 ? VarLong.read(buffer) : 0;
                long hit = (packed & HIT) != 0 ? VarLong.read(buffer) : -1;
                long attack = (packed & ATTACK) != 0 ? VarLong.read(buffer) : 0;
                units.add(new HiveStackState.Unit(packed & HP_MASK, ready, hit, attack));
            }
            return new HiveStackState(enabled, units);
        }

        @Override public void encode(ByteBuf buffer, HiveStackState value) {
            buffer.writeBoolean(value.enabled());
            VarInt.write(buffer, value.units().size());
            // One flag byte (health in the low bits) and only the timings a drone actually has.
            for (HiveStackState.Unit unit : value.units()) {
                buffer.writeByte(unit.hp() | (unit.readyAt() > 0 ? READY : 0) | (unit.lastHit() >= 0 ? HIT : 0) | (unit.attackReadyAt() > 0 ? ATTACK : 0));
                if (unit.readyAt() > 0) VarLong.write(buffer, unit.readyAt());
                if (unit.lastHit() >= 0) VarLong.write(buffer, unit.lastHit());
                if (unit.attackReadyAt() > 0) VarLong.write(buffer, unit.attackReadyAt());
            }
        }
    };
    private static final int HP_MASK = 0x0F, READY = 0x10, HIT = 0x20, ATTACK = 0x40;

    public static final StreamCodec<ByteBuf, HiveCombatState> COMBAT_STATE_STREAM = new StreamCodec<>() {
        @Override public void encode(ByteBuf buffer, HiveCombatState state) {
            buffer.writeBoolean(state.active()).writeInt(state.targetId()).writeLong(state.changedAt())
                    .writeDouble(state.targetX()).writeDouble(state.targetY()).writeDouble(state.targetZ())
                    .writeByte(state.mode().ordinal()).writeShort(state.travel()).writeByte(state.shots().size());
            for (HiveCombatState.Shot shot : state.shots()) VarInt.write(buffer, shot.unit()).writeLong(shot.firedAt()).writeByte(shot.kind())
                    .writeDouble(shot.startX()).writeDouble(shot.startY()).writeDouble(shot.startZ())
                    .writeDouble(shot.endX()).writeDouble(shot.endY()).writeDouble(shot.endZ()).writeLong(shot.impactAt());
        }
        @Override public HiveCombatState decode(ByteBuf buffer) {
            boolean active = buffer.readBoolean(); int target = buffer.readInt(); long changed = buffer.readLong();
            double x = buffer.readDouble(), y = buffer.readDouble(), z = buffer.readDouble();
            AttackMode mode = AttackMode.byOrdinal(buffer.readUnsignedByte());
            int travel = buffer.readUnsignedShort();
            int size = buffer.readUnsignedByte();
            if (size > HiveCombatState.MAX_SHOTS) throw new IllegalArgumentException("Oversized hive shot packet");
            var shots = new ArrayList<HiveCombatState.Shot>(size);
            for (int index = 0; index < size; index++) shots.add(new HiveCombatState.Shot(VarInt.read(buffer), buffer.readLong(), buffer.readUnsignedByte(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readLong()));
            return new HiveCombatState(active, target, changed, x, y, z, mode, travel, shots);
        }
    };

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
