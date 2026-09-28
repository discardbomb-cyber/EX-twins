package dev.hurtify.relicsaddon.gametest;

import com.mojang.serialization.JsonOps;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicProgression;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import dev.hurtify.relicsaddon.shield.ShieldField;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

/** Server contracts for persisted hive healer allocation and its combat exclusions. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class HiveTaskGameTests {
    @GameTest(template = "test_room")
    public static void allocationClampsToHiveCapacityAndRoundTrips(GameTestHelper helper) {
        HiveSettings settings = new HiveSettings(20);
        helper.assertTrue(settings.fighters(50) == 30 && settings.healerCount(50) == 20,
                "Fifty drones split into thirty fighters and twenty healers");
        helper.assertTrue(settings.fighters(12) == 0 && settings.healerCount(12) == 12
                        && settings.healer(0, 12) && settings.healer(11, 12),
                "A twelve-drone hive caps a twenty-healer preference at its real capacity");

        var persistent = HiveSettings.CODEC.parse(JsonOps.INSTANCE,
                HiveSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow()).getOrThrow();
        var buffer = Unpooled.buffer();
        try {
            HiveSettings.STREAM_CODEC.encode(buffer, settings);
            HiveSettings network = HiveSettings.STREAM_CODEC.decode(buffer);
            helper.assertTrue(settings.equals(persistent) && settings.equals(network) && buffer.readableBytes() == 0,
                    "Healer allocation survives persistent and synchronized component roundtrips");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void serverConfigurationValidatesOwnerIdentitySlotRangeAndTimers(GameTestHelper helper) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack hive = new ItemStack(ModItems.RF_HIVE.get());
        hive.set(ModDataComponents.INSTANCE_ID.get(), "task-owner-hive");
        hive.set(ModDataComponents.HIVE_STACK_STATE.get(), swarm(12, 12, 600));
        owner.getInventory().setItem(0, hive);

        helper.assertFalse(HiveTaskController.configure(owner, false, -1, "task-owner-hive", 1),
                "Negative inventory slots are rejected");
        helper.assertFalse(HiveTaskController.configure(owner, false, owner.getInventory().getContainerSize(), "task-owner-hive", 1),
                "Out-of-range inventory slots are rejected");
        helper.assertFalse(HiveTaskController.configure(owner, false, 0, "other-hive", 1),
                "A packet cannot configure another stack by guessing its slot");
        helper.assertFalse(HiveTaskController.configure(owner, false, 0, "task-owner-hive", 13),
                "Healer allocations above the current twelve-drone capacity are rejected");
        helper.assertFalse(HiveTaskController.configure(owner, true, 0, "task-owner-hive", 1),
                "The claimed Curios location must actually contain the identified hive");
        helper.assertTrue(HiveTaskController.configure(owner, false, 0, "task-owner-hive", 10),
                "The owner can save an in-range allocation for its identified hive");
        helper.assertTrue(HiveTaskController.settings(hive).healerCount(12) == 10,
                "Server configuration persists the selected healer count");

        helper.assertTrue(hive.get(ModDataComponents.HIVE_STACK_STATE.get()).units().stream()
                        .allMatch(unit -> unit.attackReadyAt() == 600),
                "Allocation changes never reset attack cooldowns");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void nativeRelicRankupUsesOverallLevelNotItemRarity(GameTestHelper helper) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack hive = new ItemStack(ModItems.RF_HIVE.get());
        var relic = ModItems.RF_HIVE.get();
        var data = relic.getRelicData(owner, hive);
        var leveling = data.getLevelingData();
        var ability = RelicRuntime.ability(owner, hive);
        ability.getResearchData().complete();
        ability.setLevel(4);
        leveling.setLevel(data.calculateMaxLevel());

        helper.assertTrue(leveling.getRank() == 0 && leveling.getTemplate().getMaxRank() == RelicProgression.MAX_RANK
                        && leveling.mayPlayerRankup(),
                "A max-level relic is eligible for Relics' native multi-rank progression");
        helper.assertTrue(leveling.rankup(), "Native Relics rank-up succeeds from the overall item-level cap");
        helper.assertTrue(leveling.getRank() == 1 && leveling.getLevel() == 0 && leveling.getPoints() == 0,
                "Native rank-up advances the stored rank and resets only native level progress");
        helper.assertTrue(ability.getLevel() == 0 && ability.getResearchData().isResearched(),
                "Native rank-up resets ability levels by Relics rules while retaining research completion");
        leveling.setRank(RelicProgression.MAX_RANK);
        leveling.setLevel(data.calculateMaxLevel());
        helper.assertFalse(leveling.mayPlayerRankup(),
                "The zero-based native cap produces exactly five displayed relic ranks, not six");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void healingIsOwnerOnlySharedAndSkipsDeadHealers(GameTestHelper helper) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        owner.setHealth(10);
        ItemStack first = configuredHive(ModItems.RF_HIVE.get(), 12, swarmWithLiveRange(12, 8, 12, 1));
        ItemStack second = configuredHive(ModItems.MANA_HIVE.get(), 12, swarm(12, 8, 1));
        Husk nearby = new Husk(EntityType.HUSK, helper.getLevel());
        nearby.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
        nearby.setHealth(10);
        nearby.setPos(owner.position().add(1, 0, 0));
        helper.assertTrue(helper.getLevel().addFreshEntity(nearby), "Nearby non-owner enters the server level");
        float nearbyHealth = nearby.getHealth();

        HiveTaskController.tickHealing(owner, List.of(
                new HiveController.Equipped(HiveType.RF, first),
                new HiveController.Equipped(HiveType.MANA, second)), 20);

        helper.assertTrue(owner.getHealth() == 14,
                "All equipped hives together restore at most four owner health per second");
        helper.assertTrue(nearby.getHealth() == nearbyHealth,
                "Healer drones never restore a nearby non-owner");
        var firstUnits = first.get(ModDataComponents.HIVE_STACK_STATE.get()).units();
        helper.assertTrue(firstUnits.subList(0, 8).stream().allMatch(unit -> unit.hp() == 0 && unit.attackReadyAt() == 1)
                        && firstUnits.subList(8, 12).stream().allMatch(unit -> unit.attackReadyAt() == 120),
                "Dead healers are excluded while live healers spend their independent support cooldown");
        var secondUnits = second.get(ModDataComponents.HIVE_STACK_STATE.get()).units();
        helper.assertTrue(secondUnits.subList(0, 4).stream().allMatch(unit -> unit.attackReadyAt() == 120)
                        && secondUnits.subList(4, 12).stream().allMatch(unit -> unit.attackReadyAt() == 1),
                "The shared budget carries from the first hive to the second without exceeding four HP");
        nearby.discard();
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void healersCannotInterceptButFightersCan(GameTestHelper helper) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        owner.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        ItemStack hive = equipHive(owner, ModItems.RF_HIVE.get());
        hive.set(ModDataComponents.HIVE_STACK_STATE.get(), swarm(12, 12, 1));
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(12));
        Arrow healerOnlyArrow = incomingArrow(owner);
        helper.assertFalse(HiveController.intercept(healerOnlyArrow, owner),
                "A hive assigned entirely to healing cannot intercept projectiles");
        helper.assertTrue(hive.get(ModDataComponents.HIVE_STACK_STATE.get()).units().stream().allMatch(unit -> unit.hp() == 12),
                "Healer-only interception leaves every assigned healer untouched");

        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(10));
        Arrow fighterArrow = incomingArrow(owner);
        helper.assertTrue(HiveController.intercept(fighterArrow, owner),
                "The two fighter slots can still intercept a threatening projectile");
        var units = hive.get(ModDataComponents.HIVE_STACK_STATE.get()).units();
        helper.assertTrue(units.subList(2, 12).stream().allMatch(unit -> unit.hp() == 12)
                        && (units.get(0).hp() < 12 || units.get(1).hp() < 12),
                "Only fighter indices pay interception damage; assigned healers do not");
        helper.succeed();
    }

    @GameTest(template = "field_arena", timeoutTicks = 180)
    public static void healersDoNotAttackWhileFightersStillFire(GameTestHelper helper) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        owner.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        ItemStack hive = equipHive(owner, ModItems.RF_HIVE.get());
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(10));
        Husk target = new Husk(EntityType.HUSK, helper.getLevel());
        target.setNoAi(true);
        target.setPos(owner.position().add(0, 0, 3));
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(200);
        helper.assertTrue(helper.getLevel().addFreshEntity(target), "Combat target enters the field arena");
        owner.setLastHurtMob(target);
        boolean[] sawFighterShot = {false};
        boolean[] sawHealerShot = {false};
        for (int delay = 1; delay <= 160; delay++) {
            helper.runAfterDelay(delay, () -> {
                HiveCombatController.tick(owner);
                HiveCombatState combat = hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
                sawFighterShot[0] |= combat.shots().stream().anyMatch(shot -> shot.unit() < 2);
                sawHealerShot[0] |= combat.shots().stream().anyMatch(shot -> shot.unit() >= 2);
            });
        }
        helper.runAfterDelay(161, () -> {
            helper.assertTrue(sawFighterShot[0] && !sawHealerShot[0],
                    "Assigned healers never attack while the remaining fighters retain their combat role");
            target.discard();
            HiveCombatController.clear(owner);
            helper.succeed();
        });
    }

    private static ItemStack configuredHive(dev.hurtify.relicsaddon.relic.HiveRelicItem item, int healers, HiveStackState state) {
        ItemStack stack = new ItemStack(item);
        stack.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(healers));
        stack.set(ModDataComponents.HIVE_STACK_STATE.get(), state);
        return stack;
    }

    private static ItemStack equipHive(ServerPlayer owner, dev.hurtify.relicsaddon.relic.HiveRelicItem item) {
        ItemStack hive = new ItemStack(item);
        CuriosApi.getCuriosInventory(owner).orElseThrow().setEquippedCurio("charm", 0, hive);
        RelicRuntime.ability(owner, hive).setLevel(0);
        RelicRuntime.ability(owner, hive).getResearchData().complete();
        return hive;
    }

    private static HiveStackState swarm(int count, int health, long attackReadyAt) {
        return swarmWithLiveRange(count, 0, count, attackReadyAt).withEnabled(true);
    }

    private static HiveStackState swarmWithLiveRange(int count, int liveStart, int liveEnd, long attackReadyAt) {
        var units = new ArrayList<HiveStackState.Unit>(count);
        for (int index = 0; index < count; index++) {
            int health = index >= liveStart && index < liveEnd ? 12 : 0;
            units.add(new HiveStackState.Unit(health, 0, -1, 0, 0, 0, attackReadyAt));
        }
        return new HiveStackState(true, units);
    }

    private static Arrow incomingArrow(ServerPlayer owner) {
        Arrow arrow = new Arrow(EntityType.ARROW, owner.level());
        arrow.setPos(owner.position().add(0, ShieldField.CENTER_Y, 3));
        arrow.setDeltaMovement(0, 0, -2);
        arrow.setBaseDamage(2);
        arrow.setNoGravity(true);
        return arrow;
    }

    private HiveTaskGameTests() {
    }
}
