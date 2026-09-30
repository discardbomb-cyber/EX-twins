package dev.hurtify.relicsaddon.adapter.out.persistence;

import com.mojang.serialization.Codec;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * Every synced component must read back exactly what it wrote, or item sync packets desynchronise;
 * saved forms must read back too, including saves from older versions.
 */
public final class NetworkCodecCheck {
    public static void main(String[] args) {
        // A full 750-drone hive, some drones hit and on their way home.
        List<HiveStackState.Unit> units = new ArrayList<>();
        for (int index = 0; index < HiveType.MAX_DRONES; index++) {
            units.add(index % 9 == 0 ? new HiveStackState.Unit(index % 4, 1_000_000L + index, 2_000_000L + index, 3_000_000L + index)
                    : HiveStackState.Unit.fresh());
        }
        HiveStackState swarm = new HiveStackState(true, units);
        int swarmBytes = roundTrip(HiveCodecs.STACK_STATE_STREAM, swarm, "full swarm");
        int restingBytes = roundTrip(HiveCodecs.STACK_STATE_STREAM, new HiveStackState(true,
                java.util.Collections.nCopies(HiveType.MAX_DRONES, HiveStackState.Unit.fresh())), "resting swarm");
        require(restingBytes < 800, "a resting 750-drone swarm must fit in about a byte per drone, took " + restingBytes);

        // After a fight, quiet lanes drop their timings and the swarm is back to a byte per drone.
        int slots = HiveType.MAX_DEPLOYED, fighters = HiveType.MAX_DRONES;
        List<HiveStackState.Unit> fought = new ArrayList<>();
        for (int index = 0; index < HiveType.MAX_DRONES; index++) {
            fought.add(index % 5 == 0 ? HiveStackState.Unit.fresh().hit(1, 1_000 + index, 1_100 + index) : HiveStackState.Unit.fresh());
        }
        fought.set(3, HiveStackState.Unit.fresh().hit(1, 5_950, 5_990));
        HiveStackState afterFight = new HiveStackState(true, fought);
        HiveStackState repaired = afterFight.prepare(HiveType.MAX_DRONES, 6_000, true);
        HiveStackState settled = repaired.settle(6_000, slots, fighters, 120);
        require(roundTrip(HiveCodecs.STACK_STATE_STREAM, repaired, "fought swarm") > 1000, "a fought swarm carries its timings");
        require(settled.units().get(0).equals(HiveStackState.Unit.fresh()) && settled.units().get(slots).equals(HiveStackState.Unit.fresh()),
                "a quiet lane starts afresh");
        require(!settled.units().get(3).equals(HiveStackState.Unit.fresh()), "a lane with a drone hit moments ago keeps its timings");
        require(roundTrip(HiveCodecs.STACK_STATE_STREAM, settled, "settled swarm") < 800 + 30, "a settled swarm is back to about a byte per drone");
        for (int lane = 0; lane < slots; lane++) {
            if (lane == 3) continue;
            require(HiveSlots.occupant(settled.units(), lane, slots, fighters, 6_000) == lane, "a settled lane is flown by its first drone");
        }
        require(settled.settle(6_000, slots, fighters, 120) == settled, "settling twice changes nothing");
        HiveStackState foreign = new HiveStackState(true, List.of(HiveStackState.Unit.fresh().hit(1, 9_000_000, 9_000_200)));
        HiveStackState.Unit pulled = foreign.settle(100, 1, 1, 120).units().get(0);
        require(pulled.hp() == 2 && pulled.readyAt() <= 100 && pulled.lastHit() < 0,
                "timings from another world's later clock are pulled back, so the drone is repaired here rather than grounded for days");

        // Saves keep health and repair time; older saves (one compound per drone) still load.
        HiveStackState saved = decode(HiveCodecs.STACK_STATE, encode(HiveCodecs.STACK_STATE, swarm));
        for (int index = 0; index < units.size(); index++) {
            require(saved.units().get(index).hp() == units.get(index).hp() && saved.units().get(index).readyAt() == units.get(index).readyAt(),
                    "drone " + index + " lost its health or repair time in a save");
        }
        CompoundTag legacy = new CompoundTag();
        legacy.putBoolean("enabled", true);
        ListTag legacyUnits = new ListTag();
        for (int index = 0; index < 5; index++) {
            CompoundTag unit = new CompoundTag();
            unit.putInt("hp", index == 2 ? 0 : 35);
            unit.putLong("ready_at", index == 2 ? 900 : 0);
            unit.putLong("last_hit", -1);
            unit.putFloat("x", 0); unit.putFloat("y", 0); unit.putFloat("z", 0);
            legacyUnits.add(unit);
        }
        legacy.put("units", legacyUnits);
        HiveStackState old = decode(HiveCodecs.STACK_STATE, legacy);
        require(old.units().size() == 5 && old.units().get(0).hp() == HiveType.DRONE_HP && old.units().get(2).hp() == 0
                && old.units().get(2).readyAt() == 900, "an old save must load with whole drones and the destroyed one rebuilding");

        // Settings: the attack mode travels and saves; old saves held only a healer count.
        for (AttackMode mode : AttackMode.values()) {
            HiveSettings settings = new HiveSettings(37, mode);
            roundTrip(HiveCodecs.SETTINGS_STREAM, settings, "settings " + mode);
            require(decode(HiveCodecs.SETTINGS, encode(HiveCodecs.SETTINGS, settings)).equals(settings), "settings " + mode + " did not survive a save");
        }
        require(decode(HiveCodecs.SETTINGS, IntTag.valueOf(12)).equals(new HiveSettings(12, AttackMode.BARRAGE)), "an old healer count must still load");

        List<HiveCombatState.Shot> shots = new ArrayList<>();
        for (int index = 0; index < HiveCombatState.MAX_SHOTS; index++) {
            shots.add(new HiveCombatState.Shot(HiveType.MAX_DRONES - 1 - index, 5_000L + index, 4 + index % 7, 1, 2, 3, 4, 5, 6, 5_010L + index));
        }
        for (AttackMode mode : AttackMode.values()) {
            roundTrip(HiveCodecs.COMBAT_STATE_STREAM, new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, mode, 44, shots), "combat " + mode);
        }

