package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;

import dev.hurtify.relicsaddon.registry.ModBlocks;
import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.ship.AegisModule;
import dev.hurtify.relicsaddon.ship.EscortModule;
import dev.hurtify.relicsaddon.ship.LanceModule;
import dev.hurtify.relicsaddon.ship.ShipDamage;
import dev.hurtify.relicsaddon.ship.ShipHiveBlock;
import dev.hurtify.relicsaddon.ship.ShipHiveBlockEntity;
import dev.hurtify.relicsaddon.ship.ShipHiveKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Ship hives standing on the ground, where their frame is simply their block: the lance must find a monster on its
 * own, burn it with its beam, hold its fire for the crew and bystanders, stop for redstone and an empty battery, and
 * overheat; the aegis must stop shots and blows from outside its dome but let the crew's own out, keep an explosion
 * off what it covers, break under too big a blow and come back, and fall when grounded; the escort's wings must fly
 * out on patrol, go after a monster on their own, keep to their leash and come home when switched off. Each
 * runs in a batch of its own: a lance reaches far enough to fire into another test's arena.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShipHiveGameTests {
    private static final BlockPos HIVE = new BlockPos(2, 1, 6);

    @GameTest(template = ARENA, batch = "ship_lance_burns_a_monster_on_its_own", timeoutTicks = 160)
    public static void lanceBurnsAMonsterOnItsOwn(GameTestHelper helper) {
        Scene scene = scene(helper, new Vec3(4.5, 1, 2.5), new Vec3(9.5, 1, 6.5));
        int full = scene.hive.energy().getEnergyStored();
        helper.onEachTick(() -> {
            if (scene.husk.getHealth() >= scene.husk.getMaxHealth()) return;
            helper.assertTrue(scene.husk.getLastDamageSource() != null && scene.husk.getLastDamageSource().is(ShipDamage.LANCE), "The lance's beam burns the husk");
            helper.assertTrue(scene.husk.getLastDamageSource().getEntity() == scene.owner, "The hive's owner gets the credit");
            helper.assertTrue(scene.owner.getHealth() == scene.owner.getMaxHealth(), "The owner is untouched");
            helper.assertTrue(scene.hive.energy().getEnergyStored() < full, "The beam runs on the battery");
            helper.assertTrue(lance(scene.hive).firing(), "The turret is burning");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_lance_holds_its_fire_for_the_crew", timeoutTicks = 200)
    public static void lanceHoldsItsFireForTheCrew(GameTestHelper helper) {
        // The owner stands right in the line of fire, in front of the husk.
        Scene scene = scene(helper, new Vec3(8.3, 1, 6.5), new Vec3(9.9, 1, 6.5));
        boolean[] inTheWay = {true};
        helper.onEachTick(() -> {
            helper.assertTrue(scene.owner.getHealth() == scene.owner.getMaxHealth(), "The beam never burns the crew");
            if (inTheWay[0]) helper.assertFalse(lance(scene.hive).firing(), "The turret never burns with a friend in the line of fire");
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(scene.husk.getHealth() == scene.husk.getMaxHealth(), "A friend in the way holds the beam");
            inTheWay[0] = false;
            scene.owner.teleportTo(scene.owner.getX(), scene.owner.getY(), scene.owner.getZ() - 4);
            helper.succeedWhen(() -> helper.assertTrue(scene.husk.getHealth() < scene.husk.getMaxHealth(), "With the line clear it burns"));
        });
    }

    @GameTest(template = ARENA, batch = "ship_lance_leaves_peaceful_creatures_alone", timeoutTicks = 200)
    public static void lanceLeavesPeacefulCreaturesAlone(GameTestHelper helper) {
        Scene scene = scene(helper, new Vec3(4.5, 1, 2.5), null);
        Pig pig = EntityType.PIG.create(helper.getLevel());
        helper.assertTrue(pig != null, "Pig fixture");
        Vec3 at = helper.absoluteVec(new Vec3(9.5, 1, 6.5));
        pig.moveTo(at.x, at.y, at.z);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(pig.getHealth() == pig.getMaxHealth(), "A pig is no threat");
            helper.assertTrue(lance(scene.hive).targetId() < 0, "The turret has nothing to aim at");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_redstone_grounds_the_lance", timeoutTicks = 200)
    public static void redstoneGroundsTheLance(GameTestHelper helper) {
        Scene scene = scene(helper, new Vec3(4.5, 1, 2.5), new Vec3(9.5, 1, 6.5));
        helper.setBlock(HIVE.east(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(scene.husk.getHealth() == scene.husk.getMaxHealth(), "A grounded hive does not fire");
            helper.assertFalse(lance(scene.hive).deployed(), "Its drones stay docked");
            helper.setBlock(HIVE.east(), Blocks.AIR);
            helper.succeedWhen(() -> helper.assertTrue(scene.husk.getHealth() < scene.husk.getMaxHealth(), "Without the signal it fights again"));
        });
    }

    @GameTest(template = ARENA, batch = "ship_an_empty_lance_tracks_but_cannot_burn", timeoutTicks = 200)
    public static void anEmptyLanceTracksButCannotBurn(GameTestHelper helper) {
        Scene scene = scene(helper, new Vec3(4.5, 1, 2.5), new Vec3(9.5, 1, 6.5));
        scene.hive.energy().extractEnergy(Integer.MAX_VALUE, false);
        drain(scene.hive);
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(scene.husk.getHealth() == scene.husk.getMaxHealth(), "No charge, no beam");
            helper.assertTrue(lance(scene.hive).deployed() && lance(scene.hive).targetId() == scene.husk.getId(), "The turret still takes aim");
            helper.assertFalse(lance(scene.hive).firing(), "It does not burn");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_lance_overheats_and_cools", timeoutTicks = 420)
    public static void lanceOverheatsAndCools(GameTestHelper helper) {
        Scene scene = scene(helper, new Vec3(4.5, 1, 2.5), new Vec3(9.5, 1, 6.5));
        boolean[] overheated = {false}, cooledQuietly = {false};
        helper.onEachTick(() -> {
            LanceModule lance = lance(scene.hive);
            if (lance.overheated() && !overheated[0]) {
                overheated[0] = true;
                helper.assertFalse(lance.firing(), "An overheated turret stops burning");
                float health = scene.husk.getHealth();
                helper.runAfterDelay(30, () -> {
                    helper.assertTrue(scene.husk.getHealth() == health, "It holds its fire while it cools");
                    helper.assertTrue(lance(scene.hive).overheated(), "Cooling right down takes longer than that");
                    cooledQuietly[0] = true;
                });
            }
            if (cooledQuietly[0] && !lance.overheated() && lance.firing()) helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_lance_spares_bystanders", timeoutTicks = 200)
    public static void lanceSparesABystanderInTheWay(GameTestHelper helper) {
        // Someone who is neither crew nor a threat stands in the line of fire: the turret must not burn through them.
        Scene scene = scene(helper, new Vec3(4.5, 1, 2.5), new Vec3(9.9, 1, 6.5));
        ServerPlayer bystander = DeviceTestSupport.player(helper, new Vec3(8.3, 1, 6.5), "ship-bystander");
        helper.onEachTick(() -> {
            helper.assertTrue(bystander.getHealth() == bystander.getMaxHealth(), "The beam never burns a bystander");
            helper.assertFalse(lance(scene.hive).firing(), "It holds its fire");
        });
        helper.runAfterDelay(120, helper::succeed);
    }

    // --- aegis ---------------------------------------------------------------------------------

    @GameTest(template = ARENA, batch = "ship_aegis_stops_arrows", timeoutTicks = 100)
    public static void aegisStopsArrowsFromOutside(GameTestHelper helper) {
        Aegis scene = aegis(helper);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(module(scene.hive).raised(), "The shield is up");
            Arrow arrow = shoot(helper, scene.foe, new Vec3(11.5, 2, 11.5), new Vec3(2.5, 2, 2.5));
            helper.runAfterDelay(15, () -> {
                helper.assertTrue(arrow.isRemoved(), "The shield stops the arrow at its shell");
                helper.assertTrue(scene.owner.getHealth() == scene.owner.getMaxHealth(), "The crew is untouched");
                helper.assertTrue(module(scene.hive).charge() < AegisModule.FULL, "Stopping it cost the shield charge");
                helper.succeed();
            });
        });
    }

    @GameTest(template = ARENA, batch = "ship_aegis_lets_crew_shoot", timeoutTicks = 100)
    public static void aegisLetsTheCrewShootOut(GameTestHelper helper) {
        Aegis scene = aegis(helper);
        helper.runAfterDelay(5, () -> {
            // Out to the east, clear of the husk in the far corner.
            Arrow arrow = shoot(helper, scene.owner, new Vec3(3.5, 2.5, 3.5), new Vec3(11.5, 3.5, 3.5));
            helper.runAfterDelay(6, () -> {
                helper.assertFalse(arrow.isRemoved(), "The crew's own shots fly out through the shield");
                helper.assertTrue(module(scene.hive).charge() == AegisModule.FULL, "They cost it nothing");
                helper.succeed();
            });
        });
    }

    @GameTest(template = ARENA, batch = "ship_aegis_takes_blows", timeoutTicks = 100)
    public static void aegisTakesBlowsFromOutsideForTheCrew(GameTestHelper helper) {
        Aegis scene = aegis(helper);
        helper.runAfterDelay(5, () -> {
            helper.assertFalse(scene.owner.hurt(scene.owner.damageSources().mobAttack(scene.foe), 4), "A blow from outside is taken by the shield");
            helper.assertTrue(scene.owner.getHealth() == scene.owner.getMaxHealth(), "The crew is untouched");
            helper.assertTrue(module(scene.hive).charge() == AegisModule.FULL - 8, "The shield pays twice the blow");
            scene.owner.invulnerableTime = 0;
            helper.assertTrue(scene.owner.hurt(scene.owner.damageSources().starve(), 1), "What has no outside source is not the shield's to take");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_aegis_breaks_and_returns", timeoutTicks = 200)
    public static void aegisBreaksUnderTooBigABlowAndComesBack(GameTestHelper helper) {
        Aegis scene = aegis(helper);
        helper.runAfterDelay(5, () -> {
            long now = helper.getLevel().getGameTime();
            helper.assertFalse(module(scene.hive).absorb(scene.hive, new Vec3(1, 0, 0), AegisModule.FULL + 1, now), "A blow bigger than the charge gets through");
            helper.assertTrue(module(scene.hive).down() && !module(scene.hive).raised(), "and breaks the shield");
            Arrow arrow = shoot(helper, scene.foe, new Vec3(11.5, 2, 11.5), new Vec3(2.5, 2, 2.5));
            helper.runAfterDelay(4, () -> helper.assertFalse(arrow.isRemoved() && module(scene.hive).charge() > 0, "A broken shield stops nothing"));
            helper.runAfterDelay(AegisModule.REBOOT + 5, () -> {
                helper.assertTrue(module(scene.hive).raised(), "It comes back up by itself");
                helper.assertTrue(module(scene.hive).charge() >= AegisModule.REBOOT_CHARGE, "with a part of its charge");
                helper.succeed();
            });
        });
    }

    @GameTest(template = ARENA, batch = "ship_aegis_explosions", timeoutTicks = 100)
    public static void aegisKeepsExplosionsOffWhatItCovers(GameTestHelper helper) {
        Aegis scene = aegis(helper);
        BlockPos glass = new BlockPos(7, 1, 1);
        helper.setBlock(glass, Blocks.GLASS);
        helper.runAfterDelay(5, () -> {
            Vec3 blast = helper.absoluteVec(new Vec3(10.5, 1.5, 1.5));
            helper.getLevel().explode(null, blast.x, blast.y, blast.z, 3, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
            helper.assertBlockPresent(Blocks.GLASS, glass);
            helper.assertTrue(module(scene.hive).charge() < AegisModule.FULL, "Holding it off cost the shield charge");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_aegis_grounded", timeoutTicks = 100)
    public static void redstoneLowersTheAegis(GameTestHelper helper) {
        Aegis scene = aegis(helper);
        helper.setBlock(AEGIS.east(), Blocks.REDSTONE_BLOCK);
        helper.runAfterDelay(5, () -> {
            helper.assertFalse(module(scene.hive).raised(), "A grounded hive holds no shield");
            Arrow arrow = shoot(helper, scene.foe, new Vec3(11.5, 2, 11.5), new Vec3(2.5, 2, 2.5));
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(module(scene.hive).charge() == AegisModule.FULL, "and stops nothing");
                helper.succeed();
            });
        });
    }

    // --- escort --------------------------------------------------------------------------------

    @GameTest(template = ARENA, batch = "ship_escort_patrol", timeoutTicks = 100)
    public static void escortWingsLaunchAndKeepWatch(GameTestHelper helper) {
        Scene scene = escort(helper, null);
        helper.runAfterDelay(40, () -> {
            for (EscortModule.Wing wing : escortOf(scene.hive).wings()) {
                helper.assertTrue(wing.phase() == EscortModule.Phase.PATROL, "A charged wing flies out on patrol by itself");
                helper.assertTrue(wing.charge() < EscortModule.FULL, "It flies on its own charge");
            }
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "ship_escort_fight", timeoutTicks = 240)
    public static void escortWingsGoAfterAMonster(GameTestHelper helper) {
        Scene scene = escort(helper, new Vec3(9.5, 1, 9.5));
        helper.succeedWhen(() -> {
            helper.assertTrue(scene.husk.getHealth() < scene.husk.getMaxHealth(), "The wings strike the husk");
            helper.assertTrue(scene.husk.getLastDamageSource().is(ShipDamage.ESCORT), "with their own blows");
            boolean fighting = false;
            for (EscortModule.Wing wing : escortOf(scene.hive).wings()) fighting |= wing.phase() == EscortModule.Phase.ENGAGE;
            helper.assertTrue(fighting, "A wing is out fighting it");
        });
    }

    @GameTest(template = ARENA, batch = "ship_escort_recall", timeoutTicks = 200)
    public static void escortWingsComeHomeWhenSwitchedOff(GameTestHelper helper) {
        Scene scene = escort(helper, null);
        helper.runAfterDelay(40, () -> {
            scene.hive.setEnabled(false);
            helper.succeedWhen(() -> {
                for (EscortModule.Wing wing : escortOf(scene.hive).wings()) {
                    helper.assertTrue(wing.phase() == EscortModule.Phase.DOCKED, "Switched off, every wing flies home and docks");
                }
            });
        });
    }

    @GameTest(template = ARENA, batch = "ship_escort_leash", timeoutTicks = 200)
    public static void escortWingsKeepToTheirLeash(GameTestHelper helper) {
        double leash = AddonConfig.ESCORT_LEASH.get();
        AddonConfig.ESCORT_LEASH.set(8.0);
        Scene scene = escort(helper, new Vec3(11.5, 1, 11.5));
        helper.runAfterDelay(120, () -> {
            AddonConfig.ESCORT_LEASH.set(leash);
            helper.assertTrue(scene.husk.getHealth() == scene.husk.getMaxHealth(), "A monster beyond the leash is left alone");
            for (EscortModule.Wing wing : escortOf(scene.hive).wings()) {
                helper.assertFalse(wing.phase() == EscortModule.Phase.ENGAGE, "No wing goes after it");
            }
            helper.succeed();
        });
    }

    /** A charged escort hive in a corner of an open floor, its owner beside it, and (unless null) a husk at {@code huskAt}. */
    private static Scene escort(GameTestHelper helper, Vec3 huskAt) {
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 2.5), "ship-crew");
        helper.setBlock(AEGIS, ModBlocks.SHIP_HIVES.get(ShipHiveKind.ESCORT).get().defaultBlockState().setValue(ShipHiveBlock.FACING, Direction.UP));
        ShipHiveBlockEntity hive = helper.getBlockEntity(AEGIS);
        retire(helper, AEGIS);
        hive.claim(owner);
        while (hive.energy().receiveEnergy(Integer.MAX_VALUE, false) > 0) {
            // Filled a tick's worth of input at a time.
        }
        Husk husk = null;
        if (huskAt != null) {
            husk = EntityType.HUSK.create(helper.getLevel());
            helper.assertTrue(husk != null, "Husk fixture");
            Vec3 at = helper.absoluteVec(huskAt);
            husk.moveTo(at.x, at.y, at.z);
            husk.setNoAi(true);
            husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            husk.setHealth(1000);
            helper.assertTrue(helper.getLevel().addFreshEntity(husk), "The husk enters the level");
        }
        return new Scene(hive, owner, husk);
    }

    private static EscortModule escortOf(ShipHiveBlockEntity hive) {
        return (EscortModule) hive.module();
    }

    private record Aegis(ShipHiveBlockEntity hive, ServerPlayer owner, Husk foe) { }

    private static final BlockPos AEGIS = new BlockPos(1, 1, 1);

    /**
     * A charged aegis hive in a corner of an open floor (its dome, eight blocks round it, covers that corner), its owner
     * beside it, and a husk standing well outside the dome in the far corner.
     */
    private static Aegis aegis(GameTestHelper helper) {
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 2.5), "ship-crew");
        helper.setBlock(AEGIS, ModBlocks.SHIP_HIVES.get(ShipHiveKind.AEGIS).get().defaultBlockState().setValue(ShipHiveBlock.FACING, Direction.UP));
        ShipHiveBlockEntity hive = helper.getBlockEntity(AEGIS);
        retire(helper, AEGIS);
        hive.claim(owner);
        while (hive.energy().receiveEnergy(Integer.MAX_VALUE, false) > 0) {
            // Filled a tick's worth of input at a time.
        }
        Husk foe = EntityType.HUSK.create(helper.getLevel());
        helper.assertTrue(foe != null, "Husk fixture");
        Vec3 at = helper.absoluteVec(new Vec3(11.5, 1, 11.5));
        foe.moveTo(at.x, at.y, at.z);
        foe.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(foe), "The husk enters the level");
        return new Aegis(hive, owner, foe);
    }

    /** An arrow of {@code shooter}'s, flying fast from {@code from} towards {@code to} (both relative to the test). */
    private static Arrow shoot(GameTestHelper helper, net.minecraft.world.entity.LivingEntity shooter, Vec3 from, Vec3 to) {
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setOwner(shooter);
        Vec3 start = helper.absoluteVec(from), way = helper.absoluteVec(to).subtract(start).normalize();
        arrow.moveTo(start.x, start.y, start.z);
        arrow.setDeltaMovement(way.scale(2.5));
        arrow.setNoGravity(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(arrow), "The arrow flies");
        return arrow;
    }

    /**
     * Takes the hive at {@code pos} away once the test is over. Test structures stay in the level after their tests,
     * and a hive left there would go on fighting whatever a later test sets up nearby.
     */
    private static void retire(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        var level = helper.getLevel();
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo test) { }
            @Override public void testPassed(GameTestInfo test, GameTestRunner runner) { level.setBlock(at, Blocks.AIR.defaultBlockState(), 3); }
            @Override public void testFailed(GameTestInfo test, GameTestRunner runner) { level.setBlock(at, Blocks.AIR.defaultBlockState(), 3); }
            @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) { }
        });
    }

    private static AegisModule module(ShipHiveBlockEntity hive) {
        return (AegisModule) hive.module();
    }

    private record Scene(ShipHiveBlockEntity hive, ServerPlayer owner, Husk husk) { }

    /**
     * An open floor with a charged lance hive facing up, its owner at {@code ownerAt} and, at {@code huskAt} (none if
     * null), a husk that neither moves nor dies soon.
     */
    private static Scene scene(GameTestHelper helper, Vec3 ownerAt, Vec3 huskAt) {
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 1; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer owner = DeviceTestSupport.player(helper, ownerAt, "ship-crew");
        helper.setBlock(HIVE, ModBlocks.SHIP_HIVES.get(ShipHiveKind.LANCE).get().defaultBlockState().setValue(ShipHiveBlock.FACING, Direction.UP));
        ShipHiveBlockEntity hive = helper.getBlockEntity(HIVE);
        retire(helper, HIVE);
        hive.claim(owner);
        while (hive.energy().receiveEnergy(Integer.MAX_VALUE, false) > 0) {
            // Filled a tick's worth of input at a time.
        }
        Husk husk = null;
        if (huskAt != null) {
            husk = EntityType.HUSK.create(helper.getLevel());
            helper.assertTrue(husk != null, "Husk fixture");
            Vec3 at = helper.absoluteVec(huskAt);
            husk.moveTo(at.x, at.y, at.z);
            husk.setNoAi(true);
            husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            husk.setHealth(1000);
            helper.assertTrue(helper.getLevel().addFreshEntity(husk), "The husk enters the level");
        }
        return new Scene(hive, owner, husk);
    }

    private static LanceModule lance(ShipHiveBlockEntity hive) {
        return (LanceModule) hive.module();
    }

    /** Empties the hive's battery (it gives nothing out through its capability). */
    private static void drain(ShipHiveBlockEntity hive) {
        var tag = hive.saveCustomOnly(hive.getLevel().registryAccess());
        tag.putInt("Energy", 0);
        hive.loadCustomOnly(tag, hive.getLevel().registryAccess());
    }

    private ShipHiveGameTests() {
    }
}
