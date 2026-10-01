package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;

import dev.hurtify.relicsaddon.drone.AttackMode;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveFigures;
import dev.hurtify.relicsaddon.drone.HiveFlightPlan;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.HiveContainment;
import dev.hurtify.relicsaddon.server.HiveController;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import dev.hurtify.relicsaddon.menu.DeviceControlMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
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

    // Sky access: without it the test area gets a barrier roof, which the lift would rightly stop under.
    @GameTest(template = ARENA, timeoutTicks = 200, skyAccess = true)
    public static void twinsRiftsLiftTheTarget(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.TWINS_HIVE, AttackMode.CONTAINMENT);
        double ground = floorUnder(helper, fight.husk);
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        helper.runAfterDelay(80, () -> {
            double lift = dev.hurtify.relicsaddon.drone.HiveFormation.twinsLift(fight.husk.getBbWidth(), fight.husk.getBbHeight());
            helper.assertTrue(Math.abs(fight.husk.getY() - ground - lift) < .1,
                    "The black hole holds the target " + lift + " blocks up (" + (fight.husk.getY() - ground) + ")");
            helper.assertTrue(fight.husk.isNoGravity(), "It hangs there");
            RelicRuntime.setEnabled(fight.player, fight.hive, false);
        });
        helper.runAfterDelay(90, () -> {
            helper.assertFalse(fight.husk.isNoGravity(), "Released, it falls again");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void twinsLiftStopsUnderACeiling(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.TWINS_HIVE, AttackMode.CONTAINMENT);
        // A stone ceiling three blocks over the target's feet leaves about one block to rise.
        for (int x = 6; x <= 11; x++) for (int z = 4; z <= 9; z++) helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
        double ground = floorUnder(helper, fight.husk);
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        helper.runAfterDelay(80, () -> {
            double lifted = fight.husk.getY() - ground;
            double room = helper.absolutePos(new BlockPos(8, 4, 6)).getY() - ground;
            helper.assertTrue(lifted > .5 && lifted <= room - fight.husk.getBbHeight() + 1e-6, "The lift stops under the ceiling (" + lifted + " of " + room + ")");
            helper.assertTrue(helper.getLevel().noCollision(fight.husk, fight.husk.getBoundingBox()), "The target is never pushed into the blocks");
            helper.assertFalse(fight.husk.isInWall(), "It does not suffocate");
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

    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void theSwarmTakesOnEveryAttacker(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, AttackMode.BARRAGE);
        Husk second = attacker(helper, fight.player, new Vec3(8.5, 1, 2.5));
        helper.onEachTick(() -> {
            HiveCombatController.tick(fight.player);
            var combat = fight.hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), dev.hurtify.relicsaddon.drone.HiveCombatState.DEFAULT);
            if (combat.targets().size() == 2 && fight.husk.getHealth() < fight.husk.getMaxHealth() && second.getHealth() < second.getMaxHealth()) {
                helper.succeed();
            }
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void theSwarmMovesStraightOnWhenATargetFalls(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, AttackMode.DROPLET);
        Husk second = attacker(helper, fight.player, new Vec3(8.5, 1, 2.5));
        helper.onEachTick(() -> HiveCombatController.tick(fight.player));
        long[] started = {-1};
        helper.runAfterDelay(10, () -> {
            var combat = fight.hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), dev.hurtify.relicsaddon.drone.HiveCombatState.DEFAULT);
            helper.assertTrue(combat.active() && combat.targets().size() == 2, "Both creatures are engaged (" + combat.targets().size() + ")");
            started[0] = combat.changedAt();
            fight.husk.discard();
        });
        helper.runAfterDelay(12, () -> {
            var combat = fight.hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), dev.hurtify.relicsaddon.drone.HiveCombatState.DEFAULT);
            helper.assertTrue(combat.active(), "The swarm stays out");
            helper.assertTrue(combat.changedAt() == started[0], "It does not go home and set out again");
            helper.assertTrue(combat.targets().size() == 1 && combat.targets().getFirst().id() == second.getId(), "It moves on to the other attacker");
            helper.assertTrue(combat.retargetedAt() > started[0] && !combat.previous().isEmpty(), "Its drones fly over from where they were");
            helper.succeed();
        });
    }

    /** A second husk that has the owner as its target, as an attacking mob does. */
    private static Husk attacker(GameTestHelper helper, ServerPlayer owner, Vec3 relative) {
        Husk husk = EntityType.HUSK.create(helper.getLevel());
        helper.assertTrue(husk != null, "Husk fixture");
        Vec3 at = helper.absoluteVec(relative);
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        tough(husk);
        helper.assertTrue(helper.getLevel().addFreshEntity(husk), "The attacker enters the level");
        husk.setTarget(owner);
        return husk;
    }

    @GameTest(template = ARENA)
    public static void aFullHiveKeepsTwoHundredFiftyOut(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        HiveController.prepare(player, hive, false);
        HiveStackState swarm = hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
        helper.assertTrue(swarm.units().size() == HiveType.MAX_DRONES, "A level 10 hive holds " + HiveType.MAX_DRONES + " drones, got " + swarm.units().size());
        helper.assertTrue(HiveCombatController.plan(hive, HiveType.RF, swarm.units().size()).slots() == HiveType.MAX_DEPLOYED, "At most 250 fly at once");
        helper.assertTrue(swarm.units().stream().allMatch(unit -> unit.hp() == HiveType.DRONE_HP), "Every drone has three hit points");
        helper.succeed();
    }

    // --- drones shared between the modes -------------------------------------------------------------

    /** The server refuses 1 to minimum - 1 drones in a mode, from the console's buttons or from a packet sent round them. */
    @GameTest(template = ARENA)
    public static void aModeGetsNoneOrAWholeFigure(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, 0, 0, 0));
        DeviceControlMenu menu = new DeviceControlMenu(0, player.getInventory(), true, 0);
        String identity = hive.get(ModDataComponents.INSTANCE_ID.get());
        int minimum = HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET);
        helper.assertTrue(menu.clickMenuButton(player, DeviceControlMenu.allocationButton(AttackMode.DROPLET, 2)), "+1 on an empty mode is allowed");
        helper.assertTrue(settings(hive).droplet() == minimum, "+1 from nothing gives exactly the minimum, " + minimum + " (" + settings(hive).droplet() + ")");
        helper.assertTrue(menu.clickMenuButton(player, DeviceControlMenu.allocationButton(AttackMode.DROPLET, 1)), "-1 at the minimum is allowed");
        helper.assertTrue(settings(hive).droplet() == 0, "-1 from the minimum switches the mode off");
        for (int count = 1; count < minimum; count++) {
            helper.assertFalse(HiveTaskController.configureMode(player, true, 0, identity, AttackMode.DROPLET, count), count + " drones are refused");
            helper.assertFalse(HiveTaskController.configure(player, true, 0, identity, new HiveSettings(0, count, 0, 0)), count + " drones are refused whole");
            helper.assertTrue(settings(hive).droplet() == 0, "a refused allocation writes nothing");
        }
        helper.assertTrue(HiveTaskController.configureMode(player, true, 0, identity, AttackMode.DROPLET, minimum), "exactly the minimum is allowed");
        helper.assertTrue(HiveTaskController.configureMode(player, true, 0, identity, AttackMode.DROPLET, 0), "none is allowed");
        helper.assertFalse(HiveTaskController.configure(player, true, 0, identity, new HiveSettings(0, 50, 50, 18)), "no more drones than the hive holds");
        // A step that would leave the mode between 1 and its minimum is refused and changes nothing.
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, minimum + 4, 0, 0));
        helper.assertFalse(menu.clickMenuButton(player, DeviceControlMenu.allocationButton(AttackMode.DROPLET, 0)), "-10 to " + (minimum - 6) + " is refused");
        helper.assertTrue(settings(hive).droplet() == minimum + 4, "the refused step changed nothing");
        // Every button, pressed on every count, only ever leaves an allowed allocation.
        for (int count = 0; count <= 100; count += 3) {
            hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, 0, 0, 0).with(AttackMode.CONTAINMENT, HiveFigures.allowed(HiveType.RF,
                    AttackMode.CONTAINMENT, count) ? count : 0));
            for (int id = DeviceControlMenu.BUTTON_ALLOCATION_BASE; id < DeviceControlMenu.BUTTON_ALLOCATION_BASE + 12; id++) {
                menu.clickMenuButton(player, id);
                HiveSettings now = settings(hive);
                for (AttackMode mode : AttackMode.values()) {
                    helper.assertTrue(HiveFigures.allowed(HiveType.RF, mode, now.allocated(mode)), "button " + id + " left " + mode + " at " + now.allocated(mode));
                }
                helper.assertTrue(now.healers() + now.assigned() <= 100, "button " + id + " overfilled the hive");
            }
        }
        // The console's sliders send a count in their own packet; the server holds it to the same rules.
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, 0, 0, 0));
        for (int count = 1; count < minimum; count++) helper.assertFalse(menu.allocate(player, AttackMode.DROPLET.ordinal(), count), "a slider cannot set " + count);
        helper.assertTrue(menu.allocate(player, AttackMode.DROPLET.ordinal(), 2 * minimum) && settings(hive).droplet() == 2 * minimum, "two whole figures");
        helper.assertFalse(menu.allocate(player, AttackMode.BARRAGE.ordinal(), 101 - 2 * minimum), "no more than the hive holds");
        helper.assertFalse(menu.allocate(player, DeviceControlMenu.HEALERS, 100), "healers only from free drones");
        helper.assertTrue(menu.allocate(player, DeviceControlMenu.HEALERS, 100 - 2 * minimum) && settings(hive).healers() == 100 - 2 * minimum, "healers from free drones");
        helper.assertFalse(menu.allocate(player, 7, 0) || menu.allocate(player, -1, 0) || menu.allocate(player, 0, -5), "nonsense is refused");
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, 0, 0, 0));
        helper.assertTrue(menu.clickMenuButton(player, DeviceControlMenu.BUTTON_MODE_BASE + AttackMode.BARRAGE.ordinal()), "all into Barrage");
        helper.assertTrue(settings(hive).barrage() == 100 && settings(hive).droplet() == 0 && settings(hive).containment() == 0, "every fighter in Barrage");
        helper.succeed();
    }

    /** Droplet, Barrage and Containment fight at once, each with its own drones, and no more than 250 fly. */
    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void threeModesFightAtOnce(GameTestHelper helper) {
        Fight fight = fight(helper, RelicRole.RF_HIVE, new HiveSettings(0, 600, 700, 700));
        fight.hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        fight.hive.set(ModDataComponents.DEVICE_ENERGY.get(), dev.hurtify.relicsaddon.power.DevicePower.full(fight.hive));
        Husk second = attacker(helper, fight.player, new Vec3(8.5, 1, 2.5)), third = attacker(helper, fight.player, new Vec3(8.5, 1, 10.5));
        for (Husk husk : new Husk[]{fight.husk, second, third}) harmless(husk);
        boolean[] seen = new boolean[3];
        helper.onEachTick(() -> {
            HiveCombatController.tick(fight.player);
            HiveStackState swarm = fight.hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
            HiveFlightPlan plan = HiveCombatController.plan(fight.hive, HiveType.RF, swarm.units().size());
            helper.assertTrue(plan.slots() + plan.healerSlots() <= HiveType.MAX_DEPLOYED, "no more than 250 fly: " + plan.slots());
            HiveCombatState combat = combat(fight.hive);
            if (!combat.active()) return;
            for (AttackMode mode : AttackMode.values()) helper.assertTrue(combat.wing(mode).out(), mode + " is out");
            for (HiveCombatState.Shot shot : combat.shots()) {
                if (shot.kind() == HiveCombatState.DROPLET) seen[0] = true;
                if (shot.kind() == HiveCombatState.BALL) seen[1] = true;
                if (shot.kind() == HiveCombatState.ZAP) seen[2] = true;
            }
            boolean held = HiveContainment.pinned(fight.husk) || HiveContainment.pinned(second) || HiveContainment.pinned(third);
            if (seen[0] && seen[1] && seen[2] && held) {
                helper.assertTrue(plan.slots() == HiveType.MAX_DEPLOYED, "a full hive keeps 250 out between its modes: " + plan.slots());
                helper.succeed();
            }
        });
    }

    /** A mode squeezed below its figure by the others gets no places in the air, and they fly them instead. */
    @GameTest(template = ARENA, timeoutTicks = 260)
    public static void aModeWithoutRoomStaysHome(GameTestHelper helper) {
        int minimum = HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET);
        Fight fight = fight(helper, RelicRole.RF_HIVE, new HiveSettings(0, minimum, HiveType.MAX_DRONES - minimum, 0));
        fight.hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        fight.hive.set(ModDataComponents.DEVICE_ENERGY.get(), dev.hurtify.relicsaddon.power.DevicePower.full(fight.hive));
        harmless(fight.husk);
        helper.onEachTick(() -> {
            HiveCombatController.tick(fight.player);
            HiveFlightPlan plan = HiveCombatController.plan(fight.hive, HiveType.RF, HiveType.MAX_DRONES);
            helper.assertTrue(plan.wing(AttackMode.DROPLET).grounded(), "the Droplet has no room in the air");
            helper.assertTrue(plan.wing(AttackMode.BARRAGE).slots() == HiveType.MAX_DEPLOYED, "Barrage flies its places too");
            HiveCombatState combat = combat(fight.hive);
            helper.assertFalse(combat.wing(AttackMode.DROPLET).out(), "the Droplet stays home");
            helper.assertTrue(combat.shots().stream().noneMatch(shot -> shot.kind() == HiveCombatState.DROPLET), "the Droplet never strikes");
            if (combat.shots().stream().anyMatch(shot -> shot.kind() == HiveCombatState.BALL)) helper.succeed();
        });
    }

    /** A mode left with fewer drones than one figure goes home, and comes back by itself once repaired. */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void aModeShortOfDronesGoesHomeAndReturns(GameTestHelper helper) {
        int minimum = HiveFigures.minimum(HiveType.RF, AttackMode.DROPLET);
        Fight fight = fight(helper, RelicRole.RF_HIVE, new HiveSettings(0, minimum, 100 - minimum, 0));
        harmless(fight.husk);
        long[] benched = {-1};
        helper.onEachTick(() -> {
            HiveController.prepare(fight.player, fight.hive, true);
            HiveCombatController.tick(fight.player);
            HiveCombatState combat = combat(fight.hive);
            long now = helper.getLevel().getGameTime();
            HiveCombatState.Wing droplet = combat.wing(AttackMode.DROPLET);
            if (benched[0] < 0) {
                if (!combat.active() || !droplet.out() || now < combat.changedAt() + 30) return;
                // A drone of the Droplet's is hit and it has no spare: fifteen cannot make a tesseract.
                HiveStackState swarm = fight.hive.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(), HiveStackState.DEFAULT);
                List<HiveStackState.Unit> units = new ArrayList<>(swarm.units());
                units.set(3, units.get(3).hit(1, now, now + 60));
                fight.hive.set(ModDataComponents.HIVE_STACK_STATE.get(), new HiveStackState(swarm.enabled(), units));
                benched[0] = now;
                return;
            }
            if (now == benched[0] + 1) {
                helper.assertFalse(droplet.out(), "a Droplet short of a tesseract goes home");
                helper.assertTrue(combat.active() && combat.wing(AttackMode.BARRAGE).out(), "the other modes fight on");
            }
            if (now > benched[0] + 1 && droplet.out()) {
                helper.assertTrue(now >= benched[0] + 60, "it comes back only once its drone is repaired");
                helper.assertTrue(droplet.since() > benched[0], "it sets out afresh");
                helper.succeed();
            }
        });
    }

    /** A save from before the modes could be mixed puts every fighter in its one mode, or in Barrage if that mode's figure cannot be built. */
    @GameTest(template = ARENA)
    public static void anOldSingleModeSaveMigrates(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.TWINS_HIVE, 0);
        CompoundTag old = new CompoundTag();
        old.putInt("healers", 10);
        old.putString("mode", AttackMode.CONTAINMENT.id());
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.CODEC.parse(NbtOps.INSTANCE, old).getOrThrow());
        HiveSettings migrated = HiveController.normalize(player, hive);
        helper.assertTrue(migrated.healers() == 10 && migrated.containment() == 90 && migrated.assigned() == 90 && migrated.notice() == null,
                "every fighter holds, as before: " + migrated);
        helper.assertTrue(settings(hive).equals(migrated), "the migrated orders are written back");
        old.putInt("healers", 80);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.CODEC.parse(NbtOps.INSTANCE, old).getOrThrow());
        migrated = HiveController.normalize(player, hive);
        helper.assertTrue(migrated.containment() == 0 && migrated.barrage() == 20, "twenty cannot build the Twins tori, so they go to Barrage: " + migrated);
        helper.assertTrue(migrated.notice() != null && migrated.notice().kind() == HiveSettings.Notice.Kind.MOVED && migrated.notice().need() == 24,
                "and the console says why");
        helper.succeed();
    }

    /** When the hive holds fewer drones, a mode left short of its figure is switched off, its drones freed, and the console says why. */
    @GameTest(template = ARENA)
    public static void aShrunkHiveSwitchesOffWhatNoLongerFits(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 0);
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(0, 90, 4, 18));
        helper.assertTrue(HiveController.normalize(player, hive).containment() == 18, "a big hive keeps its orders");
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, 0, 0, 0));
        HiveSettings shrunk = HiveController.normalize(player, hive);
        helper.assertTrue(shrunk.droplet() == 90 && shrunk.barrage() == 4 && shrunk.containment() == 0, "six drones cannot hold anything: " + shrunk);
        HiveSettings.Notice notice = shrunk.notice();
        helper.assertTrue(notice != null && notice.kind() == HiveSettings.Notice.Kind.CUT && notice.mode() == AttackMode.CONTAINMENT
                && notice.had() == 6 && notice.need() == HiveFigures.minimum(HiveType.RF, AttackMode.CONTAINMENT), "the notice says which mode and why: " + notice);
        helper.assertTrue(shrunk.free(100) == 6, "its drones are freed");
        // The owner's next change is theirs: the notice goes.
        DeviceControlMenu menu = new DeviceControlMenu(0, player.getInventory(), true, 0);
        helper.assertTrue(menu.clickMenuButton(player, DeviceControlMenu.allocationButton(AttackMode.BARRAGE, 2)), "+1 Barrage");
        helper.assertTrue(settings(hive).notice() == null && settings(hive).barrage() == 5, "a change of the owner's clears the notice");
        helper.succeed();
    }

    private static HiveSettings settings(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_SETTINGS.get(), HiveSettings.DEFAULT);
    }

    private static HiveCombatState combat(ItemStack hive) {
        return hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
    }

    /** A target that never swings at drones, so none is hit but those a test hits itself. */
    private static void harmless(Husk husk) {
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(0);
    }

    /** The floor under a creature: the fixture's target floats (it has no AI, so no gravity), and lifts count from the ground. */
    private static double floorUnder(GameTestHelper helper, Husk husk) {
        return helper.getLevel().clip(new ClipContext(husk.position(), husk.position().subtract(0, 8, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, husk)).getLocation().y;
    }

    /** A hundred-drone swarm kills an ordinary husk in a blow or two; test targets must outlive the checks. */
    private static void tough(Husk husk) {
        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(500);
        husk.setHealth(500);
    }

    private record Fight(ServerPlayer player, ItemStack hive, Husk husk) { }

    /** An open floor, the owner with a charged hive in {@code mode}, and a target six blocks off that the owner has just hit. */
    private static Fight fight(GameTestHelper helper, RelicRole role, AttackMode mode) {
        return fight(helper, role, HiveSettings.legacy(0, mode));
    }

    /** As above with the hive's drones shared out by {@code settings}. */
    private static Fight fight(GameTestHelper helper, RelicRole role, HiveSettings settings) {
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer player = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        ItemStack hive = DeviceTestSupport.equip(helper, player, role, 0);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), settings);
        Husk husk = EntityType.HUSK.create(helper.getLevel());
        helper.assertTrue(husk != null, "Husk fixture");
        Vec3 at = helper.absoluteVec(new Vec3(8.5, 1, 6.5));
        husk.moveTo(at.x, at.y, at.z);
        husk.setNoAi(true);
        tough(husk);
        helper.assertTrue(helper.getLevel().addFreshEntity(husk), "The target enters the level");
        player.setLastHurtMob(husk);
        return new Fight(player, hive, husk);
    }

    private HiveModeGameTests() {
    }
}
