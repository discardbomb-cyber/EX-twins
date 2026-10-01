package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import dev.hurtify.relicsaddon.server.HiveController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The Noctis core of the Twins spear and the Eclipse scythe: a charge (0 to {@link #MAX}) fed by their blows,
 * more by a blow on a sinner, topped up slowly by a worn Twins hive, and spent on their great works. The weapon's
 * lines burn brighter and more intricate the fuller it is.
 */
public final class NoctisCore {
    public static final int MAX = 100;
    /** What a blow feeds the core, and a blow on a sinner. */
    public static final int BLOW = 10, SINNER_BLOW = 20;
    /** How long after striking the owner a creature counts as a sinner, in ticks. */
    public static final int SIN_MEMORY = 100;
    /** The sinner's and the pinned creature's share of the blow's weight. */
    public static final float SIN_RESONANCE = 1.5F, ABSOLUTION = 2F;

    public static int charge(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.NOCTIS_CORE.get(), 0);
    }

    /** The core's charge as a share of its full (0 to 1). */
    public static double fullness(ItemStack stack) {
        return charge(stack) / (double) MAX;
    }

    public static void feed(ItemStack stack, int amount) {
        stack.set(ModDataComponents.NOCTIS_CORE.get(), Math.clamp(charge(stack) + amount, 0, MAX));
    }

    /** Spends {@code amount} if the core holds it; false, and nothing spent, otherwise. */
    public static boolean spend(ItemStack stack, int amount) {
        if (charge(stack) < amount) return false;
        stack.set(ModDataComponents.NOCTIS_CORE.get(), charge(stack) - amount);
        return true;
    }

    /** A sinner: a creature that struck {@code owner} lately, or has {@code owner} for its target. */
    public static boolean sinner(ServerPlayer owner, LivingEntity target) {
        if (target instanceof Mob mob && mob.getTarget() == owner) return true;
        if (owner.getLastHurtByMob() == target && owner.tickCount - owner.getLastHurtByMobTimestamp() < SIN_MEMORY) return true;
        return HiveCombatController.remembersAttacker(owner, target);
    }

    /** The worn, working Twins hive of {@code player}, or null. */
    public static ItemStack hive(Player player) {
        for (var equipped : HiveController.active(player)) if (equipped.type() == HiveType.TWINS) return equipped.stack();
        return null;
    }

    private NoctisCore() { }
}
