package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ProjectileFieldGameTests {
    @GameTest(template = "field_arena")
    public static void allShieldsStopArrowsAtTwoBlocks(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        for (var item : new net.minecraft.world.item.Item[] {ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get()}) {
            ItemStack shield = equip(player, new ItemStack(item));
            Arrow arrow = arrow(helper, player, 3, 2, 2);
            helper.assertTrue(ShieldProjectileInterceptor.supported(arrow), "Arrow supported");
            helper.assertTrue(ShieldProjectileInterceptor.threatens(arrow, player), "Arrow is incoming hostile");
            var crossing = ShieldProjectileInterceptor.crossing(arrow, player, 1);
            helper.assertTrue(crossing != null, "Crosses sphere within one tick");
            var clip = helper.getLevel().clip(new net.minecraft.world.level.ClipContext(arrow.position(),
                    arrow.position().add(arrow.getDeltaMovement().scale(crossing.time())),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, arrow));
            helper.assertTrue(ShieldProjectileInterceptor.unobstructed(arrow, crossing.time()),
                    "Path obstructed: " + clip.getType() + " " + helper.getLevel().getBlockState(clip.getBlockPos()) + " at " + clip.getBlockPos());
            helper.assertTrue(RelicRuntime.canOperate(player, shield), "Equipped ability usable");
            helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player), "Boundary interception");
            helper.assertTrue(arrow.isRemoved(), "Stopped arrow removed before touching player");
            helper.assertTrue(Math.abs(arrow.position().distanceTo(center(player)) - 2) < 1e-7, "Exact radius");
            helper.assertTrue(state(shield).sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 4
                    && state(shield).front() == 12 && player.getHealth() == 20, "Common buffer pays cost; no local or player damage");
            ShieldImpact impact = shield.get(ModDataComponents.SHIELD_IMPACT.get());
            helper.assertTrue(impact != null && impact.absorbed() == 4 && !impact.broken(), "Confirmed absorption pulse");
        }
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void highSpeedArrowCannotTunnelThroughField(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        Arrow arrow = arrow(helper, player, 5, 30, .2);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player) && arrow.isRemoved(), "Swept interception at 30 blocks/tick");
        helper.assertTrue(Math.abs(arrow.position().distanceTo(center(player)) - 2) < 1e-7, "Not intercepted at player center");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void brokenPanelLetsNextProjectileThrough(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        emptyBuffer(shield);
        Arrow arrow = arrow(helper, player, 3, 2, 6);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player) && arrow.isRemoved(), "Last integrity can finish a full block");
        helper.assertTrue(state(shield).front() == 0 && shield.get(ModDataComponents.SHIELD_IMPACT.get()).broken(), "Destruction event survives zero integrity");
        Arrow next = arrow(helper, player, 3, 2, 2);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(next, player), "Broken sector passes next projectile");
        helper.assertFalse(next.isRemoved(), "Next projectile remains alive");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void overpoweredArrowBreaksAndLosesAbsorbedDamage(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        emptyBuffer(shield);
        Arrow arrow = arrow(helper, player, 3, 2, 10);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player), "Overpowering hit still breaks the panel");
        helper.assertFalse(arrow.isRemoved(), "Remaining projectile penetrates");
        helper.assertTrue(state(shield).front() == 0 && Math.abs(arrow.getBaseDamage() - 4) < 1e-6, "Absorbed portion removed once");
        helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow, player), "Cannot consume same broken panel again");
        player.setYRot(90);
        var damage = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(player,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(player.damageSources().arrow(arrow, null), 8));
        dev.hurtify.relicsaddon.server.ShieldController.onIncomingDamage(damage);
        helper.assertTrue(damage.getAmount() == 8, "Already weakened arrow is not absorbed again after turning");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void ownOutgoingDisabledShotsIgnoredButInteriorHostileIntercepted(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        Arrow own = arrow(helper, player, 3, 2, 2);
        own.setOwner(player);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(own, player), "Own incoming projectile ignored");
        Arrow outgoing = arrow(helper, player, 3, -2, 2);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(outgoing, player), "Outgoing ignored");
        int beforeInterior = state(shield).sharedBuffer();
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow(helper, player, 1, 1, 2), player), "Hostile interior projectile intercepted locally");
        helper.assertTrue(state(shield).sharedBuffer() < beforeInterior, "Interior interception spends real buffer HP");
        RelicRuntime.setEnabled(player, shield, false);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow(helper, player, 3, 2, 2), player), "Disabled field");
        helper.assertTrue(state(shield).front() == 12, "Ignored hits consume nothing");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void wallBeforeBoundaryPreventsInterception(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        BlockPos wall = BlockPos.containing(center(player).add(0, 0, 2.5));
        var before = helper.getLevel().getBlockState(wall);
        helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        try {
            helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow(helper, player, 4, 3, 2), player), "Wall hit occurs before shield");
            helper.assertTrue(state(shield).front() == 12, "Wall-obstructed shot consumes nothing");
        } finally {
            helper.getLevel().setBlockAndUpdate(wall, before);
        }
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void fireballStopsButUtilityPearlIsUntouched(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        equip(player, new ItemStack(ModItems.MANA_SHIELD.get()));
        SmallFireball fireball = new SmallFireball(EntityType.SMALL_FIREBALL, helper.getLevel());
        fireball.setPos(center(player).add(0, 0, 3));
        fireball.setDeltaMovement(0, 0, -2);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(fireball, player) && fireball.isRemoved(), "Fireball intercepted without explosion");
        ThrownEnderpearl pearl = new ThrownEnderpearl(EntityType.ENDER_PEARL, helper.getLevel());
        pearl.setPos(center(player).add(0, 0, 3));
        pearl.setDeltaMovement(0, 0, -2);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(pearl, player), "Utility projectiles not erased");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void partialFireballPaysHpOnceAndOnlyOverflowHitsPlayer(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT.damagePanel(0, 10, 0, 0));
        emptyBuffer(shield);
        SmallFireball fireball = new SmallFireball(EntityType.SMALL_FIREBALL, helper.getLevel());
        fireball.setPos(center(player).add(0, 0, 3));
        fireball.setDeltaMovement(0, 0, -2);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(fireball, player) && !fireball.isRemoved(), "Fireball breaks two remaining HP and continues");
        helper.assertTrue(state(shield).front() == 0, "Facing cell is destroyed");
        player.setYRot(90);
        var source = new net.minecraft.world.damagesource.DamageSource(helper.getLevel().registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                .getHolderOrThrow(net.minecraft.world.damagesource.DamageTypes.FIREBALL), fireball, null);
        player.hurt(source, 5);
        helper.assertTrue(player.getHealth() == 17, "Only three damage above the paid two HP reaches the wearer");
        player.invulnerableTime = 0;
        player.hurt(source, 5);
        helper.assertTrue(player.getHealth() == 12 && state(shield).totalIntegrity()
                == ShieldStackState.MAX_TOTAL_INTEGRITY - ShieldStackState.MAX_SHARED_BUFFER - 12, "Credit consumed exactly once; no second cell absorbs after turning");
        helper.succeed();
    }

    @GameTest(template = "field_arena", timeoutTicks = 40)
    public static void flyingArrowTriggersRegisteredTickHook(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        helper.getLevel().addNewPlayer(player);
        Arrow arrow = arrow(helper, player, 5, 1, 2);
        arrow.setNoGravity(true);
        helper.getLevel().addFreshEntity(arrow);
        helper.runAfterDelay(8, () -> {
            helper.assertTrue(arrow.isRemoved(), "Live flying arrow stopped by the registered server tick hook");
            helper.assertTrue(player.getHealth() == 20 && state(shield).sharedBuffer() < ShieldStackState.MAX_SHARED_BUFFER
                    && state(shield).front() == 12, "Shield intercepted before any player or local-cell damage");
            helper.assertTrue(shield.get(ModDataComponents.SHIELD_IMPACT.get()) != null, "Live hit emitted synchronized impact data");
            helper.succeed();
        });
    }

    @GameTest(template = "test_room")
    public static void impactCodecAndRepairKeepDestructionPulse(GameTestHelper helper) {
        ShieldImpact impact = new ShieldImpact(new Vec3(1, 2, 3), 100, 2, 3.5F, true);
        var buffer = Unpooled.buffer();
        try {
            ShieldImpact.STREAM_CODEC.encode(buffer, impact);
            ShieldImpact restored = ShieldImpact.STREAM_CODEC.decode(buffer);
            helper.assertTrue(restored.normal().distanceTo(impact.normal()) < 1e-8 && restored.broken() && restored.gameTime() == 100,
                    "Impact network codec retains direction, time and destruction");
        } finally {
            buffer.release();
        }
        ItemStack shield = new ItemStack(ModItems.RF_SHIELD.get());
        shield.set(ModDataComponents.SHIELD_IMPACT.get(), impact);
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT.damagePanel(2, 12, 3.5F, 100).repairFirstDamagedPanel(150));
        helper.assertTrue(shield.get(ModDataComponents.SHIELD_IMPACT.get()).equals(impact), "Repair must not reset the visual impact clock");
        helper.succeed();
    }

    private static ItemStack equip(ServerPlayer player, ItemStack shield) {
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, shield);
        RelicRuntime.ability(player, shield).setLevel(0);
        RelicRuntime.ability(player, shield).getResearchData().complete();
        return shield;
    }

    private static ServerPlayer fieldPlayer(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        // GameTest puts the structure one block above its structure-block anchor.
        Vec3 position = helper.absoluteVec(new Vec3(6.5, 2, 6.5));
        player.setPos(position);
        helper.assertTrue(helper.getLevel().getBlockState(BlockPos.containing(position)).isAir(), "Field fixture feet must be above the floor");
        return player;
    }

    @GameTest(template = "field_arena")
    public static void interceptedPickupArrowKeepsItsItem(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        Arrow arrow = arrow(helper, player, 3, 2, 2);
        arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED;
        arrow.getPickupItemStackOrigin().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Retained arrow"));
        ItemStack expected = arrow.getPickupItemStackOrigin().copy();
        helper.assertTrue(ShieldProjectileInterceptor.intercept(arrow, player), "Pickup arrow intercepted");
        var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, arrow.getBoundingBox().inflate(1));
        helper.assertTrue(drops.size() == 1 && ItemStack.isSameItemSameComponents(expected, drops.getFirst().getItem()),
                "Recoverable ammunition and its components survive interception");
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void closerMobGetsTheShotBeforeShield(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        ItemStack shield = equip(player, new ItemStack(ModItems.RF_SHIELD.get()));
        var zombie = new net.minecraft.world.entity.monster.Zombie(EntityType.ZOMBIE, helper.getLevel());
        zombie.setPos(player.position().add(0, 0, 3.5));
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        try {
            helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow(helper, player, 5, 4, 2), player), "Closer entity impact wins");
            helper.assertTrue(state(shield).front() == 12, "No shield cost for shot already stopped by a closer entity");
        } finally {
            zombie.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void nearestPlayersBoundaryWinsOnce(GameTestHelper helper) {
        var farther = fieldPlayer(helper);
        var nearer = fieldPlayer(helper);
        nearer.setPos(nearer.position().add(0, 0, 1));
        ItemStack farShield = equip(farther, new ItemStack(ModItems.RF_SHIELD.get()));
        ItemStack nearShield = equip(nearer, new ItemStack(ModItems.MANA_SHIELD.get()));
        helper.getLevel().addNewPlayer(farther);
        helper.getLevel().addNewPlayer(nearer);
        Arrow arrow = arrow(helper, farther, 5, 4, 2);
        ShieldProjectileInterceptor.onEntityTick(new net.neoforged.neoforge.event.tick.EntityTickEvent.Pre(arrow));
        helper.assertTrue(arrow.isRemoved() && state(nearShield).sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 8, "Nearest crossing owns projectile");
        helper.assertTrue(state(farShield).equals(ShieldStackState.DEFAULT), "Second shield does not pay twice");
        helper.succeed();
    }

    private static ShieldStackState state(ItemStack shield) {
        return shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
    }

    private static void emptyBuffer(ItemStack stack) {
        var hp = state(stack);
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), hp.withCellsAndBuffer(hp.cells(), 0, hp.moves(), hp.gatherTime()));
    }

    private static Vec3 center(ServerPlayer player) {
        return player.position().add(0, ShieldField.CENTER_Y, 0);
    }

    private static Arrow arrow(GameTestHelper helper, ServerPlayer player, double distance, double speed, double damage) {
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(center(player).add(0, 0, distance));
        arrow.setDeltaMovement(0, 0, -speed);
        arrow.setBaseDamage(damage);
        arrow.setNoGravity(true);
        return arrow;
    }

    private ProjectileFieldGameTests() {
    }
}
