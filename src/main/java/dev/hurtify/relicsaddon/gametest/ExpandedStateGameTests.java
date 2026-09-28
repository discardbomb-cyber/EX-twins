package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldImpactHistory;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;
import java.util.List;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ExpandedStateGameTests {
    @GameTest(template = "test_room")
    public static void shieldWireFormatIsCompactAndRetainsFiveThousandHp(GameTestHelper helper) {
        var state = ShieldStackState.DEFAULT.withCellsAndBuffer(ShieldStackState.DEFAULT.cells(), 5000, List.of(), -1)
                .damageLocalCell(419, 5, 5, 10);
        var buffer = Unpooled.buffer();
        try {
            ShieldStackState.STREAM_CODEC.encode(buffer, state);
            helper.assertTrue(buffer.readableBytes() == 445, "420 HP cells and 5000 shared HP fit in 445 bytes");
            helper.assertTrue(state.equals(ShieldStackState.STREAM_CODEC.decode(buffer)) && buffer.readableBytes() == 0,
                    "Compact state preserves every HP value exactly");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void volleysHaveBoundedExactPackets(GameTestHelper helper) {
        var shot = new HiveCombatState.Shot(249, 300, 3, 12345678.125, 64.5, -128.125, 12345680.5, 65, -129.25, 303);
        var state = new HiveCombatState(true, 19, 299, 12345680.5, 64, -129.25, java.util.Collections.nCopies(100, shot));
        var buffer = Unpooled.buffer();
        try {
            HiveCombatState.STREAM_CODEC.encode(buffer, state);
            helper.assertTrue(buffer.readableBytes() < 6700, "Maximum volley has bounded binary size without repeated NBT field names");
            helper.assertTrue(state.equals(HiveCombatState.STREAM_CODEC.decode(buffer)), "World positions retain double precision");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void hiveWireFormatRetainsTwoHundredFiftyDisabledUnits(GameTestHelper helper) {
        var units = new java.util.ArrayList<HiveStackState.Unit>(HiveType.MAX_DRONES);
        for (int index = 0; index < HiveType.MAX_DRONES; index++) {
            units.add(new HiveStackState.Unit(index == 249 ? 1000 : index, 1_000_000L + index,
                    2_000_000L + index, index == 249 ? .25F : 0, index == 249 ? -.5F : 0,
                    index == 249 ? .75F : 0, 3_000_000L + index));
        }
        var state = new HiveStackState(false, units);
        var buffer = Unpooled.buffer();
        try {
            HiveStackState.STREAM_CODEC.encode(buffer, state);
            helper.assertTrue(buffer.readableBytes() == 9502,
                    "Disabled 250-unit hive state has a fixed compact 9502-byte packet");
            var decoded = HiveStackState.STREAM_CODEC.decode(buffer);
            var last = decoded.units().get(249);
            helper.assertTrue(decoded.equals(state) && !decoded.enabled() && decoded.units().size() == 250
                            && last.hp() == 1000 && last.readyAt() == 1_000_249L && last.lastHit() == 2_000_249L
                            && last.x() == .25F && last.y() == -.5F && last.z() == .75F
                            && last.attackReadyAt() == 3_000_249L && buffer.readableBytes() == 0,
                    "Hive packet roundtrip retains the last unit's health, timers and direction");
        } finally { buffer.release(); }

        var oversized = Unpooled.buffer();
        try {
            oversized.writeBoolean(true).writeByte(251);
            boolean rejected = false;
            try {
                HiveStackState.STREAM_CODEC.decode(oversized);
            } catch (io.netty.handler.codec.DecoderException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Hive decoder rejects a 251-unit network payload");
        } finally { oversized.release(); }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void sameTickImpactsKeepIndependentWaves(GameTestHelper helper) {
        var first = new ShieldImpact(new Vec3(0, 0, 1), 10, 0, 3, false);
        var second = new ShieldImpact(new Vec3(1, 0, 0), 10, 1, 2, false).atDistance(.75);
        var history = ShieldImpactHistory.EMPTY.append(first).append(second);
        helper.assertTrue(history.impacts().equals(List.of(first, second)), "Two impacts in one tick survive network collection");
        for (int index = 0; index < 20; index++) history = history.append(new ShieldImpact(new Vec3(0, 1, 0), 11 + index, 0, 1, false));
        helper.assertTrue(history.impacts().size() == 12, "Visual history cannot grow beyond twelve wave fronts");
        var expired = history.append(new ShieldImpact(new Vec3(0, 1, 0), 100, 0, 1, false));
        helper.assertTrue(expired.impacts().size() == 1, "Old wave events expire independently of saved HP");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void shieldCommandsAreSelfServiceAndCannotRefillHp(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack shield = new ItemStack(ModItems.MANA_SHIELD.get());
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, shield);
        RelicRuntime.ability(player, shield).getResearchData().complete();
        RelicRuntime.ability(player, shield).setLevel(10);
        var damaged = ShieldStackState.DEFAULT.routeCellDamage(3, 200, 200, 10);
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), damaged);
        var source = player.createCommandSourceStack().withPermission(0);
        var commands = helper.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(source, "relics_addon shield_radius 5");
        commands.performPrefixedCommand(source, "relics_addon shield_coverage all");
        helper.assertTrue(ShieldParameters.radius(player, shield) == 5 && ShieldParameters.settings(shield).coverage().equals("all"),
                "Non-operator can set own shield options");
        commands.performPrefixedCommand(source, "relics_addon shield_radius 13");
        helper.assertTrue(ShieldParameters.radius(player, shield) == 5 && damaged.equals(shield.get(ModDataComponents.SHIELD_STACK_STATE.get())),
                "Out-of-range setting is rejected and successful settings never heal");
        helper.succeed();
    }
}
