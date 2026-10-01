package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;
import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.hive.AttackMode;
import dev.hurtify.relicsaddon.domain.hive.HiveCombatState;
import dev.hurtify.relicsaddon.domain.hive.HiveSettings;
import dev.hurtify.relicsaddon.domain.hive.HiveStackState;
import dev.hurtify.relicsaddon.domain.hive.HiveSupportState;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.HiveController;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What a worn hive does besides attacking, pinned as it behaves today: healers mend the owner, a
 * blast damages the drones in its reach, the swarm is recalled when its target is gone, and every
 * tenth tick repairs the swarm, pays for it and settles it.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class HiveCharacterizationGameTests {
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void healersMendTheOwner(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.legacy(3, AttackMode.BARRAGE)
                .resolve(HiveType.RF, HiveController.capacity(player, hive)));
        player.setHealth(10);
        int rf = DevicePower.energy(hive).rf();
        helper.onEachTick(() -> {
            HiveCombatController.tick(player);
            if (player.getHealth() > 10 && support(hive).active() && DevicePower.energy(hive).rf() < rf) helper.succeed();
        });
    }

    @GameTest(template = ARENA)
    public static void explosionsDamageDronesInReach(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, AttackMode.DROPLET);
        HiveCombatController.tick(fight.player);
        helper.assertTrue(combat(fight.hive).active(), "The swarm takes on the target at once");
        // Still in the tick the combat began, so every drone is just leaving the hive at the owner's belt.
        long now = helper.getLevel().getGameTime();
        Vec3 feet = fight.player.position();
        Explosion explosion = new Explosion(helper.getLevel(), null, feet.x, feet.y + .86, feet.z, 1F, false, Explosion.BlockInteraction.KEEP);
        HiveCombatController.onExplosion(new ExplosionEvent.Detonate(helper.getLevel(), explosion, new ArrayList<>()));
        helper.assertTrue(swarm(fight.hive).units().stream().anyMatch(unit -> unit.lastHit() == now && unit.hp() < 3),
                "A blast within reach damages the drones and sends them home");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void swarmRecallsWhenTheTargetIsGone(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, AttackMode.BARRAGE);
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(combat(fight.hive).active(), "The swarm is fighting its target");
            fight.husk.discard();
            HiveCombatController.tick(fight.player);
            long now = helper.getLevel().getGameTime();
            HiveCombatState combat = combat(fight.hive);
            helper.assertTrue(!combat.active() && combat.changedAt() == now && combat.targetId() == -1,
                    "Without a target the swarm is recalled at once (active " + combat.active() + ", changed at " + combat.changedAt()
                            + " of " + now + ", target " + combat.targetId() + ")");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void hiveRepairsAndSettlesOnTheTenthTick(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        HiveController.prepare(player, hive, false);
        HiveStackState swarm = swarm(hive);
        List<HiveStackState.Unit> units = new ArrayList<>(swarm.units());
        units.set(0, new HiveStackState.Unit(1, 0, -1, 0));
        hive.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(swarm.enabled(), units));
        int rf = DevicePower.energy(hive).rf();
        helper.onEachTick(() -> {
            if (helper.getLevel().getGameTime() % 10 != 0) return;
            // Called directly rather than through the bus, so the upkeep listener cannot take its share as well.
            HiveController.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(swarm(hive).units().get(0).equals(HiveStackState.Unit.fresh()), "The damaged drone is whole again, got "
                    + swarm(hive).units().get(0));
            int spent = rf - DevicePower.energy(hive).rf();
            helper.assertTrue(spent == 2 * DevicePower.HIVE_REPAIR_PER_HP * DevicePower.FE_PER_POINT,
                    "The battery pays for the two restored hit points, got " + spent + " FE");
            helper.succeed();
        });
    }

    private static HiveStackState swarm(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
    }

    private static HiveCombatState combat(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
    }

    private static HiveSupportState support(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_SUPPORT_STATE.get(), HiveSupportState.DEFAULT);
    }

    private record Fight(ServerPlayer player, ItemStack hive, Husk husk) { }

    /** An open floor, the owner with a charged hive in {@code mode}, and a target six blocks off that the owner has just hit. */
    private static Fight fight(GameTestHelper helper, RelicRole role, AttackMode mode) {
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer player = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        ItemStack hive = DeviceTestSupport.equip(helper, player, role, 0);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.legacy(0, mode)
                .resolve(HiveType.of(role), HiveController.capacity(player, hive)));
        Husk husk = EntityType.HUSK.create(helper.getLevel());
        helper.assertTrue(husk != null, "Husk fixture");
        Vec3 at = helper.absoluteVec(new Vec3(8.5, 1, 6.5));
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(husk), "The target enters the level");
        player.setLastHurtMob(husk);
        return new Fight(player, hive, husk);
    }

    private HiveCharacterizationGameTests() {
    }
}
