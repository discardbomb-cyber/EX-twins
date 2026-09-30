package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;
import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.power.DeviceEnergy;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.ShieldBarrier;
import dev.hurtify.relicsaddon.server.ShieldEffectGuard;
import dev.hurtify.relicsaddon.server.ShieldStrike;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldImpactHistory;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.shield.ShieldSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
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
    public static void attackersEffectsAreCutOff(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.MANA_SHIELD, 0);
        Zombie zombie = zombie(helper, player.position().add(3, 0, 0));
        int charge = DevicePower.energy(shield).mana();
        helper.assertFalse(player.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 1), zombie), "An attacker's poison is cut off");
        helper.assertFalse(player.hasEffect(MobEffects.POISON), "No poison reaches the wearer");
        helper.assertTrue(DevicePower.energy(shield).mana() < charge, "Cutting an effect costs charge");
        helper.assertTrue(player.addEffect(new MobEffectInstance(MobEffects.WITHER, 100), null), "Effects nobody cast (a wither rose) still apply");
        helper.assertTrue(player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 100), zombie), "Helpful effects pass");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void aPartlyStoppedHitTrimsItsEffect(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Zombie zombie = zombie(helper, player.position().add(3, 0, 0));
        // The field stopped 6 of 8 damage in this tick, so a quarter of the hit got through.
        ShieldEffectGuard.recordHit(player, zombie, 6, 2);
        player.addEffect(new MobEffectInstance(MobEffects.WITHER, 200), zombie);
        MobEffectInstance wither = player.getEffect(MobEffects.WITHER);
        helper.assertTrue(wither != null && wither.getDuration() == 50, "A quarter of the wither stays: " + (wither == null ? "none" : wither.getDuration()));
        helper.succeed();
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
        ServerPlayer player = openArena(helper);
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
        DeviceTestSupport.close(helper, wolf.getHealth(), wolf.getMaxHealth(), "A tamed wolf is never struck");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aggressiveMobsAreStruckAndThrownBack(GameTestHelper helper) {
        ServerPlayer player = openArena(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Husk husk = husk(helper, player.position().add(.6, 0, .2));
        int charge = DevicePower.energy(shield).rf();
        ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        helper.assertTrue(husk.getHealth() < husk.getMaxHealth(), "The field strikes a hostile mob it holds back");
        helper.assertTrue(husk.getLastDamageSource() != null && husk.getLastDamageSource().is(ShieldStrike.DISCHARGE),
                "An RF shield strikes with its own discharge damage");
        Vec3 outward = new Vec3(.6, 0, .2).normalize();
        double throwSpeed = husk.getDeltaMovement().x * outward.x + husk.getDeltaMovement().z * outward.z;
        helper.assertTrue(throwSpeed > .8, "The strike throws the mob away from the wearer (outward speed " + throwSpeed + ")");
        helper.assertTrue(charge - DevicePower.energy(shield).rf() == (1 + DevicePower.STRIKE) * DevicePower.FE_PER_POINT,
                "Holding and striking are paid from the battery: " + charge + " -> " + DevicePower.energy(shield).rf());
        ShieldImpact last = shield.getOrDefault(ModDataComponents.SHIELD_IMPACTS.get(), ShieldImpactHistory.EMPTY).impacts().getLast();
        helper.assertTrue(last.isStrike() && last.normal().dot(outward) > .95, "The strike shows on the shell facing the mob");
        helper.assertTrue(RelicRuntime.progression(shield).experience() > 0, "Striking earns device experience");
        DeviceTestSupport.close(helper, player.getHealth(), 20, "The wearer is untouched");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void rfDischargeIsSoftenedByArmour(GameTestHelper helper) {
        strikeArmoured(helper, RelicRole.RF_SHIELD, ShieldStrike.DISCHARGE, false);
    }

    @GameTest(template = ARENA)
    public static void manaBurstIgnoresArmour(GameTestHelper helper) {
        strikeArmoured(helper, RelicRole.MANA_SHIELD, ShieldStrike.MANA_BURST, true);
    }

    @GameTest(template = ARENA)
    public static void twinSurgeIgnoresArmour(GameTestHelper helper) {
        strikeArmoured(helper, RelicRole.TWINS_SHIELD, ShieldStrike.TWIN_SURGE, true);
    }

    @GameTest(template = ARENA, timeoutTicks = 80)
    public static void strikesRespectTheirCooldown(GameTestHelper helper) {
        ServerPlayer player = openArena(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Vec3 inside = player.position().add(.6, 0, .2);
        Husk husk = husk(helper, inside);
        ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        float afterFirst = husk.getHealth();
        helper.assertTrue(afterFirst < husk.getMaxHealth(), "The first touch is struck");
        husk.moveTo(inside.x, inside.y, inside.z);
        ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        DeviceTestSupport.close(helper, husk.getHealth(), afterFirst, "A second touch inside the cooldown is only pushed");
        helper.runAfterDelay(ShieldParameters.strikeCooldown() + 1, () -> {
            husk.moveTo(inside.x, inside.y, inside.z);
            ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(husk.getHealth() < afterFirst, "After the cooldown the field strikes again");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA)
    public static void neutralMobsAreStruckOnlyOnceTheyAttack(GameTestHelper helper) {
        ServerPlayer player = openArena(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Vec3 inside = player.position().add(.6, 0, .2);
        ZombifiedPiglin piglin = mob(helper, EntityType.ZOMBIFIED_PIGLIN, inside);
        ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        DeviceTestSupport.close(helper, piglin.getHealth(), piglin.getMaxHealth(), "A calm zombified piglin is held out but not struck");
        helper.assertTrue(piglin.getBoundingBox().getCenter().subtract(player.position().add(0, ShieldField.CENTER_Y, 0)).horizontalDistance()
                > ShieldParameters.radius(player, DeviceTestSupport.charm(helper, player, 0)) - .05, "It is still kept out of the field");
        piglin.setTarget(player);
        piglin.moveTo(inside.x, inside.y, inside.z);
        ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        helper.assertTrue(piglin.getHealth() < piglin.getMaxHealth(), "Once it attacks the wearer, it is struck");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void aFieldNeverAbsorbsAStrike(GameTestHelper helper) {
        ServerPlayer player = openArena(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        // Covering everyone puts the mob under the field too; the strike must still land.
        shield.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(2, "all"));
        Husk husk = husk(helper, player.position().add(.6, 0, .2));
        int integrity = DeviceTestSupport.integrity(shield);
        helper.assertTrue(husk.hurt(helper.getLevel().damageSources().source(ShieldStrike.DISCHARGE, player), 4),
                "Strike damage is registered and lands");
        helper.assertTrue(husk.getHealth() < husk.getMaxHealth(), "The mob loses health");
        helper.assertTrue(DeviceTestSupport.integrity(shield) == integrity, "The field spends nothing on its own strike");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void droneHitsDoNotKnockBack(GameTestHelper helper) {
        ServerPlayer player = openArena(helper);
        Husk husk = husk(helper, player.position().add(4, 0, 0));
        helper.assertTrue(husk.hurt(helper.getLevel().damageSources().source(dev.hurtify.relicsaddon.server.HiveCombatController.DRONE_SHOT, player), 3),
                "A drone hit lands");
        helper.assertTrue(husk.getHealth() < husk.getMaxHealth(), "The drone hit hurts");
        helper.assertTrue(husk.getDeltaMovement().horizontalDistanceSqr() < 1e-6, "A drone hit never knocks the target back");
        helper.assertTrue(husk.getLastDamageSource() != null && husk.getLastDamageSource().getEntity() == player,
                "The owner gets the kill credit");
        helper.succeed();
    }

    /** A mob in full-strength armour is struck once; arcane strikes ignore the armour, a discharge does not. */
    private static void strikeArmoured(GameTestHelper helper, RelicRole role, ResourceKey<DamageType> type, boolean ignoresArmour) {
        ServerPlayer player = openArena(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, role, 0);
        Husk husk = husk(helper, player.position().add(.6, 0, .2));
        husk.getAttribute(Attributes.ARMOR).setBaseValue(20);
        husk.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(8);
        ShieldBarrier.onPlayerTick(new PlayerTickEvent.Post(player));
        float lost = husk.getMaxHealth() - husk.getHealth();
        float nominal = ShieldParameters.strikeDamage(shield);
        helper.assertTrue(husk.getLastDamageSource() != null && husk.getLastDamageSource().is(type), role + " strikes with " + type.location());
        if (ignoresArmour) DeviceTestSupport.close(helper, lost, nominal, role + " goes straight through armour");
        else helper.assertTrue(lost > 0 && lost < nominal * .5, role + " is softened by armour: " + lost + " of " + nominal);
        helper.succeed();
    }

    /** The arena has fixtures; an open 9x9 floor lets only the field decide where mobs go. */
    static ServerPlayer openArena(GameTestHelper helper) {
        for (int x = 2; x <= 10; x++) for (int y = 1; y <= 4; y++) for (int z = 2; z <= 10; z++) {
            helper.setBlock(new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR);
        }
        return DeviceTestSupport.player(helper, new Vec3(6.5, 1, 6.5));
    }

    /** Husks are zombies that do not burn in daylight, so their health only changes when the field strikes. */
    static Husk husk(GameTestHelper helper, Vec3 at) {
        return mob(helper, EntityType.HUSK, at);
    }

    private static <T extends Mob> T mob(GameTestHelper helper, EntityType<T> type, Vec3 at) {
        T mob = type.create(helper.getLevel());
        helper.assertTrue(mob != null, type + " fixture");
        mob.moveTo(at.x, at.y, at.z);
        mob.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(mob), type + " enters the level");
        return mob;
    }

    static Zombie zombie(GameTestHelper helper, Vec3 at) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null, "Zombie fixture");
        zombie.moveTo(at.x, at.y, at.z);
        zombie.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(zombie), "The zombie enters the level");
        return zombie;
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void aBlowTheFieldTakesSetsTheSwarmOnTheAttacker(GameTestHelper helper) {
        ServerPlayer player = openArena(helper);
        DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.RF_HIVE, 1);
        Husk husk = husk(helper, player.position().add(3, 0, 0));
        float health = player.getHealth();
        helper.assertFalse(player.hurt(player.damageSources().mobAttack(husk), 4), "The field takes the whole blow");
        helper.assertTrue(player.getHealth() == health, "The wearer is untouched");
        dev.hurtify.relicsaddon.server.HiveCombatController.tick(player);
        var combat = hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), dev.hurtify.relicsaddon.drone.HiveCombatState.DEFAULT);
        helper.assertTrue(combat.active() && combat.targetId() == husk.getId(), "A blow on the field sets the swarm on the attacker");
        helper.succeed();
    }

    private ShieldDefenseGameTests() {
    }
}
