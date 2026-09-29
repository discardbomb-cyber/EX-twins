package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * Sparse, item-local combat replication for a virtual swarm. Positions are world coordinates
 * so clients can render the same volleys without tracking a mob entity for every drone.
 */
public record HiveCombatState(boolean active, int targetId, long changedAt,
                              double targetX, double targetY, double targetZ, List<Shot> shots) {
    public static final int MAX_SHOTS = 100;
    public static final HiveCombatState DEFAULT = new HiveCombatState(false, -1, 0, 0, 0, 0, List.of());
    public static final Codec<HiveCombatState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(HiveCombatState::active),
            Codec.INT.fieldOf("target_id").forGetter(HiveCombatState::targetId),
            Codec.LONG.fieldOf("changed_at").forGetter(HiveCombatState::changedAt),
            Codec.DOUBLE.fieldOf("target_x").forGetter(HiveCombatState::targetX),
            Codec.DOUBLE.fieldOf("target_y").forGetter(HiveCombatState::targetY),
            Codec.DOUBLE.fieldOf("target_z").forGetter(HiveCombatState::targetZ),
            Shot.CODEC.listOf(0, MAX_SHOTS).fieldOf("shots").forGetter(HiveCombatState::shots)
    ).apply(i, HiveCombatState::new));
    public static final StreamCodec<ByteBuf, HiveCombatState> STREAM_CODEC = new StreamCodec<>() {
        @Override public void encode(ByteBuf buffer, HiveCombatState state) {
            buffer.writeBoolean(state.active).writeInt(state.targetId).writeLong(state.changedAt)
                    .writeDouble(state.targetX).writeDouble(state.targetY).writeDouble(state.targetZ).writeByte(state.shots.size());
            // Drone indices exceed a byte once a swarm passes 256 units.
            for (Shot shot : state.shots) net.minecraft.network.VarInt.write(buffer, shot.unit).writeLong(shot.firedAt).writeByte(shot.kind)
                    .writeDouble(shot.startX).writeDouble(shot.startY).writeDouble(shot.startZ)
                    .writeDouble(shot.endX).writeDouble(shot.endY).writeDouble(shot.endZ).writeLong(shot.impactAt);
        }
        @Override public HiveCombatState decode(ByteBuf buffer) {
            boolean active = buffer.readBoolean(); int target = buffer.readInt(); long changed = buffer.readLong();
            double x = buffer.readDouble(), y = buffer.readDouble(), z = buffer.readDouble();
            int size = buffer.readUnsignedByte();
            if (size > MAX_SHOTS) throw new IllegalArgumentException("Oversized hive shot packet");
            var shots = new java.util.ArrayList<Shot>(size);
            for (int index = 0; index < size; index++) shots.add(new Shot(net.minecraft.network.VarInt.read(buffer), buffer.readLong(), buffer.readUnsignedByte(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readLong()));
            return new HiveCombatState(active, target, changed, x, y, z, shots);
        }
    };

    public HiveCombatState {
        changedAt = Math.max(0, changedAt);
        targetX = finite(targetX);
        targetY = finite(targetY);
        targetZ = finite(targetZ);
        shots = List.copyOf(shots.subList(0, Math.min(MAX_SHOTS, shots.size())));
        if (!active) targetId = -1;
    }

    public HiveCombatState withShots(List<Shot> updated) {
        return new HiveCombatState(active, targetId, changedAt, targetX, targetY, targetZ, updated);
    }

    public static HiveCombatState target(int entityId, double x, double y, double z, long changedAt) {
        return new HiveCombatState(true, entityId, changedAt, x, y, z, List.of());
    }

    public record Shot(int unit, long firedAt, int kind,
                       double startX, double startY, double startZ,
                       double endX, double endY, double endZ, long impactAt) {
        public static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(0, HiveType.MAX_DRONES - 1).fieldOf("unit").forGetter(Shot::unit),
                Codec.LONG.fieldOf("fired_at").forGetter(Shot::firedAt),
                Codec.intRange(0, 3).fieldOf("kind").forGetter(Shot::kind),
                Codec.DOUBLE.fieldOf("start_x").forGetter(Shot::startX),
                Codec.DOUBLE.fieldOf("start_y").forGetter(Shot::startY),
                Codec.DOUBLE.fieldOf("start_z").forGetter(Shot::startZ),
                Codec.DOUBLE.fieldOf("end_x").forGetter(Shot::endX),
                Codec.DOUBLE.fieldOf("end_y").forGetter(Shot::endY),
                Codec.DOUBLE.fieldOf("end_z").forGetter(Shot::endZ),
                Codec.LONG.fieldOf("impact_at").forGetter(Shot::impactAt)
        ).apply(i, Shot::new));

        public Shot {
            unit = Math.clamp(unit, 0, HiveType.MAX_DRONES - 1);
            firedAt = Math.max(0, firedAt);
            kind = Math.clamp(kind, 0, 3);
            startX = finite(startX); startY = finite(startY); startZ = finite(startZ);
            endX = finite(endX); endY = finite(endY); endZ = finite(endZ);
            impactAt = Math.max(firedAt, impactAt);
        }
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -30_000_000D, 30_000_000D) : 0;
    }
}
