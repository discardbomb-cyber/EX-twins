package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;
import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldSettings;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.server.ShieldEffectGuard;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.server.ShieldStrike;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The server's shield lists: what passes, what is absorbed, which projectiles are stopped and which
 * effects are kept. Each test swaps a list only for the synchronous part of its body, so the tests
 * running beside it in the same batch always see the defaults.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldConfigGameTests {
    @GameTest(template = TEMPLATE)
    public static void listedDamageTypesPassTheField(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        withList(AddonConfig.SHIELD_PASSING_DAMAGE_TYPES, List.of("#minecraft:is_fire", "Not An Id!", "#", "minecraft:no_such_damage"), () -> {
            int before = DeviceTestSupport.integrity(shield);
            helper.assertFalse(player.hurt(player.damageSources().wither(), 2), "Unlisted damage is still absorbed");
            helper.assertTrue(before - DeviceTestSupport.integrity(shield) == 2, "The field pays for the wither hit");
            int after = DeviceTestSupport.integrity(shield);
            helper.assertTrue(player.hurt(player.damageSources().inFire(), 2), "Damage in a listed tag passes the field, bad entries aside");
            DeviceTestSupport.close(helper, player.getHealth(), 18, "Listed damage reaches the wearer");
            helper.assertTrue(DeviceTestSupport.integrity(shield) == after, "The field spends nothing on damage it lets through");
        });
        helper.assertFalse(ShieldController.passesField(player.damageSources().inFire()), "With the list restored, fire is absorbed again");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void absorbListOverridesThePassTagButNeverEatsStrikes(GameTestHelper helper) {
        ServerPlayer player = ShieldDefenseGameTests.openArena(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        // Covering everyone puts the mob under the field too, so only the strike rule lets the blow land.
        shield.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(2, "all"));
        Husk husk = ShieldDefenseGameTests.husk(helper, player.position().add(.6, 0, .2));
        withList(AddonConfig.SHIELD_ABSORBED_DAMAGE_TYPES,
                List.of("minecraft:starve", "#relics_addon:shield_strike", "#relics_addon:swarm_damage"), () -> {
            int before = DeviceTestSupport.integrity(shield);
            helper.assertFalse(player.hurt(player.damageSources().starve(), 2), "Listed starvation is absorbed although shield_passes lets it through");
            DeviceTestSupport.close(helper, player.getHealth(), 20, "Absorbed starvation never reaches the wearer");
            helper.assertTrue(before - DeviceTestSupport.integrity(shield) == 2, "The field pays for the starvation");
            int after = DeviceTestSupport.integrity(shield);
            helper.assertTrue(husk.hurt(helper.getLevel().damageSources().source(ShieldStrike.DISCHARGE, player), 4), "A strike still lands");
            helper.assertTrue(husk.getHealth() < husk.getMaxHealth() && DeviceTestSupport.integrity(shield) == after,
                    "The field never absorbs its own strike, whatever the absorb list says");
            helper.assertFalse(ShieldController.passesField(helper.getLevel().damageSources().source(HiveCombatController.SWARM_STRIKE, player)),
                    "Swarm blows are not waved through every field: another player's field stops them");
            husk.invulnerableTime = 0;
            float health = husk.getHealth();
            helper.assertTrue(husk.hurt(helper.getLevel().damageSources().source(HiveCombatController.SWARM_STRIKE, player), 3), "The owner's swarm lands");
            helper.assertTrue(husk.getHealth() < health && DeviceTestSupport.integrity(shield) == after,
                    "A field never shelters a stranger inside it from its own owner's swarm");
            withList(AddonConfig.SHIELD_PASSING_DAMAGE_TYPES, List.of("minecraft:starve"), () ->
                    helper.assertTrue(ShieldController.passesField(player.damageSources().starve()), "The pass list wins over the absorb list"));
        });
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void ignoredProjectilesAreNotStopped(GameTestHelper helper) {
        ServerPlayer player = ShieldDefenseGameTests.openArena(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Zombie shooter = ShieldDefenseGameTests.zombie(helper, player.position().add(4, 0, 0));
        Arrow arrow = incoming(helper, player, shield, new Arrow(helper.getLevel(), shooter, new ItemStack(Items.ARROW), null));
        helper.assertTrue(ShieldProjectileInterceptor.supported(arrow), "Arrows are stopped in flight by default");
        int before = DeviceTestSupport.integrity(shield);
        withList(AddonConfig.SHIELD_IGNORED_PROJECTILES, List.of("#minecraft:arrows"), () -> {
            helper.assertFalse(ShieldProjectileInterceptor.supported(arrow), "An arrow in an ignored tag is not a target");
            helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow, player), "The field does not stop an ignored arrow");
            helper.assertFalse(arrow.isRemoved(), "The ignored arrow keeps flying");
            helper.assertTrue(DeviceTestSupport.integrity(shield) == before, "Letting it through costs nothing");
        });
        int cost = ShieldProjectileInterceptor.impactCost(arrow);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player) && arrow.isRemoved(), "With the list restored the arrow is stopped");
        helper.assertTrue(before - DeviceTestSupport.integrity(shield) == cost, "The field pays for the arrow");
        helper.succeed();
    }

    @GameTest(template = ARENA)
    public static void listedProjectilesAreStoppedInFlight(GameTestHelper helper) {
        ServerPlayer player = ShieldDefenseGameTests.openArena(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.RF_SHIELD, 0);
        Zombie shooter = ShieldDefenseGameTests.zombie(helper, player.position().add(4, 0, 0));
        Snowball snowball = incoming(helper, player, shield, new Snowball(helper.getLevel(), shooter));
        helper.assertFalse(ShieldProjectileInterceptor.supported(snowball), "Snowballs fly through by default");
        withList(AddonConfig.SHIELD_INTERCEPTED_PROJECTILES, List.of("minecraft:snowball"), () -> {
            withList(AddonConfig.SHIELD_IGNORED_PROJECTILES, List.of("minecraft:snowball"), () ->
                    helper.assertFalse(ShieldProjectileInterceptor.supported(snowball), "The ignore list wins over the intercept list"));
            int before = DeviceTestSupport.integrity(shield);
            helper.assertTrue(ShieldProjectileInterceptor.intercept(snowball, player), "A listed snowball is intercepted");
            helper.assertTrue(snowball.isRemoved(), "The listed snowball is stopped at the shell");
            helper.assertTrue(before - DeviceTestSupport.integrity(shield) == ShieldProjectileInterceptor.impactCost(snowball),
                    "The field pays the snowball's cost");
        });
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void keptEffectsAreNeitherCutNorTrimmed(GameTestHelper helper) {
        ServerPlayer player = DeviceTestSupport.player(helper);
        ItemStack shield = DeviceTestSupport.equip(helper, player, RelicRole.MANA_SHIELD, 0);
        Zombie zombie = ShieldDefenseGameTests.zombie(helper, player.position().add(3, 0, 0));
        withList(AddonConfig.SHIELD_KEPT_EFFECTS, List.of("minecraft:poison", "minecraft:wither"), () -> {
            int charge = DevicePower.energy(shield).mana();
            helper.assertTrue(player.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 1), zombie), "A kept effect is not cut off");
            helper.assertTrue(player.hasEffect(MobEffects.POISON), "The attacker's poison reaches the wearer");
            helper.assertTrue(DevicePower.energy(shield).mana() == charge, "Keeping an effect costs nothing");
            ShieldEffectGuard.recordHit(player, zombie, 6, 2);
            player.addEffect(new MobEffectInstance(MobEffects.WITHER, 200), zombie);
            MobEffectInstance wither = player.getEffect(MobEffects.WITHER);
            helper.assertTrue(wither != null && wither.getDuration() == 200, "A kept effect is not trimmed: " + (wither == null ? "none" : wither.getDuration()));
            helper.assertFalse(player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200), zombie), "Effects off the list are still cut");
        });
        helper.succeed();
    }

    /** Swaps a server config list for {@code body} and puts the old list back even when an assertion fails. */
    private static void withList(ModConfigSpec.ConfigValue<List<? extends String>> option, List<String> entries, Runnable body) {
        List<? extends String> previous = option.get();
        option.set(entries);
        try {
            body.run();
        } finally {
            option.set(previous);
        }
    }

    /** Half a block outside the shell, flying straight at the wearer: the projectile meets the shell this tick. */
    private static <P extends Projectile> P incoming(GameTestHelper helper, ServerPlayer player, ItemStack shield, P projectile) {
        Vec3 start = player.position().add(0, ShieldField.CENTER_Y, ShieldParameters.radius(player, shield) + .5);
        projectile.moveTo(start.x, start.y, start.z);
        projectile.setDeltaMovement(0, 0, -1.5);
        helper.assertTrue(helper.getLevel().addFreshEntity(projectile), "The projectile enters the level");
        return projectile;
    }

    private ShieldConfigGameTests() {
    }
}
