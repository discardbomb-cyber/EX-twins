package dev.hurtify.relicsaddon.gametest;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ManaCombatGameTests {
    @GameTest(template = "field_arena")
    public static void manaBlocksActualZombieAttack(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        var shield = new ItemStack(ModItems.MANA_SHIELD.get());
        RelicRuntime.ability(player, shield).getResearchData().complete();
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm",0,shield);
        var zombie = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        zombie.setPos(player.position().add(0,0,1));
        zombie.setNoAi(true);
        helper.getLevel().addFreshEntity(zombie);
        try {
            zombie.doHurtTarget(player);
            helper.assertTrue(player.getHealth() == 20, "Actual zombie attack must not hurt through intact mana cell");
            var hp = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            helper.assertTrue(hp.totalIntegrity() < ShieldStackState.MAX_TOTAL_INTEGRITY && hp.lastAbsorbed() > 0, "Mana spends HP on actual melee");
            RelicRuntime.setEnabled(player, shield, false);
            player.invulnerableTime = 0;
            zombie.doHurtTarget(player);
            helper.assertTrue(player.getHealth() < 20, "Control: the same zombie hurts with the shield disabled");
        } finally { zombie.discard(); }
        helper.succeed();
    }

    @GameTest(template = "field_arena", timeoutTicks = 100)
    public static void manaInterceptsActualSkeletonShot(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        player.setPos(helper.absoluteVec(new Vec3(6.5,2,6.5)));
        var shield = new ItemStack(ModItems.MANA_SHIELD.get());
        RelicRuntime.ability(player, shield).getResearchData().complete();
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm",0,shield);
        helper.getLevel().addNewPlayer(player);
        var skeleton = new Skeleton(EntityType.SKELETON, helper.getLevel());
        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.BOW));
        skeleton.setNoAi(true);
        skeleton.setPos(player.position().add(0,0,4));
        helper.getLevel().addFreshEntity(skeleton);
        skeleton.performRangedAttack(player,1);
        var shots = helper.getLevel().getEntitiesOfClass(AbstractArrow.class, skeleton.getBoundingBox().inflate(2), a -> a.getOwner() == skeleton);
        helper.assertTrue(shots.size() == 1, "Skeleton must create a real owned arrow");
        var arrow = shots.getFirst();
        // Remove random spread, retaining the real skeleton projectile, damage and owner.
        arrow.setDeltaMovement(player.getEyePosition().subtract(arrow.position()).normalize().scale(1.6));
        arrow.setNoGravity(true);
        helper.runAfterDelay(12, () -> {
            skeleton.discard();
            var state = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            helper.assertTrue(arrow.isRemoved(), "Real ticking skeleton arrow intercepted");
            helper.assertTrue(state.totalIntegrity() < ShieldStackState.MAX_TOTAL_INTEGRITY && state.lastAbsorbed() > 0 && player.getHealth() == 20,
                    "Mana absorbs actual skeleton arrow at boundary without player injury");
            helper.succeed();
        });
    }
}
