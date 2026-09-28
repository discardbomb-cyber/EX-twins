package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/** Sparse component updates, not hundreds of entity trackers or a packet every animation frame. */
public record HiveStackState(boolean enabled, List<Unit> units) {
    public static final HiveStackState DEFAULT = new HiveStackState(true, List.of());
    public static final Codec<HiveStackState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("enabled").forGetter(HiveStackState::enabled),
            Unit.CODEC.listOf(0, HiveType.MAX_DRONES).fieldOf("units").forGetter(HiveStackState::units)
    ).apply(i, HiveStackState::new));
    public static final StreamCodec<ByteBuf, HiveStackState> STREAM_CODEC = new StreamCodec<>() {
        @Override public HiveStackState decode(ByteBuf buffer) {
            boolean enabled = buffer.readBoolean();
            int count = buffer.readUnsignedByte();
            if (count > HiveType.MAX_DRONES) throw new io.netty.handler.codec.DecoderException("Hive unit count exceeds limit");
            var units = new ArrayList<Unit>(count);
            for (int index = 0; index < count; index++) units.add(new Unit(buffer.readUnsignedShort(), buffer.readLong(),
                    buffer.readLong(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readLong()));
            return new HiveStackState(enabled, units);
        }
        @Override public void encode(ByteBuf buffer, HiveStackState value) {
            buffer.writeBoolean(value.enabled()).writeByte(value.units().size());
            for (Unit unit : value.units()) buffer.writeShort(unit.hp()).writeLong(unit.readyAt()).writeLong(unit.lastHit())
                    .writeFloat(unit.x()).writeFloat(unit.y()).writeFloat(unit.z()).writeLong(unit.attackReadyAt());
        }
    };

    public HiveStackState {
        units = List.copyOf(units.subList(0, Math.min(HiveType.MAX_DRONES, units.size())));
    }

    /**
     * {@code readyAt} belongs to interception/repair. {@code attackReadyAt} is deliberately
     * independent: taking a hit, repairing, or toggling an equipped hive must not restart
     * a combat cadence.
     */
    public record Unit(int hp, long readyAt, long lastHit, float x, float y, float z, long attackReadyAt) {
        public static final Codec<Unit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("hp").forGetter(Unit::hp),
                Codec.LONG.fieldOf("ready_at").forGetter(Unit::readyAt),
                Codec.LONG.fieldOf("last_hit").forGetter(Unit::lastHit),
                Codec.FLOAT.fieldOf("x").forGetter(Unit::x),
                Codec.FLOAT.fieldOf("y").forGetter(Unit::y),
                Codec.FLOAT.fieldOf("z").forGetter(Unit::z),
                Codec.LONG.optionalFieldOf("attack_ready_at", 0L).forGetter(Unit::attackReadyAt)
        ).apply(i, Unit::new));

        public Unit {
            hp = Math.clamp(hp, 0, 1000);
            readyAt = Math.max(0, readyAt);
            lastHit = Math.max(-1, lastHit);
            x = Float.isFinite(x) ? Math.clamp(x, -1, 1) : 0;
            y = Float.isFinite(y) ? Math.clamp(y, -1, 1) : 0;
            z = Float.isFinite(z) ? Math.clamp(z, -1, 1) : 0;
            attackReadyAt = Math.max(0, attackReadyAt);
        }

        /** Compatibility constructor for old state fixtures and integrations. */
        public Unit(int hp, long readyAt, long lastHit, float x, float y, float z) {
            this(hp, readyAt, lastHit, x, y, z, 0);
        }

        public static Unit fresh(int health) { return new Unit(health, 0, -1, 0, 0, 0, 0); }
        public boolean ready(long now) { return hp > 0 && now >= readyAt; }
        public boolean attackReady(long now) { return hp > 0 && now >= attackReadyAt; }
    }

    public HiveStackState withEnabled(boolean value) { return new HiveStackState(value, units); }

    public HiveStackState prepare(int capacity, int maxHealth, long now, boolean repair) {
        return prepare(capacity, maxHealth, now, repair, 1);
    }

    public HiveStackState prepare(int capacity, int maxHealth, long now, boolean repair, int repairAmount) {
        capacity = Math.clamp(capacity, 1, HiveType.MAX_DRONES);
        maxHealth = Math.clamp(maxHealth, 1, 1000);
        if ((!repair || !enabled) && capacity == units.size()) {
            boolean bounded = true;
            for (Unit unit : units) if (unit.hp() > maxHealth) { bounded = false; break; }
            if (bounded) return this;
        }
        var next = new ArrayList<Unit>(capacity);
        for (int index = 0; index < capacity; index++) {
            Unit unit = index < units.size() ? units.get(index) : Unit.fresh(maxHealth);
            int health = Math.min(unit.hp(), maxHealth);
            if (repair && enabled) {
                if (health == 0 && now >= unit.readyAt()) health = maxHealth;
                else if (health > 0 && now % 20 == 0 && (unit.lastHit() < 0 || now - unit.lastHit() >= 40)) {
                    health = Math.min(maxHealth, health + Math.clamp(repairAmount, 1, 5));
                }
            }
            next.add(health == unit.hp() ? unit : new Unit(health, unit.readyAt(), unit.lastHit(), unit.x(), unit.y(), unit.z(), unit.attackReadyAt()));
        }
        return next.equals(units) ? this : new HiveStackState(enabled, next);
    }

    public int readyCount(long now) {
        return enabled ? (int) units.stream().filter(unit -> unit.ready(now)).count() : 0;
    }
}
