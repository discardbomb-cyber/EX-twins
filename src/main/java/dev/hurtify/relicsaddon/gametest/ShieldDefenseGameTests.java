package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;
import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.server.ShieldBarrier;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Simulated attacks against a worn shield: what the field must stop, and what it must let through. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldDefenseGameTests {
    @GameTest(template = TEMPLATE)
    public static void absorbedMeleeIsCancelledWithoutKnockback(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Zombie zombie = zombie(helper, player.position().add(3, 0, 0));
        int before = DeviceTestSupport.integrity(shield);
        boolean hurt = player.hurt(player.damageSources().mobAttack(zombie), 6);
        helper.assertFalse(hurt, "A fully absorbed hit must be cancelled, not applied as zero damage");
        DeviceTestSupport.close(helper, player.getHealth(), 20, "The wearer keeps full health");
        helper.assertTrue(player.hurtTime == 0 && player.getDeltaMovement().horizontalDistanceSqr() < 1e-6,
                "No hurt animation and no knockback from an absorbed hit");
        helper.assertTrue(before - DeviceTestSupport.integrity(shield) == 6, "The field pays for the hit: " + before + " -> " + DeviceTestSupport.integrity(shield));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void witherDamageIsAbsorbed(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.TWINS_SHIELD, 0);
        helper.assertFalse(player.hurt(player.damageSources().wither(), 2), "Wither damage is absorbed by the field");
        helper.assertFalse(player.hurt(player.damageSources().onFire(), 1), "Burning is absorbed by the field");
        DeviceTestSupport.close(helper, player.getHealth(), 20, "Status damage never reaches the wearer");
        helper.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void witherSkullNeitherHurtsNorWithers(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.TWINS_SHIELD, 0);
        Zombie shooter = zombie(helper, player.position().add(0, 0, 8));
        Vec3 target = player.position().add(0, 1, 0);
        Vec3 start = target.add(0, 0, 4);
        WitherSkull skull = new WitherSkull(helper.getLevel(), shooter, target.subtract(start).normalize());
        skull.moveTo(start.x, start.y, start.z);
        helper.assertTrue(helper.getLevel().addFreshEntity(skull), "The skull enters the level");
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(player.hasEffect(MobEffects.WITHER), "The skull must not wither the wearer through the field");
            DeviceTestSupport.close(helper, player.getHealth(), 20, "The skull must not hurt the wearer through the field");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void starvationAndTheVoidPassTheField(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        helper.assertTrue(player.hurt(player.damageSources().starve(), 2), "Starvation is not blocked by the field");
        DeviceTestSupport.close(helper, player.getHealth(), 18, "Starvation reaches the wearer");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void repeatedFireTicksRespectHitImmunity(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.MANA_SHIELD, 0);
        int start = DeviceTestSupport.integrity(shield);
        player.hurt(player.damageSources().inFire(), 1);
        player.hurt(player.damageSources().inFire(), 1);
        player.hurt(player.damageSources().inFire(), 1);
        helper.assertTrue(start - DeviceTestSupport.integrity(shield) == 1, "Hits inside the immunity window cost nothing extra");
        player.hurt(player.damageSources().inFire(), 3);
        helper.assertTrue(start - DeviceTestSupport.integrity(shield) == 3, "A stronger hit inside the window pays only the difference");
        helper.runAfterDelay(12, () -> {
            player.hurt(player.damageSources().inFire(), 1);
            helper.assertTrue(start - DeviceTestSupport.integrity(shield) == 4, "After the window a new hit is paid again");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void flatBatteryLetsDamageThrough(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        shield.set(ModDataComponents.DEVICE_ENERGY.get(), DeviceEnergy.EMPTY);
        helper.assertFalse(DevicePower.powered(player, shield), "An empty RF battery leaves the shield unpowered");
        Zombie zombie = zombie(helper, player.position().add(3, 0, 0));
        helper.assertTrue(player.hurt(player.damageSources().mobAttack(zombie), 4), "Without power the hit lands");
        DeviceTestSupport.close(helper, player.getHealth(), 16, "Without power the wearer takes the damage");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void hostileMobsArePushedOutOfTheField(GameTestHelper helper) {
        // The arena has fixtures; clear an open 9x9 floor so only the field decides where mobs go.
        for (int x = 2; x <= 10; x++) for (int y = 1; y <= 4; y++) for (int z = 2; z <= 10; z++) {
            helper.setBlock(new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR);
        }
        ServerPlayer player = DeviceTestSupport.player(helper, new Vec3(6.5, 1, 6.5));
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Zombie zombie = zombie(helper, player.position().add(.6, 0, .2));
        Wolf wolf = EntityType.WOLF.create(helper.getLevel());
        helper.assertTrue(wolf != null, "Wolf fixture");
        wolf.moveTo(player.getX() - .6, player.getY(), player.getZ());
        wolf.tame(player);
        helper.getLevel().addFreshEntity(wolf);
        Vec3 wolfBefore = wolf.position();
        for (int tick = 0; tick < 4; tick++) ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        Vec3 center = player.position().add(0, ShieldField.CENTER_Y, 0);
        double radius = ShieldParameters.radius(player, shield);
        double distance = zombie.getBoundingBox().getCenter().subtract(center).horizontalDistance();
        helper.assertTrue(distance >= radius - .05, "The zombie is held at the shell (" + distance + " of " + radius + ")");
        helper.assertTrue(wolf.position().distanceTo(wolfBefore) < 1e-6, "A tamed wolf is never pushed");
        helper.succeed();
    }

    private static Zombie zombie(GameTestHelper helper, Vec3 at) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null, "Zombie fixture");
        zombie.moveTo(at.x, at.y, at.z);
        zombie.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(zombie), "The zombie enters the level");
        return zombie;
    }

    private ShieldDefenseGameTests() {
    }
}
