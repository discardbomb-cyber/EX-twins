package dev.hurtify.relicsaddon.adapter.out.persistence;

import dev.hurtify.relicsaddon.domain.hive.*;

import com.mojang.serialization.Codec;
import dev.hurtify.relicsaddon.domain.energy.DeviceEnergy;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import dev.hurtify.relicsaddon.domain.math.Vec3d;

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
        require(restingBytes < HiveType.MAX_DRONES + 30, "a resting full swarm must fit in about a byte per drone, took " + restingBytes);

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
        require(roundTrip(HiveCodecs.STACK_STATE_STREAM, settled, "settled swarm") < HiveType.MAX_DRONES + 60, "a settled swarm is back to about a byte per drone");
        for (int lane = 0; lane < slots; lane++) {
            if (lane == 3) continue;
            require(HiveFlightPlan.of(HiveType.RF, HiveType.MAX_DRONES, HiveSettings.DEFAULT).wing(AttackMode.BARRAGE).occupant(settled.units(), lane, 6_000) == lane,
                    "a settled lane is flown by its first drone");
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

        // Settings: every mode's drones travel and save, with or without a notice, counts as variable-length
        // numbers; old saves held one mode for every fighter, or only a healer count.
        HiveSettings full = new HiveSettings(HiveType.MAX_DRONES, HiveType.MAX_DRONES, HiveType.MAX_DRONES, HiveType.MAX_DRONES);
        require(roundTrip(HiveCodecs.SETTINGS_STREAM, full, "full settings") == 4 * 2 + 1, "settings counts travel as variable-length numbers");
        require(roundTrip(HiveCodecs.SETTINGS_STREAM, new HiveSettings(0, 60, 120, 70), "small settings") == 4 + 1, "small counts take a byte each");
        for (AttackMode mode : AttackMode.values()) for (HiveSettings.Notice.Kind kind : HiveSettings.Notice.Kind.values()) {
            HiveSettings settings = new HiveSettings(37, 60, 0, 1_999, new HiveSettings.Notice(kind, mode, 300, 1_024));
            roundTrip(HiveCodecs.SETTINGS_STREAM, settings, "settings " + mode + " " + kind);
            require(decode(HiveCodecs.SETTINGS, encode(HiveCodecs.SETTINGS, settings)).equals(settings), "settings " + mode + " " + kind + " did not survive a save");
        }
        require(decode(HiveCodecs.SETTINGS, encode(HiveCodecs.SETTINGS, new HiveSettings(3, 16, 0, 18))).equals(new HiveSettings(3, 16, 0, 18)),
                "settings without a notice survive a save");
        require(decode(HiveCodecs.SETTINGS, IntTag.valueOf(12)).equals(HiveSettings.legacy(12, AttackMode.BARRAGE)), "an old healer count must still load");
        for (AttackMode mode : AttackMode.values()) {
            CompoundTag single = new CompoundTag();
            single.putInt("healers", 5);
            single.putString("mode", mode.id());
            require(decode(HiveCodecs.SETTINGS, single).equals(HiveSettings.legacy(5, mode)), "an old single-mode save loads as that mode (" + mode + ")");
        }
        CompoundTag barrageSave = new CompoundTag();
        barrageSave.putInt("healers", 0);
        require(decode(HiveCodecs.SETTINGS, barrageSave).equals(HiveSettings.DEFAULT), "an old save that left out its default mode is a Barrage one");
        for (int[] bad : new int[][]{{-1}, {HiveType.MAX_DRONES + 1}}) {
            ByteBuf buffer = Unpooled.buffer();
            VarInt.write(buffer, 1);
            VarInt.write(buffer, bad[0]);
            boolean refused = false;
            try { HiveCodecs.SETTINGS_STREAM.decode(buffer); } catch (RuntimeException expected) { refused = true; }
            require(refused, "a settings packet with " + bad[0] + " drones in a mode is refused");
        }

        List<HiveCombatState.Shot> shots = new ArrayList<>();
        for (int index = 0; index < HiveCombatState.MAX_SHOTS; index++) {
            shots.add(new HiveCombatState.Shot(HiveType.MAX_DRONES - 1 - index, 5_000L + index, 4 + index % 7, 1, 2, 3, 4, 5, 6, 5_010L + index));
        }
        List<List<HiveCombatState.Wing>> wingSets = List.of(List.of(), List.of(new HiveCombatState.Wing(true, 77, 12345),
                new HiveCombatState.Wing(false, 0, 0), new HiveCombatState.Wing(true, 9_000_000_000L, -7)));
        for (List<HiveCombatState.Wing> wings : wingSets) {
            String mode = wings.size() + " wings";
            roundTrip(HiveCodecs.COMBAT_STATE_STREAM, new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, wings, 44, shots), "combat " + mode);
            List<HiveTarget> targets = new ArrayList<>(), previous = new ArrayList<>();
            for (int index = 0; index < HiveCombatState.MAX_TARGETS; index++) {
                targets.add(new HiveTarget(100 + index, index * 1.5, 64, -index, .6 + index * .1, 1.8));
                if (index % 2 == 0) previous.add(new HiveTarget(200 + index, -index, 70, index * 2.5, 1.2F, 2.9F));
            }
            HiveCombatState engaged = HiveCombatState.engage(targets, 77L, wings, 44).withShots(shots).retarget(targets, 90L, 3);
            roundTrip(HiveCodecs.COMBAT_STATE_STREAM, new HiveCombatState(true, 100, 77L, 0, 64, 0, wings, 44, shots, targets, previous, 90L, 2),
                    "combat with targets " + mode);
            roundTrip(HiveCodecs.COMBAT_STATE_STREAM, engaged, "retargeted combat " + mode);
            require(engaged.previous().equals(engaged.targets()) && engaged.retargetedAt() == 90L && engaged.changedAt() == 77L && engaged.previousHeld() == 3,
                    "a retarget keeps the fight's start and remembers the old targets and how many were held");
            require(decode(HiveCodecs.COMBAT_STATE, encode(HiveCodecs.COMBAT_STATE, engaged)).equals(engaged), "combat " + mode + " did not survive a save");
        }

        for (DeviceEnergy.ManaSource source : DeviceEnergy.ManaSource.values()) {
            roundTrip(DeviceCodecs.ENERGY_STREAM, new DeviceEnergy(1_000_000, 100_000, false, true, source), "battery " + source);
        }

        // Axis-aligned normals survive the constructor's re-normalisation bit for bit.
        roundTrip(ShieldCodecs.IMPACT_STREAM, new ShieldImpact(new Vec3d(1, 0, 0), 99L, 1, 6, true, List.of(3), 1.5, 0), "absorbed hit");
        ShieldImpact strike = ShieldImpact.strike(new Vec3d(0, 0, 1), 1_234L, 2, 4.5F, .3F);
        roundTrip(ShieldCodecs.IMPACT_STREAM, strike, "shield strike");
        require(strike.isStrike() && !new ShieldImpact(new Vec3d(0, 1, 0), 5L, 0, 2, false).isStrike(), "Only strikes are marked as strikes");
        // An Armageddon under way travels exactly, whichever hive fires it, its counters as variable-length
        // numbers; broken numbers never get into it.
        int armageddonBytes = roundTrip(HiveCodecs.ARMAGEDDON_STREAM,
                new ArmageddonState(HiveType.TWINS, 123_456_789L, new Vec3d(1.25, 70.5, -3), new Vec3d(-200.5, 63, 180.25), true), "armageddon");
        require(armageddonBytes < 62, "an Armageddon's counters travel as variable-length numbers, took " + armageddonBytes + " bytes");
        roundTrip(HiveCodecs.ARMAGEDDON_STREAM, new ArmageddonState(HiveType.RF, 42L, new Vec3d(1, 80, 2), new Vec3d(1, 95, 20), ArmageddonState.Face.DOWN, 17.5, true),
                "rf armageddon at a ceiling");
        ArmageddonState roomless = new ArmageddonState(HiveType.RF, 0, Vec3d.ZERO, Vec3d.ZERO, null, Double.NaN, false);
        require(roomless.face() == ArmageddonState.Face.UP && roomless.room() == ArmageddonState.MOST_ROOM
                && new ArmageddonState(HiveType.RF, 0, Vec3d.ZERO, Vec3d.ZERO, ArmageddonState.Face.EAST, -4, false).room() == 0,
                "a shot lands on a real face with room from none to the most that matters");
        roundTrip(HiveCodecs.ARMAGEDDON_STREAM, new ArmageddonState(HiveType.MANA, 987_654_321L, new Vec3d(-4, 80.5, 12), new Vec3d(30, 64, -220.75), true), "mana armageddon");
        roundTrip(HiveCodecs.ARMAGEDDON_STREAM, new ArmageddonState(HiveType.TWINS, 0, Vec3d.ZERO, new Vec3d(0, -64, 0), false), "armageddon unlinked");
        roundTrip(HiveCodecs.ARMAGEDDON_STREAM, new ArmageddonState(HiveType.RF, 555_555L, new Vec3d(8, 72, -3.5), new Vec3d(-40.5, 63, 190), true), "rf armageddon");
        ArmageddonState broken = new ArmageddonState(HiveType.RF, -5, new Vec3d(Double.NaN, 0, 0), new Vec3d(0, Double.POSITIVE_INFINITY, 0), false);
        require(broken.origin().equals(Vec3d.ZERO) && broken.target().equals(Vec3d.ZERO), "an Armageddon keeps only finite places");
        require(broken.type() == HiveType.RF && new ArmageddonState(null, 0, Vec3d.ZERO, Vec3d.ZERO, false).type() == HiveType.TWINS,
                "every hive family fires an Armageddon of its own; a missing family is taken for Twins");
        roundTrip(HiveCodecs.ARMAGEDDON_STREAM, broken, "armageddon started before the world's first tick");
        require(broken.running(100) && broken.age(100) == 105, "a shot with a head start in a young world is just as far along");
        ByteBuf stray = Unpooled.buffer();
        VarInt.write(stray, 99);
        VarLong.write(stray, 7);
        for (int coordinate = 0; coordinate < 6; coordinate++) stray.writeDouble(coordinate);
        VarInt.write(stray, 77);
        stray.writeFloat(Float.NaN);
        stray.writeBoolean(false);
        ArmageddonState strayState = HiveCodecs.ARMAGEDDON_STREAM.decode(stray);
        require(strayState.type() == HiveType.TWINS && stray.readableBytes() == 0, "a hive family from nowhere reads as Twins");
        require(strayState.room() == ArmageddonState.MOST_ROOM && strayState.face() != null, "a face and room from nowhere read as a real face with all the room");
        for (HiveType type : HiveType.values()) {
            int end = ArmageddonTimeline.of(type).end();
            ArmageddonState running = new ArmageddonState(type, 1_000, Vec3d.ZERO, new Vec3d(0, 0, 60), false);
            require(!running.running(999) && running.running(1_000) && running.running(1_000 + end - 1) && !running.running(1_000 + end),
                    "a " + type + " Armageddon runs from its start until its drones are home");
        }
        roundTrip(dev.hurtify.relicsaddon.network.ArmageddonPayloads.Blast.STREAM_CODEC,
                new dev.hurtify.relicsaddon.network.ArmageddonPayloads.Blast(HiveType.MANA, new net.minecraft.world.phys.Vec3(-12.5, 63, 400.25), new net.minecraft.world.phys.Vec3(3, 75.5, 250),
                        net.minecraft.core.Direction.UP, 256, 1_234_567L), "mana blast");
        roundTrip(dev.hurtify.relicsaddon.network.ArmageddonPayloads.Blast.STREAM_CODEC,
                new dev.hurtify.relicsaddon.network.ArmageddonPayloads.Blast(HiveType.TWINS, net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(0, 6.5, 1.5), net.minecraft.core.Direction.UP, 256, -3L),
                "twins blast in a young world");
        roundTrip(dev.hurtify.relicsaddon.network.ArmageddonPayloads.Blast.STREAM_CODEC,
                new dev.hurtify.relicsaddon.network.ArmageddonPayloads.Blast(HiveType.RF, new net.minecraft.world.phys.Vec3(100.5, 64, -80), new net.minecraft.world.phys.Vec3(4, 80, 2), net.minecraft.core.Direction.WEST, 12.25F,
                        9_876_543L), "rf blast into a wall");
        System.out.println("Network codecs: " + HiveType.MAX_DRONES + "-drone swarm (" + swarmBytes + " bytes, " + restingBytes + " at rest), old saves, "
                + "settings, combat, batteries, shield impacts and Armageddon round-trip exactly");
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