        for (DeviceEnergy.ManaSource source : DeviceEnergy.ManaSource.values()) {
            roundTrip(DeviceEnergy.STREAM_CODEC, new DeviceEnergy(1_000_000, 100_000, false, true, source), "battery " + source);
        }

        // Axis-aligned normals survive the constructor's re-normalisation bit for bit.
        roundTrip(ShieldCodecs.IMPACT_STREAM, new ShieldImpact(new Vec3(1, 0, 0), 99L, 1, 6, true, List.of(3), 1.5, 0), "absorbed hit");
        ShieldImpact strike = ShieldImpact.strike(new Vec3(0, 0, 1), 1_234L, 2, 4.5F, .3F);
        roundTrip(ShieldCodecs.IMPACT_STREAM, strike, "shield strike");
        require(strike.isStrike() && !new ShieldImpact(new Vec3(0, 1, 0), 5L, 0, 2, false).isStrike(), "Only strikes are marked as strikes");
        System.out.println("Network codecs: 750-drone swarm (" + swarmBytes + " bytes, " + restingBytes + " at rest), old saves, settings, "
                + "combat, batteries and shield impacts round-trip exactly");
    }

    private static <T> Tag encode(Codec<T> codec, T value) {
        return codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
    }

    private static <T> T decode(Codec<T> codec, Tag tag) {
        return codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static <T> int roundTrip(StreamCodec<ByteBuf, T> codec, T value, String label) {
        ByteBuf buffer = Unpooled.buffer();
        codec.encode(buffer, value);
        int size = buffer.readableBytes();
        T decoded = codec.decode(buffer);
        require(decoded.equals(value), label + " changed in transit");
        require(buffer.readableBytes() == 0, label + " left " + buffer.readableBytes() + " unread bytes");
        return size;
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
