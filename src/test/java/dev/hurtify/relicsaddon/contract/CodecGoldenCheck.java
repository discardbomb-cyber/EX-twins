package dev.hurtify.relicsaddon.contract;

import com.mojang.serialization.Codec;
import dev.hurtify.relicsaddon.adapter.out.persistence.LegacyDroneStackState;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveSupportState;
import dev.hurtify.relicsaddon.network.OpenDevicePayload;
import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.shield.ShieldCellMove;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldImpactHistory;
import dev.hurtify.relicsaddon.shield.ShieldSettings;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * Byte and NBT golden master of every saved or synced form ({@code golden/codecs.txt}): one line per
 * fixture, {@code label<TAB>SNBT|-|ERROR:<Class>:<message><TAB>stream-hex|-|ERROR:<Class>}.
 *
 * <ul>
 *   <li>A value fixture shows the value's NBT (sorted-key SNBT) and wire bytes. When decoding those
 *   forms and encoding again gives anything else, a {@code "<label> decoded"} line shows the result.</li>
 *   <li>An NBT or stream input fixture (old saves, malformed packets) shows the decoded value in both
 *   forms, or the error of the decode in its own column and {@code -} in the other.</li>
 *   <li>{@code -} also marks a component without a persistent codec.</li>
 * </ul>
 *
 * Codec constants are referenced directly, never through ModDataComponents. {@code --record} rewrites the golden.
 */
public final class CodecGoldenCheck {
    private final List<String> lines = new ArrayList<>();

    /** One component's wire form. */
    private interface Wire<T> {
        byte[] encode(T value);
        T decode(byte[] bytes);
    }

