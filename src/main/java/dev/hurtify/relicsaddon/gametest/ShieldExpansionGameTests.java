package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.server.ShieldCoverage;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import dev.hurtify.relicsaddon.shield.ShieldSettings;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

/** Contracts for the expandable, shared shield field. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldExpansionGameTests {
    @GameTest(template = "test_room")
    public static void shieldCapacityAndRadiusUpgradeToTheirPublishedMaximums(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack shield = equip(player);
        helper.assertTrue(ShieldParameters.capacity(player, shield) == 504 && ShieldParameters.maxRadius(player, shield) == 2,
                "Fresh shield starts with 504 shared HP and a two-block radius");
        RelicRuntime.ability(player, shield).setLevel(10);
        helper.assertTrue(ShieldParameters.capacity(player, shield) == 5000 && ShieldParameters.maxRadius(player, shield) == 12,
                "Level ten upgrades the buffer to 5000 HP and unlocks twelve blocks");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void settingsAndLargeBufferAreClampedAndDoNotResetDamage(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack shield = equip(player);
        RelicRuntime.ability(player, shield).setLevel(10);
        ShieldStackState damaged = ShieldStackState.DEFAULT.damageLocalCell(17, 4, 4, 20)
                .withCellsAndBuffer(ShieldStackState.DEFAULT.damageLocalCell(17, 4, 4, 20).cells(), 5000, java.util.List.of(), -1);
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), damaged);
        shield.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(99, "all"));
        helper.assertTrue(ShieldParameters.radius(player, shield) == 12 && ShieldParameters.settings(shield).coverage().equals("all"),
                "Settings respect the unlocked/configured maximum and retain the explicit all-entity mode");
        RelicRuntime.setEnabled(player, shield, false);
        RelicRuntime.setEnabled(player, shield, true);
        ShieldStackState restored = shield.get(ModDataComponents.SHIELD_STACK_STATE.get());
        helper.assertTrue(restored.sharedBuffer() == 5000 && restored.cellHp(17) == 8,
                "Toggle and radius/coverage changes never refill the shared buffer or damaged panel");
        ShieldStackState emptyPool = restored.withCellsAndBuffer(restored.cells(), 0, restored.moves(), restored.gatherTime());
        helper.assertTrue(emptyPool.repairFirstDamagedPanel(60, 5000).cellHp(17) == 9,
                "Repair keeps visible panels ahead of the 5000-HP common buffer");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void hostileArrowCreatedInsideFieldIsConsumedImmediately(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        ItemStack shield = equip(player);
        Arrow arrow = arrow(player, 1, -1, 2);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player) && arrow.isRemoved(),
                "A hostile arrow created inside the field is intercepted at time zero");
        ShieldImpact impact = shield.get(ModDataComponents.SHIELD_IMPACT.get());
        helper.assertTrue(impact != null && impact.distance() >= 0 && state(shield).sharedBuffer() < 504,
                "Inside interception emits a real local impact and pays shield HP");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void ownerAndTamedPetProjectilesAreExcluded(GameTestHelper helper) {
        ServerPlayer player = fieldPlayer(helper);
        ItemStack shield = equip(player);
        Arrow own = arrow(player, 1, -1, 2);
        own.setOwner(player);
        Wolf wolf = new Wolf(EntityType.WOLF, helper.getLevel());
        wolf.setOwnerUUID(player.getUUID());
        Arrow petArrow = arrow(player, 1, -1, 2);
        petArrow.setOwner(wolf);
        helper.assertFalse(ShieldProjectileInterceptor.threatens(own, player) || ShieldProjectileInterceptor.intercept(own, player),
                "Owner projectiles do not create an inside panel");
        helper.assertFalse(ShieldProjectileInterceptor.threatens(petArrow, player) || ShieldProjectileInterceptor.intercept(petArrow, player),
                "Tamed-pet projectiles do not create an inside panel");
        helper.assertTrue(state(shield).sharedBuffer() == 504, "Friendly shots consume no shield HP");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void allCoverageProtectsNearbyZombieButNotOutsideVictim(GameTestHelper helper) {
        ServerPlayer owner = fieldPlayer(helper);
        ItemStack shield = equip(owner);
        shield.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(2, "all"));
        helper.getLevel().addNewPlayer(owner);
        Zombie inside = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        Zombie outside = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        inside.setPos(owner.position().add(.5, 0, 0));
        outside.setPos(owner.position().add(4, 0, 0));
        try {
            helper.assertTrue(helper.getLevel().addFreshEntity(inside) && helper.getLevel().addFreshEntity(outside),
                    "Coverage fixtures are spawned");
            DamageSource source = mobAttack(helper, outside);
            LivingIncomingDamageEvent protectedHit = new LivingIncomingDamageEvent(inside, new DamageContainer(source, 4));
            ShieldController.onIncomingDamage(protectedHit);
            helper.assertTrue(protectedHit.getAmount() == 0 && state(shield).sharedBuffer() == 500,
                    "All mode lets the owner shield a nearby non-allied living entity from melee");
            LivingIncomingDamageEvent outsideHit = new LivingIncomingDamageEvent(outside, new DamageContainer(source, 4));
            ShieldController.onIncomingDamage(outsideHit);
            helper.assertTrue(outsideHit.getAmount() == 4 && !ShieldCoverage.covers(owner, shield, outside),
                    "Victims beyond the configured field radius remain unprotected");
        } finally {
            inside.discard();
            outside.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void levelTenCanSetFiveBlockFieldBeforeTwoBlockBoundary(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack shield = equip(player);
        RelicRuntime.ability(player, shield).setLevel(10);
        shield.set(ModDataComponents.SHIELD_SETTINGS.get(), new ShieldSettings(5, "owner"));
        double radius = ShieldParameters.radius(player, shield);
        ShieldField.Crossing expanded = ShieldField.intercept(new Vec3(0, 0, 6), new Vec3(0, 0, -1), 2, radius);
        ShieldField.Crossing defaultRadius = ShieldField.intercept(new Vec3(0, 0, 6), new Vec3(0, 0, -1), 2, 2);
        helper.assertTrue(radius == 5 && expanded != null && expanded.time() == 1 && defaultRadius == null,
                "A chosen five-block radius intercepts before the old two-block boundary enters the tick window");
        helper.succeed();
    }

    private static ItemStack equip(ServerPlayer player) {
        ItemStack shield = new ItemStack(ModItems.RF_SHIELD.get());
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, shield);
        RelicRuntime.ability(player, shield).setLevel(0);
        RelicRuntime.ability(player, shield).getResearchData().complete();
        return shield;
    }

    private static ServerPlayer fieldPlayer(GameTestHelper helper) {
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        player.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        return player;
    }

    private static Arrow arrow(ServerPlayer player, double distance, double speed, double damage) {
        Arrow arrow = new Arrow(EntityType.ARROW, player.level());
        arrow.setPos(player.position().add(0, ShieldField.CENTER_Y, distance));
        arrow.setDeltaMovement(0, 0, speed);
        arrow.setBaseDamage(damage);
        arrow.setNoGravity(true);
        return arrow;
    }

    private static DamageSource mobAttack(GameTestHelper helper, Zombie attacker) {
        return new DamageSource(helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageTypes.MOB_ATTACK), attacker);
    }

    private static ShieldStackState state(ItemStack shield) {
        return shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
    }

    private ShieldExpansionGameTests() { }
}
