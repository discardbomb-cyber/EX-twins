package dev.hurtify.relicsaddon.gametest;

import com.mojang.serialization.JsonOps;
import dev.hurtify.relicsaddon.drone.HiveOrbit;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class HiveGameTests {
    @GameTest(template = "field_arena")
    public static void allHivesStopRealArrowsThroughTickDispatch(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        helper.getLevel().addNewPlayer(player);
        for (HiveType type : HiveType.values()) {
            clearHives(player);
            ItemStack hive = equip(player, type.role, 0);
            Arrow arrow = arrow(helper, player, 3, 2, 3);
            helper.assertTrue(helper.getLevel().addFreshEntity(arrow), "Arrow must enter the real test level");
            HiveController.onEntityTick(new EntityTickEvent.Pre(arrow));
            HiveStackState state = state(hive);
            helper.assertTrue(arrow.isRemoved(), type + " hive stops a real incoming arrow");
            helper.assertTrue(state.units().size() == type.initialCount, type + " prepares its initial swarm size");
            helper.assertTrue(state.units().stream().mapToInt(HiveStackState.Unit::hp).sum()
                    == type.initialCount * type.initialHealth - 6, type + " pays the arrow impact exactly once");
            arrow.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void unitsHaveIndependentHpCooldownAndRepair(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        ItemStack hive = equip(player, RelicRole.MANA_HIVE, 0);
        long now = helper.getLevel().getGameTime();
        List<Integer> order = manaFrontOrder(now);
        int first = order.getFirst(), second = order.get(1);
        var units = new ArrayList<HiveStackState.Unit>(HiveType.MANA.initialCount);
        for (int index = 0; index < HiveType.MANA.initialCount; index++) {
            units.add(new HiveStackState.Unit(0, now + 10_000, now, 0, 0, 0));
        }
        units.set(first, new HiveStackState.Unit(1, 0, -1, 0, 0, 0));
        units.set(second, new HiveStackState.Unit(8, 0, -1, 0, 0, 0));
        hive.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(true, units));
        Arrow arrow = arrow(helper, player, 3, 2, 1);

        helper.assertTrue(HiveController.intercept(arrow, player) && arrow.isRemoved(), "The two ready front units pay a two-point arrow");
        HiveStackState damaged = state(hive);
        HiveStackState.Unit destroyed = damaged.units().get(first);
        HiveStackState.Unit survivor = damaged.units().get(second);
        helper.assertTrue(damaged.units().stream().mapToInt(HiveStackState.Unit::hp).sum() == 7,
                "Damage is divided between independent unit HP pools");
        helper.assertTrue(destroyed.hp() == 0 && survivor.hp() == 7,
                "The orbit-selected first unit dies before the next unit pays the remainder");
        helper.assertTrue(survivor.readyAt() == now + 8 && !survivor.ready(now + 7) && survivor.ready(now + 8),
                "Surviving units use the eight-tick intercept cooldown");
        helper.assertTrue(destroyed.readyAt() == now + HiveType.MANA.initialCooldown,
                "Destroyed units use the hive rebuild cooldown");

        HiveStackState rebuilt = damaged.prepare(HiveType.MANA.initialCount, HiveType.MANA.initialHealth, destroyed.readyAt(), true);
        helper.assertTrue(rebuilt.units().get(first).hp() == HiveType.MANA.initialHealth,
                "Destroyed unit regenerates after its cooldown");
        HiveStackState quiet = new HiveStackState(true, List.of(new HiveStackState.Unit(4, 0, 0, 0, 0, 0)));
        helper.assertTrue(quiet.prepare(1, 8, 20, true).units().getFirst().hp() == 4,
                "A survivor does not repair before forty quiet ticks");
        helper.assertTrue(quiet.prepare(1, 8, 40, true).units().getFirst().hp() == 5,
                "A quiet survivor repairs one HP on the twentieth tick");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void hiveCountsHealthAndStateAreHardCapped(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (HiveType type : HiveType.values()) {
            ItemStack hive = equip(player, type.role, 0);
            helper.assertTrue(type.initialCount == 12, type + " starts with twelve drones");
            helper.assertTrue(HiveController.capacity(player, hive) == type.initialCount, type + " starts at its configured count");
            helper.assertTrue(HiveController.health(player, hive) == type.initialHealth, type + " starts at its configured health");
            RelicRuntime.ability(player, hive).setLevel(10);
            helper.assertTrue(HiveController.capacity(player, hive) == HiveType.MAX_DRONES, type + " count upgrades to two hundred fifty");
            helper.assertTrue(HiveController.health(player, hive) == type.maxHealth, type + " health reaches its level-ten target");
        }
        HiveStackState oversized = new HiveStackState(true, java.util.Collections.nCopies(255, HiveStackState.Unit.fresh(1)));
        helper.assertTrue(oversized.units().size() == HiveType.MAX_DRONES
                        && oversized.prepare(300, 1000, 0, false).units().size() == HiveType.MAX_DRONES,
                "Stored and prepared swarms cannot exceed two hundred fifty units per type");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void duplicateHivesDoNotMultiplyAndMixedTypesCoexist(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        var charms = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("charm").orElseThrow();
        if (charms.getSlots() < 4) charms.grow(4 - charms.getSlots());
        ItemStack firstRf = equip(player, RelicRole.RF_HIVE, 0);
        ItemStack duplicateRf = equip(player, RelicRole.RF_HIVE, 1);
        ItemStack mana = equip(player, RelicRole.MANA_HIVE, 2);
        ItemStack twins = equip(player, RelicRole.TWINS_HIVE, 3);
        List<HiveController.Equipped> active = HiveController.active(player);
        helper.assertTrue(active.size() == 3 && active.stream().filter(hive -> hive.type() == HiveType.RF).count() == 1,
                "Only the first equipped hive of each type is active");
        RelicRuntime.ability(player, firstRf).setLevel(10);
        RelicRuntime.ability(player, mana).setLevel(10);
        RelicRuntime.ability(player, twins).setLevel(10);
        helper.assertTrue(HiveType.MAX_DRONES == 250 && active.stream().mapToInt(hive -> HiveController.capacity(player, hive.stack())).sum() == 750,
                "All three functional hive types cap at seven hundred fifty active units per owner");

        Arrow arrow = arrow(helper, player, 3, 2, 2);
        helper.assertTrue(HiveController.intercept(arrow, player), "The first RF hive can intercept");
        helper.assertTrue(state(firstRf).units().size() == HiveType.MAX_DRONES
                        && state(duplicateRf).equals(HiveStackState.DEFAULT),
                "Duplicate RF hive receives neither capacity nor damage state");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void overflowingArrowSpendsAllHiveHpOnlyOnce(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        ItemStack hive = equip(player, RelicRole.RF_HIVE, 0);
        Arrow arrow = arrow(helper, player, 3, 2,
                (HiveType.RF.initialCount * HiveType.RF.initialHealth + 8) / 2.0);

        helper.assertTrue(HiveController.intercept(arrow, player), "Overflowing arrow reaches the hive");
        HiveStackState spent = state(hive);
        helper.assertTrue(!arrow.isRemoved() && spent.units().size() == HiveType.RF.initialCount
                        && spent.units().stream().allMatch(unit -> unit.hp() == 0)
                        && Math.abs(arrow.getBaseDamage() - 4) < 1e-7,
                "All hive HP is spent and only the unpaid arrow damage continues");
        helper.assertFalse(HiveController.intercept(arrow, player) || !state(hive).equals(spent),
                "The same overflow arrow cannot charge the hive a second time");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void highPriorityHiveEventPrecedesShield(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        helper.getLevel().addNewPlayer(player);
        ItemStack hive = equip(player, RelicRole.RF_HIVE, 0);
        ItemStack shield = equipShield(player, RelicRole.RF_SHIELD, 1);
        Arrow arrow = arrow(helper, player, 3, 2, 3);
        helper.assertTrue(helper.getLevel().addFreshEntity(arrow), "Arrow must enter the real event level");

        EntityTickEvent.Pre event = new EntityTickEvent.Pre(arrow);
        NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.isCanceled() && arrow.isRemoved() && !state(hive).units().isEmpty()
                        && shieldState(shield).equals(ShieldStackState.DEFAULT),
                "High-priority hive interception removes the arrow before the shield can pay");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void unsupportedTridentsAndOwnMissilesPassUnchanged(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        ItemStack hive = equip(player, RelicRole.RF_HIVE, 0);
        Arrow own = arrow(helper, player, 3, 2, 2);
        own.setOwner(player);
        ThrownTrident trident = new ThrownTrident(EntityType.TRIDENT, helper.getLevel());
        trident.setPos(player.position().add(0, ShieldField.CENTER_Y, 3));
        trident.setDeltaMovement(0, 0, -2);

        helper.assertFalse(HiveController.intercept(own, player) || ShieldProjectileInterceptor.threatens(own, player),
                "Own arrow passes the hive unchanged");
        helper.assertFalse(ShieldProjectileInterceptor.supported(trident) || HiveController.intercept(trident, player),
                "Unsupported trident passes the hive unchanged");
        helper.assertTrue(!own.isRemoved() && !trident.isRemoved() && state(hive).equals(HiveStackState.DEFAULT),
                "Ignored missiles do not consume hive state");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void friendlyDisabledUnequippedCosmeticAndLockedHivesDoNotPay(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        ItemStack hive = equip(player, RelicRole.RF_HIVE, 0);
        Arrow own = arrow(helper, player, 3, 2, 2);
        own.setOwner(player);
        helper.assertFalse(ShieldProjectileInterceptor.threatens(own, player) || HiveController.intercept(own, player),
                "Own projectiles are never hive threats");

        ServerPlayer teammate = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "hive-friendly-shooter"), ClientInformation.createDefault());
        PlayerTeam team = player.getScoreboard().addPlayerTeam("hive_test_friends");
        team.setAllowFriendlyFire(false);
        player.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team);
        player.getScoreboard().addPlayerToTeam(teammate.getScoreboardName(), team);
        try {
            Arrow friendly = arrow(helper, player, 3, 2, 2);
            friendly.setOwner(teammate);
            helper.assertFalse(ShieldProjectileInterceptor.threatens(friendly, player) || HiveController.intercept(friendly, player),
                    "Allied and PvP-disabled player projectiles are ignored");
        } finally {
            teammate.discard();
            player.getScoreboard().removePlayerTeam(team);
        }

        RelicRuntime.setEnabled(player, hive, false);
        helper.assertFalse(HiveController.intercept(arrow(helper, player, 3, 2, 2), player), "Disabled hive cannot intercept");
        RelicRuntime.setEnabled(player, hive, true);
        clearHives(player);
        helper.assertFalse(HiveController.intercept(arrow(helper, player, 3, 2, 2), player), "Unequipped hive cannot intercept");
        ItemStack cosmetic = new ItemStack(ModItems.MANA_HIVE.get());
        CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("charm").orElseThrow()
                .getCosmeticStacks().setStackInSlot(0, cosmetic);
        helper.assertTrue(HiveController.active(player).isEmpty()
                        && !HiveController.intercept(arrow(helper, player, 3, 2, 2), player),
                "Cosmetic hive is not a functional charm slot");
        ItemStack locked = new ItemStack(ModItems.TWINS_HIVE.get());
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, locked);
        helper.assertFalse(RelicRuntime.canOperate(player, locked)
                        || HiveController.intercept(arrow(helper, player, 3, 2, 2), player),
                "Research-locked hive cannot intercept");
        helper.assertTrue(state(hive).equals(HiveStackState.DEFAULT), "Ignored projectiles consume no hive state");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void hiveStateComponentSerializesAndClamps(GameTestHelper helper) {
        HiveStackState state = new HiveStackState(false, List.of(new HiveStackState.Unit(-1, -1, -2, 2, Float.NaN, -2)));
        HiveStackState.Unit unit = state.units().getFirst();
        helper.assertTrue(unit.hp() == 0 && unit.readyAt() == 0 && unit.lastHit() == -1
                        && unit.x() == 1 && unit.y() == 0 && unit.z() == -1,
                "Hive unit component values are bounded before serialization");
        var buffer = Unpooled.buffer();
        try {
            HiveStackState.STREAM_CODEC.encode(buffer, state);
            helper.assertTrue(state.equals(HiveStackState.STREAM_CODEC.decode(buffer)), "Hive component network roundtrip");
        } finally {
            buffer.release();
        }
        var encoded = HiveStackState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        helper.assertTrue(state.equals(HiveStackState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()),
                "Hive component persistent roundtrip");
        helper.succeed();
    }

    private static ServerPlayer fieldPlayer(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        player.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        return player;
    }

    private static ItemStack equip(ServerPlayer player, RelicRole role, int slot) {
        AutonomousRelicItem item = switch (role) {
            case RF_HIVE -> ModItems.RF_HIVE.get();
            case MANA_HIVE -> ModItems.MANA_HIVE.get();
            case TWINS_HIVE -> ModItems.TWINS_HIVE.get();
            default -> throw new IllegalArgumentException("Expected hive role: " + role);
        };
        ItemStack stack = new ItemStack(item);
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", slot, stack);
        RelicRuntime.ability(player, stack).setLevel(0);
        RelicRuntime.ability(player, stack).getResearchData().complete();
        return stack;
    }

    private static ItemStack equipShield(ServerPlayer player, RelicRole role, int slot) {
        AutonomousRelicItem item = switch (role) {
            case RF_SHIELD -> ModItems.RF_SHIELD.get();
            case MANA_SHIELD -> ModItems.MANA_SHIELD.get();
            case TWINS_SHIELD -> ModItems.TWINS_SHIELD.get();
            default -> throw new IllegalArgumentException("Expected shield role: " + role);
        };
        ItemStack stack = new ItemStack(item);
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", slot, stack);
        RelicRuntime.ability(player, stack).setLevel(0);
        RelicRuntime.ability(player, stack).getResearchData().complete();
        return stack;
    }

    private static void clearHives(ServerPlayer player) {
        var stacks = CuriosApi.getCuriosInventory(player).orElseThrow().getStacksHandler("charm").orElseThrow().getStacks();
        for (int slot = 0; slot < stacks.getSlots(); slot++) stacks.setStackInSlot(slot, ItemStack.EMPTY);
    }

    private static HiveStackState state(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
    }

    private static ShieldStackState shieldState(ItemStack shield) {
        return shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
    }

    private static List<Integer> manaFrontOrder(long now) {
        var order = new ArrayList<Integer>(HiveType.MANA.initialCount);
        for (int index = 0; index < HiveType.MANA.initialCount; index++) order.add(index);
        order.sort(Comparator.comparingDouble(index -> -HiveOrbit.at(index, HiveType.MANA.initialCount,
                HiveType.MANA.ordinal(), now).z()));
        return order;
    }

    private static Arrow arrow(GameTestHelper helper, ServerPlayer player, double distance, double speed, double damage) {
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(player.position().add(0, ShieldField.CENTER_Y, distance));
        arrow.setDeltaMovement(0, 0, -speed);
        arrow.setBaseDamage(damage);
        arrow.setNoGravity(true);
        return arrow;
    }

    private HiveGameTests() {
    }
}