    public static void main(String[] args) throws IOException {
        Path golden = Path.of(args[0]);
        boolean record = args.length > 1 && args[1].equals("--record");
        CodecGoldenCheck check = new CodecGoldenCheck();
        check.hive();
        check.shield();
        check.device();
        if (record) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, check.lines, StandardCharsets.UTF_8);
            System.out.println("Codec golden recorded: " + check.lines.size() + " lines");
            return;
        }
        List<String> expected = Files.readAllLines(golden, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        for (int index = 0; index < Math.max(expected.size(), check.lines.size()); index++) {
            String want = index < expected.size() ? expected.get(index) : "<none>";
            String got = index < check.lines.size() ? check.lines.get(index) : "<none>";
            if (!want.equals(got)) problems.add("line " + (index + 1) + "\n  golden: " + shorten(want) + "\n  now:    " + shorten(got));
        }
        problems.forEach(System.out::println);
        if (!problems.isEmpty()) throw new AssertionError(problems.size() + " codec golden lines differ; saved and synced forms are frozen");
        System.out.println("Codec golden: " + check.lines.size() + " saved and synced forms unchanged");
    }

    private void hive() {
        Wire<HiveSettings> settings = wire(HiveSettings.STREAM_CODEC);
        value("hive_settings/DEFAULT", HiveSettings.CODEC, settings, HiveSettings.DEFAULT);
        for (AttackMode mode : AttackMode.values()) value("hive_settings/37 " + mode, HiveSettings.CODEC, settings, new HiveSettings(37, mode));
        value("hive_settings/750 CONTAINMENT", HiveSettings.CODEC, settings, new HiveSettings(750, AttackMode.CONTAINMENT));
        nbtInput("hive_settings/nbt IntTag 12", HiveSettings.CODEC, settings, IntTag.valueOf(12));
        streamInput("hive_settings/stream healers 751", HiveSettings.CODEC, settings, new byte[] {0x02, (byte) 0xEF, 0x01});

        Wire<HiveStackState> swarm = wire(HiveStackState.STREAM_CODEC);
        value("hive_stack_state/DEFAULT", HiveStackState.CODEC, swarm, HiveStackState.DEFAULT);
        value("hive_stack_state/12 fresh units", HiveStackState.CODEC, swarm,
                new HiveStackState(true, Collections.nCopies(12, HiveStackState.Unit.fresh())));
        // The 750-drone mix of NetworkCodecCheck: every ninth drone hit, rebuilding and pacing a mend.
        List<HiveStackState.Unit> units = new ArrayList<>();
        for (int index = 0; index < HiveType.MAX_DRONES; index++) {
            units.add(index % 9 == 0 ? new HiveStackState.Unit(index % 4, 1_000_000L + index, 2_000_000L + index, 3_000_000L + index)
                    : HiveStackState.Unit.fresh());
        }
        value("hive_stack_state/750 mix", HiveStackState.CODEC, swarm, new HiveStackState(true, units));
        CompoundTag legacy = new CompoundTag();
        legacy.putBoolean("enabled", true);
        ListTag legacyUnits = new ListTag();
        for (int index = 0; index < 5; index++) {
            CompoundTag unit = new CompoundTag();
            unit.putInt("hp", index == 2 ? 0 : 35);
            unit.putLong("ready_at", index == 2 ? 900 : 0);
            unit.putLong("last_hit", -1);
            unit.putFloat("x", 0);
            unit.putFloat("y", 0);
            unit.putFloat("z", 0);
            legacyUnits.add(unit);
        }
        legacy.put("units", legacyUnits);
        nbtInput("hive_stack_state/nbt legacy units", HiveStackState.CODEC, swarm, legacy);
        streamInput("hive_stack_state/stream count 751", HiveStackState.CODEC, swarm, new byte[] {0x01, (byte) 0xEF, 0x05});

        // The combat state is synced only (S3 dropped its unused NBT codec), so it has no NBT column.
        Wire<HiveCombatState> combat = wire(HiveCombatState.STREAM_CODEC);
        value("hive_combat_state/DEFAULT", null, combat, HiveCombatState.DEFAULT);
        List<HiveCombatState.Shot> shots = new ArrayList<>();
        for (int index = 0; index < HiveCombatState.MAX_SHOTS; index++) {
            shots.add(new HiveCombatState.Shot(HiveType.MAX_DRONES - 1 - index, 5_000L + index, 4 + index % 7, 1, 2, 3, 4, 5, 6, 5_010L + index));
        }
        for (AttackMode mode : AttackMode.values()) {
            value("hive_combat_state/100 shots " + mode, null, combat, new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, mode, 44, shots));
        }
        // 101 shots cannot be built (the record keeps 100), so the packet is patched: count byte 101 and one more shot.
        byte[] none = combat.encode(new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, AttackMode.BARRAGE, 44, List.of()));
        byte[] full = combat.encode(new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, AttackMode.BARRAGE, 44, shots));
        byte[] one = combat.encode(new HiveCombatState(true, 42, 77L, 1.5, 2.5, 3.5, AttackMode.BARRAGE, 44, shots.subList(0, 1)));
        byte[] oversized = concat(full, Arrays.copyOfRange(one, none.length, one.length));
        oversized[none.length - 1] = (byte) (HiveCombatState.MAX_SHOTS + 1);
        streamInput("hive_combat_state/stream 101 shots", null, combat, oversized);

        Wire<HiveSupportState> support = wire(HiveSupportState.STREAM_CODEC);
        value("hive_support_state/DEFAULT", null, support, HiveSupportState.DEFAULT);
        value("hive_support_state/true 123456789", null, support, new HiveSupportState(true, 123456789L));
    }

    private void shield() {
        Wire<ShieldSettings> settings = wire(ShieldSettings.STREAM_CODEC);
        value("shield_settings/DEFAULT", ShieldSettings.CODEC, settings, ShieldSettings.DEFAULT);
        value("shield_settings/5.5 owner", ShieldSettings.CODEC, settings, new ShieldSettings(5.5, "owner"));
        value("shield_settings/24 all", ShieldSettings.CODEC, settings, new ShieldSettings(24, "all"));
        nbtInput("shield_settings/nbt {}", ShieldSettings.CODEC, settings, new CompoundTag());
        CompoundTag bogus = new CompoundTag();
        bogus.putString("coverage", "bogus");
        nbtInput("shield_settings/nbt coverage bogus", ShieldSettings.CODEC, settings, bogus);

        Wire<ShieldStackState> state = wire(ShieldStackState.STREAM_CODEC);
        value("shield_stack_state/DEFAULT", ShieldStackState.CODEC, state, ShieldStackState.DEFAULT);
        ShieldStackState damaged = ShieldStackState.DEFAULT.damageLocalCell(5, 12, 3.5F, 1_000)
                .damageLocalCell(17, 4, 2.25F, 1_001).damageLocalCell(203, 7, 1.5F, 1_002);
        damaged = damaged.withCellsAndBuffer(damaged.cells(), 100,
                List.of(new ShieldCellMove(40, 5), new ShieldCellMove(41, 17), new ShieldCellMove(300, 203)), 77);
        value("shield_stack_state/damaged", ShieldStackState.CODEC, state, damaged);
        CompoundTag sectors = legacyShield();
        sectors.putInt("front", 3);
        sectors.putInt("left", 12);
        sectors.putInt("right", 7);
        sectors.putInt("back", 0);
        nbtInput("shield_stack_state/nbt legacy sectors", ShieldStackState.CODEC, state, sectors);
        CompoundTag legacyCells = legacyShield();
        legacyCells.put("cells", new IntArrayTag(cells(42)));
        ListTag moves = new ListTag();
        moves.add(move(1, 2));
        moves.add(move(40, 41));
        legacyCells.put("moves", moves);
        legacyCells.putInt("sharedBuffer", 250);
        legacyCells.putLong("gatherTime", 880L);
        nbtInput("shield_stack_state/nbt legacy 42 cells 2 moves", ShieldStackState.CODEC, state, legacyCells);
        CompoundTag odd = legacyShield();
        odd.put("cells", new IntArrayTag(cells(41)));
        nbtInput("shield_stack_state/nbt 41 cells", ShieldStackState.CODEC, state, odd);
        // Four moves cannot be built either: the packet of the damaged state gets count 4 and one more move.
        byte[] three = state.encode(damaged);
        byte[] four = concat(three, new byte[] {0x00, 0x01, 0x00, 0x02});
        four[three.length - 1 - 3 * 4] = 4;
        streamInput("shield_stack_state/stream 4 moves", ShieldStackState.CODEC, state, four);

        Wire<ShieldImpact> impact = wire(ShieldImpact.STREAM_CODEC);
        value("shield_impact/absorbed", ShieldImpact.CODEC, impact, new ShieldImpact(new Vec3(1, 0, 0), 99L, 1, 6, true, List.of(3), 1.5, 0));
        value("shield_impact/strike", ShieldImpact.CODEC, impact, ShieldImpact.strike(new Vec3(0, 0, 1), 1_234L, 2, 4.5F, .3F));
        value("shield_impact/normal .3 .4 .5", ShieldImpact.CODEC, impact, new ShieldImpact(new Vec3(.3, .4, .5), 7L, 0, 2F, false));
        value("shield_impact/zero normal", ShieldImpact.CODEC, impact, new ShieldImpact(new Vec3(0, 0, 0), 8L, 3, 1F, false));
        value("shield_impact/NaN distance", ShieldImpact.CODEC, impact,
                new ShieldImpact(new Vec3(0, 1, 0), 9L, 2, 3F, true, List.of(10, 11), Double.NaN, 0));

        Wire<ShieldImpactHistory> history = wire(ShieldImpactHistory.STREAM_CODEC);
        value("shield_impacts/EMPTY", ShieldImpactHistory.CODEC, history, ShieldImpactHistory.EMPTY);
        List<ShieldImpact> impacts = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            impacts.add(new ShieldImpact(new Vec3(index - 6, 1, 7 - index), 100L + index, index % 4, 1.5F + index, index % 5 == 0,
                    index % 5 == 0 ? List.of(index * 30) : List.of(), index % 3 == 0 ? -1 : index * .75, index % 4 == 1 ? .25F * index : 0));
        }
        value("shield_impacts/12", ShieldImpactHistory.CODEC, history, new ShieldImpactHistory(impacts.subList(0, 12)));
        value("shield_impacts/13", ShieldImpactHistory.CODEC, history, new ShieldImpactHistory(impacts));
    }

    private void device() {
        Wire<DeviceEnergy> energy = wire(DeviceEnergy.STREAM_CODEC);
        value("device_energy/EMPTY", DeviceEnergy.CODEC, energy, DeviceEnergy.EMPTY);
        for (DeviceEnergy.ManaSource source : DeviceEnergy.ManaSource.values()) {
            value("device_energy/1000000 100000 false true " + source, DeviceEnergy.CODEC, energy,
                    new DeviceEnergy(1_000_000, 100_000, false, true, source));
        }
        CompoundTag bare = new CompoundTag();
        bare.putInt("rf", 500);
        bare.putInt("mana", 20);
        nbtInput("device_energy/nbt without optional fields", DeviceEnergy.CODEC, energy, bare);
        CompoundTag bogus = new CompoundTag();
        bogus.putInt("rf", 1);
        bogus.putInt("mana", 2);
        bogus.putBoolean("rf_on", false);
        bogus.putBoolean("mana_on", true);
        bogus.putString("source", "bogus");
        nbtInput("device_energy/nbt source bogus", DeviceEnergy.CODEC, energy, bogus);

        Wire<DeviceProgression> progression = registryWire(DeviceProgression.STREAM_CODEC);
        value("device_progression/DEFAULT", DeviceProgression.CODEC, progression, DeviceProgression.DEFAULT);
        value("device_progression/100 3 2 0b10_01_11", DeviceProgression.CODEC, progression, new DeviceProgression(100, 3, 2, 0b10_01_11));
        streamInput("device_progression/stream level 99", DeviceProgression.CODEC, progression, new byte[] {0, 99, 0, 0});
        CompoundTag tooHigh = new CompoundTag();
        tooHigh.putInt("experience", 0);
        tooHigh.putInt("level", 11);
        tooHigh.putInt("points", 0);
        tooHigh.putInt("upgrades", 0);
        nbtInput("device_progression/nbt level 11", DeviceProgression.CODEC, progression, tooHigh);

        value("instance_id/0f8fad5b-d9cb-469f-a165-70867728950e", Codec.STRING, wire(ByteBufCodecs.STRING_UTF8),
                "0f8fad5b-d9cb-469f-a165-70867728950e");

        Wire<LegacyDroneStackState> drone = wire(LegacyDroneStackState.STREAM_CODEC);
        value("drone_stack_state/DEFAULT", LegacyDroneStackState.CODEC, drone, LegacyDroneStackState.DEFAULT);
        value("drone_stack_state/false 42 1.5", LegacyDroneStackState.CODEC, drone, new LegacyDroneStackState(false, 42, 1.5F));

        Wire<OpenDevicePayload> open = registryWire(OpenDevicePayload.STREAM_CODEC);
        value("open_device/true 0", null, open, new OpenDevicePayload(true, 0));
        value("open_device/false 40", null, open, new OpenDevicePayload(false, 40));
    }

    private static CompoundTag legacyShield() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enabled", true);
        tag.putInt("front", 12);
        tag.putInt("left", 12);
        tag.putInt("right", 12);
        tag.putInt("back", 12);
        tag.putInt("lastHitPanel", 2);
        tag.putFloat("lastAbsorbed", 4.5F);
        tag.putLong("lastActiveGameTime", 900L);
        return tag;
    }

    private static int[] cells(int count) {
        int[] cells = new int[count];
        for (int index = 0; index < count; index++) cells[index] = index % 13;
        return cells;
    }

    private static CompoundTag move(int from, int to) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("from", from);
        tag.putInt("to", to);
        return tag;
    }

    private <T> void value(String label, Codec<T> codec, Wire<T> wire, T value) {
        String nbt = codec == null ? "-" : nbt(() -> codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow());
        String stream = wire == null ? "-" : stream(() -> wire.encode(value));
        lines.add(line(label, nbt, stream));
        String nbtBack = codec == null ? "-" : nbt(() -> codec.encodeStart(NbtOps.INSTANCE,
                codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()).getOrThrow()).getOrThrow());
        String streamBack = wire == null ? "-" : stream(() -> wire.encode(wire.decode(wire.encode(value))));
        if (!nbtBack.equals(nbt) || !streamBack.equals(stream)) lines.add(line(label + " decoded", nbtBack, streamBack));
    }

    private <T> void nbtInput(String label, Codec<T> codec, Wire<T> wire, Tag input) {
        T decoded;
        try {
            decoded = codec.parse(NbtOps.INSTANCE, input).getOrThrow();
        } catch (RuntimeException error) {
            lines.add(line(label, "ERROR:" + error.getClass().getName() + ":" + error.getMessage(), "-"));
            return;
        }
        lines.add(line(label, nbt(() -> codec.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow()), wire == null ? "-" : stream(() -> wire.encode(decoded))));
    }

    private <T> void streamInput(String label, Codec<T> codec, Wire<T> wire, byte[] input) {
        T decoded;
        try {
            decoded = wire.decode(input);
        } catch (RuntimeException error) {
            lines.add(line(label, "-", "ERROR:" + error.getClass().getName()));
            return;
        }
        lines.add(line(label, codec == null ? "-" : nbt(() -> codec.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow()), stream(() -> wire.encode(decoded))));
    }

    private static String nbt(Supplier<Tag> encode) {
        try {
            return encode.get().toString();
        } catch (RuntimeException error) {
            return "ERROR:" + error.getClass().getName() + ":" + error.getMessage();
        }
    }

    private static String stream(Supplier<byte[]> encode) {
        try {
            return HexFormat.of().formatHex(encode.get());
        } catch (RuntimeException error) {
            return "ERROR:" + error.getClass().getName();
        }
    }

    private static String line(String label, String nbt, String stream) {
        return label + "\t" + clean(nbt) + "\t" + clean(stream);
    }

    private static String clean(String column) {
        return column.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static <T> Wire<T> wire(StreamCodec<ByteBuf, T> codec) {
        return new Wire<>() {
            @Override public byte[] encode(T value) {
                ByteBuf buffer = Unpooled.buffer();
                codec.encode(buffer, value);
                return bytes(buffer);
            }

            @Override public T decode(byte[] bytes) {
                return codec.decode(Unpooled.wrappedBuffer(bytes));
            }
        };
    }

    private static <T> Wire<T> registryWire(StreamCodec<RegistryFriendlyByteBuf, T> codec) {
        return new Wire<>() {
            @Override public byte[] encode(T value) {
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                codec.encode(buffer, value);
                return bytes(buffer);
            }

            @Override public T decode(byte[] bytes) {
                return codec.decode(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY));
            }
        };
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String shorten(String line) {
        return line.length() <= 400 ? line : line.substring(0, 400) + "... (" + line.length() + " chars)";
    }

    private CodecGoldenCheck() { }
}
