package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;

import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveSlots;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.HiveContainment;
import dev.hurtify.relicsaddon.server.HiveController;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A worn hive driven tick by tick against a real target: each attack mode must land its blows, and
 * containment must pin, silence, turn back and lift what it holds.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class HiveModeGameTests {
    @GameTest(template = ARENA, timeoutTicks = 260)
    public static void dropletGroupsStrikeAsOneBlow(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, AttackMode.DROPLET);
        helper.onEachTick(() -> {
            HiveCombatController.tick(fight.player);
            if (fight.husk.getHealth() < fight.husk.getMaxHealth()) {
                helper.assertTrue(fight.husk.getLastDamageSource() != null && fight.husk.getLastDamageSource().is(HiveCombatController.SWARM_STRIKE),
                        "A group's dive lands as a swarm blow");
                helper.assertTrue(fight.husk.getLastDamageSource().getEntity() == fight.player, "The owner gets the credit");
                helper.succeed();
            }
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 260)
    public static void barrageClustersFireCharges(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.MANA_HIVE, AttackMode.BARRAGE);
        helper.onEachTick(() -> {
            HiveCombatController.tick(fight.player);
            if (fight.husk.getHealth() < fight.husk.getMaxHealth()) {
                helper.assertTrue(fight.husk.getLastDamageSource().is(HiveCombatController.SWARM_STRIKE), "A charge blasts the target");
                helper.succeed();
            }
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void rfContainmentSealsTheTarget(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, AttackMode.CONTAINMENT);
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(HiveContainment.pinned(fight.husk), "The torus holds the target");
            Vec3 anchor = fight.husk.position();
            fight.husk.teleportTo(anchor.x + 2, anchor.y, anchor.z);
            HiveCombatController.tick(fight.player);
            helper.assertTrue(fight.husk.position().distanceTo(anchor) < .05, "A held target cannot move");
            float health = fight.player.getHealth();
            helper.assertFalse(fight.player.hurt(fight.player.damageSources().mobAttack(fight.husk), 4), "A sealed target's blows do nothing");
            helper.assertTrue(fight.player.getHealth() == health, "The owner is untouched");
            Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
            arrow.setOwner(fight.husk);
            arrow.moveTo(anchor.x, anchor.y + 1.5, anchor.z);
            helper.assertFalse(helper.getLevel().addFreshEntity(arrow), "Shots the target fires are swallowed");
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(fight.husk.getHealth() < fight.husk.getMaxHealth(), "The torus strikes its prisoner with lightning");
            helper.assertTrue(fight.husk.getLastDamageSource().is(HiveCombatController.DRONE_SHOT), "Containment zaps do not knock the target about");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void manaWardTurnsBlowsBack(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.MANA_HIVE, AttackMode.CONTAINMENT);
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        helper.runAfterDelay(90, () -> {
            HiveContainment.Hold hold = HiveContainment.held(fight.husk);
            helper.assertTrue(hold != null && hold.ward() >= 4, "The drones charge the ward (" + (hold == null ? "no hold" : hold.ward()) + ")");
            float health = fight.player.getHealth(), before = fight.husk.getHealth();
            helper.assertFalse(fight.player.hurt(fight.player.damageSources().mobAttack(fight.husk), 4), "The ward stops the blow");
            helper.assertTrue(fight.player.getHealth() == health, "The owner is untouched");
            helper.assertTrue(fight.husk.getHealth() < before, "The blow is turned back on the attacker");
            helper.assertTrue(fight.husk.getLastDamageSource().is(HiveCombatController.SWARM_REFLECT), "Its own blow, reflected");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void twinsRiftsLiftTheTarget(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.TWINS_HIVE, AttackMode.CONTAINMENT);
        double ground = fight.husk.getY();
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(Math.abs(fight.husk.getY() - ground - HiveContainment.LIFT) < .1,
                    "The rifts hold the target four blocks up (" + (fight.husk.getY() - ground) + ")");
            helper.assertTrue(fight.husk.isNoGravity(), "It hangs there");
            RelicRuntime.setEnabled(fight.player, fight.hive, false);
        });
        helper.runAfterDelay(90, () -> {
            helper.assertFalse(fight.husk.isNoGravity(), "Released, it falls again");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 260)
    public static void targetsSwingAtDronesInReach(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.TWINS_HIVE, AttackMode.DROPLET);
        helper.onEachTick(() -> {
            HiveCombatController.tick(fight.player);
            HiveStackState swarm = fight.hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
            if (swarm.units().stream().anyMatch(unit -> unit.lastHit() >= 0)) {
                HiveStackState.Unit hit = swarm.units().stream().filter(unit -> unit.lastHit() >= 0).findFirst().orElseThrow();
                helper.assertTrue(hit.hp() < HiveType.DRONE_HP && hit.readyAt() > hit.lastHit(), "A hit drone goes home for repair");
                helper.succeed();
            }
        });
    }

    @GameTest(template = ARENA)
    public static void aFullHiveKeepsTwoHundredFiftyOut(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        HiveController.prepare(player, hive, false);
        HiveStackState swarm = hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        helper.assertTrue(swarm.units().size() == HiveType.MAX_DRONES, "A level 10 hive holds 750 drones, got " + swarm.units().size());
        helper.assertTrue(HiveSlots.fighterSlots(swarm.units().size(), HiveSettings.DEFAULT) == HiveType.MAX_DEPLOYED, "At most 250 fly at once");
        helper.assertTrue(swarm.units().stream().allMatch(unit -> unit.hp() == HiveType.DRONE_HP), "Every drone has three hit points");
        helper.succeed();
    }

    private record Fight(ServerPlayer player, ItemStack hive, Husk husk) { }

    /** An open floor, the owner with a charged hive in {@code mode}, and a target six blocks off that the owner has just hit. */
    private static Fight fight(GameTestHelper helper, RelicRole role, AttackMode mode) {
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer player = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        ItemStack hive = DeviceTestSupport.equip(helper, player, role, 0);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, mode));
        Husk husk = EntityType.HUSK.create(helper.getLevel());
        helper.assertTrue(husk != null, "Husk fixture");
        Vec3 at = helper.absoluteVec(new Vec3(8.5, 1, 6.5));
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(husk), "The target enters the level");
        player.setLastHurtMob(husk);
        return new Fight(player, hive, husk);
    }

    private HiveModeGameTests() {
    }
}
