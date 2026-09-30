package dev.hurtify.relicsaddon.drone;

import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;

/**
 * Sparse, item-local combat replication for a virtual swarm: the locked target, when the swarm set
 * out ({@code changedAt}), in which mode, how many ticks the flight out takes ({@code travel}), and the
 * recent blows, charges and zaps as world-space events so clients can draw them without an entity
 * per drone. Everything else (where each drone flies, when each group strikes) follows analytically
 * from these values and the item's own state.
 */
public record HiveCombatState(boolean active, int targetId, long changedAt,
                              double targetX, double targetY, double targetZ, AttackMode mode, int travel, List<Shot> shots) {
    public static final int MAX_SHOTS = 100;
    /** Event kinds. Kinds 0 to 3 were the single-drone shots of older versions. */
    public static final int DROPLET = 4, BALL = 5, ZAP = 6, VOID = 7, WARD = 8, INTERCEPT = 9, DRONE_HIT = 10;
    public static final HiveCombatState DEFAULT = new HiveCombatState(false, -1, 0, 0, 0, 0, AttackMode.BARRAGE, 20, List.of());
    public static final StreamCodec<ByteBuf, HiveCombatState> STREAM_CODEC = new StreamCodec<>() {
        @Override public void encode(ByteBuf buffer, HiveCombatState state) {
            buffer.writeBoolean(state.active).writeInt(state.targetId).writeLong(state.changedAt)
                    .writeDouble(state.targetX).writeDouble(state.targetY).writeDouble(state.targetZ)
                    .writeByte(state.mode.ordinal()).writeShort(state.travel).writeByte(state.shots.size());
            for (Shot shot : state.shots) VarInt.write(buffer, shot.unit).writeLong(shot.firedAt).writeByte(shot.kind)
                    .writeDouble(shot.startX).writeDouble(shot.startY).writeDouble(shot.startZ)
                    .writeDouble(shot.endX).writeDouble(shot.endY).writeDouble(shot.endZ).writeLong(shot.impactAt);
        }
        @Override public HiveCombatState decode(ByteBuf buffer) {
            boolean active = buffer.readBoolean(); int target = buffer.readInt(); long changed = buffer.readLong();
            double x = buffer.readDouble(), y = buffer.readDouble(), z = buffer.readDouble();
            AttackMode mode = AttackMode.byOrdinal(buffer.readUnsignedByte());
            int travel = buffer.readUnsignedShort();
            int size = buffer.readUnsignedByte();
            if (size > MAX_SHOTS) throw new IllegalArgumentException("Oversized hive shot packet");
            var shots = new java.util.ArrayList<Shot>(size);
            for (int index = 0; index < size; index++) shots.add(new Shot(VarInt.read(buffer), buffer.readLong(), buffer.readUnsignedByte(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readLong()));
            return new HiveCombatState(active, target, changed, x, y, z, mode, travel, shots);
        }
    };

    public HiveCombatState {
        changedAt = Math.max(0, changedAt);
        targetX = finite(targetX);
        targetY = finite(targetY);
        targetZ = finite(targetZ);
        if (mode == null) mode = AttackMode.BARRAGE;
        travel = Math.clamp(travel, 1, 400);
        shots = List.copyOf(shots.subList(0, Math.min(MAX_SHOTS, shots.size())));
        if (!active) targetId = -1;
    }

    public HiveCombatState withShots(List<Shot> updated) {
        return new HiveCombatState(active, targetId, changedAt, targetX, targetY, targetZ, mode, travel, updated);
    }

    public HiveCombatState withTargetPosition(double x, double y, double z) {
        return new HiveCombatState(active, targetId, changedAt, x, y, z, mode, travel, shots);
    }

    public static HiveCombatState target(int entityId, double x, double y, double z, long changedAt, AttackMode mode, int travel) {
        return new HiveCombatState(true, entityId, changedAt, x, y, z, mode, travel, List.of());
    }

    /** A world-space event: a group's blow, a charge in flight, a zap. {@code unit} is the group or place it came from. */
    public record Shot(int unit, long firedAt, int kind,
                       double startX, double startY, double startZ,
                       double endX, double endY, double endZ, long impactAt) {
        public Shot {
            unit = Math.clamp(unit, 0, HiveType.MAX_DRONES - 1);
            firedAt = Math.max(0, firedAt);
            kind = Math.clamp(kind, 0, 15);
            startX = finite(startX); startY = finite(startY); startZ = finite(startZ);
            endX = finite(endX); endY = finite(endY); endZ = finite(endZ);
            impactAt = Math.max(firedAt, impactAt);
        }
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.clamp(value, -30_000_000D, 30_000_000D) : 0;
    }
}
