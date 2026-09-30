package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;

/**
 * Sparse, item-local combat replication for a virtual swarm: the creatures it is engaged with (the
 * first is its main target, mirrored in {@code targetId} and {@code targetX..Z}), when the swarm set
 * out ({@code changedAt}), in which mode, how many ticks the flight out takes ({@code travel}), and the
 * recent blows, charges and zaps as world-space events so clients can draw them without an entity
 * per drone. When the set of targets changes mid-fight, {@code previous} keeps the old set as it was and
 * {@code retargetedAt} when, so drones fly from their old places straight to their new ones rather
 * than home first. Everything else (where each drone flies, when each group strikes) follows
 * analytically from these values and the item's own state.
 */
public record HiveCombatState(boolean active, int targetId, long changedAt,
                              double targetX, double targetY, double targetZ, AttackMode mode, int travel, List<Shot> shots,
                              List<HiveTarget> targets, List<HiveTarget> previous, long retargetedAt) {
    public static final int MAX_SHOTS = 100;
    /** At most this many creatures are engaged at once (never more than the swarm has strike groups). */
    public static final int MAX_TARGETS = 16;
    /** Event kinds. Kinds 0 to 3 were the single-drone shots of older versions. */
    public static final int DROPLET = 4, BALL = 5, ZAP = 6, VOID = 7, WARD = 8, INTERCEPT = 9, DRONE_HIT = 10;
    public static final HiveCombatState DEFAULT = new HiveCombatState(false, -1, 0, 0, 0, 0, AttackMode.BARRAGE, 20, List.of());
    public static final Codec<HiveCombatState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("active").forGetter(HiveCombatState::active),
            Codec.INT.fieldOf("target_id").forGetter(HiveCombatState::targetId),
            Codec.LONG.fieldOf("changed_at").forGetter(HiveCombatState::changedAt),
            Codec.DOUBLE.fieldOf("target_x").forGetter(HiveCombatState::targetX),
            Codec.DOUBLE.fieldOf("target_y").forGetter(HiveCombatState::targetY),
            Codec.DOUBLE.fieldOf("target_z").forGetter(HiveCombatState::targetZ),
            Codec.INT.optionalFieldOf("mode", AttackMode.BARRAGE.ordinal()).xmap(AttackMode::byOrdinal, AttackMode::ordinal).forGetter(HiveCombatState::mode),
            Codec.INT.optionalFieldOf("travel", 20).forGetter(HiveCombatState::travel),
            Shot.CODEC.listOf(0, MAX_SHOTS).fieldOf("shots").forGetter(HiveCombatState::shots),
            HiveTarget.CODEC.listOf(0, MAX_TARGETS).optionalFieldOf("targets", List.of()).forGetter(HiveCombatState::targets),
            HiveTarget.CODEC.listOf(0, MAX_TARGETS).optionalFieldOf("previous", List.of()).forGetter(HiveCombatState::previous),
            Codec.LONG.optionalFieldOf("retargeted_at", 0L).forGetter(HiveCombatState::retargetedAt)
    ).apply(i, HiveCombatState::new));
    public static final StreamCodec<ByteBuf, HiveCombatState> STREAM_CODEC = new StreamCodec<>() {
        @Override public void encode(ByteBuf buffer, HiveCombatState state) {
            buffer.writeBoolean(state.active).writeInt(state.targetId).writeLong(state.changedAt)
                    .writeDouble(state.targetX).writeDouble(state.targetY).writeDouble(state.targetZ)
                    .writeByte(state.mode.ordinal()).writeShort(state.travel).writeByte(state.shots.size());
            for (Shot shot : state.shots) VarInt.write(buffer, shot.unit).writeLong(shot.firedAt).writeByte(shot.kind)
                    .writeDouble(shot.startX).writeDouble(shot.startY).writeDouble(shot.startZ)
                    .writeDouble(shot.endX).writeDouble(shot.endY).writeDouble(shot.endZ).writeLong(shot.impactAt);
            writeTargets(buffer, state.targets);
            writeTargets(buffer, state.previous);
            VarLong.write(buffer, state.retargetedAt);
        }
        @Override public HiveCombatState decode(ByteBuf buffer) {
            boolean active = buffer.readBoolean(); int target = buffer.readInt(); long changed = buffer.readLong();
            double x = buffer.readDouble(), y = buffer.readDouble(), z = buffer.readDouble();
            AttackMode mode = AttackMode.byOrdinal(buffer.readUnsignedByte());
            int travel = buffer.readUnsignedShort();
            int size = buffer.readUnsignedByte();
            if (size > MAX_SHOTS) throw new IllegalArgumentException("Oversized hive shot packet");
            var shots = new ArrayList<Shot>(size);
            for (int index = 0; index < size; index++) shots.add(new Shot(VarInt.read(buffer), buffer.readLong(), buffer.readUnsignedByte(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readLong()));
            List<HiveTarget> targets = readTargets(buffer), previous = readTargets(buffer);
            long retargeted = VarLong.read(buffer);
            return new HiveCombatState(active, target, changed, x, y, z, mode, travel, shots, targets, previous, retargeted);
        }
    };

    private static void writeTargets(ByteBuf buffer, List<HiveTarget> targets) {
        buffer.writeByte(targets.size());
        for (HiveTarget target : targets) {
            VarInt.write(buffer, target.id());
            buffer.writeDouble(target.x()).writeDouble(target.y()).writeDouble(target.z())
                    .writeDouble(target.width()).writeDouble(target.height());
        }
    }

    private static List<HiveTarget> readTargets(ByteBuf buffer) {
        int count = buffer.readUnsignedByte();
        if (count > MAX_TARGETS) throw new IllegalArgumentException("Oversized hive target list");
        var targets = new ArrayList<HiveTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new HiveTarget(VarInt.read(buffer), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble()));
        }
        return targets;
    }

    public HiveCombatState {
        changedAt = Math.max(0, changedAt);
        targetX = finite(targetX);
        targetY = finite(targetY);
        targetZ = finite(targetZ);
        if (mode == null) mode = AttackMode.BARRAGE;
        travel = Math.clamp(travel, 1, 400);
        shots = List.copyOf(shots.subList(0, Math.min(MAX_SHOTS, shots.size())));
        targets = List.copyOf(targets.subList(0, Math.min(MAX_TARGETS, targets.size())));
        previous = List.copyOf(previous.subList(0, Math.min(MAX_TARGETS, previous.size())));
        retargetedAt = Math.max(0, retargetedAt);
        if (!active) {
            targetId = -1;
            targets = List.of();
            previous = List.of();
        } else if (targets.isEmpty()) {
            // Older states named a single target; it is the whole engagement.
            targets = List.of(new HiveTarget(targetId, targetX, targetY, targetZ, .6, 1.8));
        }
    }

    /** A state with a single target of unknown size, as older versions kept it. */
    public HiveCombatState(boolean active, int targetId, long changedAt, double targetX, double targetY, double targetZ,
            AttackMode mode, int travel, List<Shot> shots) {
        this(active, targetId, changedAt, targetX, targetY, targetZ, mode, travel, shots, List.of(), List.of(), 0);
    }

    public HiveCombatState withShots(List<Shot> updated) {
        return new HiveCombatState(active, targetId, changedAt, targetX, targetY, targetZ, mode, travel, updated, targets, previous, retargetedAt);
    }

    public HiveCombatState withTargetPosition(double x, double y, double z) {
        List<HiveTarget> moved = new ArrayList<>(targets);
        if (!moved.isEmpty()) {
            HiveTarget main = moved.getFirst();
            moved.set(0, new HiveTarget(main.id(), x, y, z, main.width(), main.height()));
        }
        return new HiveCombatState(active, targetId, changedAt, x, y, z, mode, travel, shots, moved, previous, retargetedAt);
    }

    /** The same engagement with its targets' positions and sizes brought up to date. */
    public HiveCombatState withTargets(List<HiveTarget> updated) {
        HiveTarget main = updated.getFirst();
        return new HiveCombatState(active, main.id(), changedAt, main.x(), main.y(), main.z(), mode, travel, shots, updated, previous, retargetedAt);
    }

    /** The same fight against a different set of creatures: drones fly from where they are, not from the hive. */
    public HiveCombatState retarget(List<HiveTarget> updated, long now) {
        HiveTarget main = updated.getFirst();
        return new HiveCombatState(true, main.id(), changedAt, main.x(), main.y(), main.z(), mode, travel, shots, updated, targets, now);
    }

    public static HiveCombatState target(int entityId, double x, double y, double z, long changedAt, AttackMode mode, int travel) {
        return new HiveCombatState(true, entityId, changedAt, x, y, z, mode, travel, List.of());
    }

    /** A new fight against {@code targets}: the swarm sets out from the hive at {@code changedAt}. */
    public static HiveCombatState engage(List<HiveTarget> targets, long changedAt, AttackMode mode, int travel) {
        HiveTarget main = targets.getFirst();
        return new HiveCombatState(true, main.id(), changedAt, main.x(), main.y(), main.z(), mode, travel, List.of(), targets, List.of(), 0);
    }

    /** Whether the creatures engaged differ from {@code others} (by id, in order). */
    public boolean sameTargets(List<HiveTarget> others) {
        if (others.size() != targets.size()) return false;
        for (int index = 0; index < others.size(); index++) if (others.get(index).id() != targets.get(index).id()) return false;
        return true;
    }

    /** A world-space event: a group's blow, a charge in flight, a zap. {@code unit} is the group or place it came from. */
    public record Shot(int unit, long firedAt, int kind,
                       double startX, double startY, double startZ,
                       double endX, double endY, double endZ, long impactAt) {
        public static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
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
