package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.adapter.out.world.McVectors;
import dev.hurtify.relicsaddon.domain.shield.CoverageRule;
import dev.hurtify.relicsaddon.shield.ShieldParameters;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ShieldCoverage {
    public static boolean friendly(Player owner, Entity entity) {
        if (entity == owner || entity.isAlliedTo(owner) || owner.isAlliedTo(entity)) return true;
        if (entity instanceof TamableAnimal pet) {
            if (owner.getUUID().equals(pet.getOwnerUUID())) return true;
            return pet.getOwner() != null && owner.isAlliedTo(pet.getOwner());
        }
        return false;
    }

    public static boolean covers(Player owner, ItemStack shield, LivingEntity victim) {
        if (!owner.isAlive() || owner.isSpectator() || !victim.isAlive() || victim.isSpectator()) return false;
        if (owner == victim) return true;
        String mode = ShieldParameters.settings(shield).coverage();
        if ("owner".equals(mode) || ("allies".equals(mode) && !friendly(owner, victim))) return false;
        double radius = ShieldParameters.radius(owner, shield);
        return CoverageRule.withinRadius(McVectors.toDomain(victim.getBoundingBox().getCenter()), McVectors.toDomain(owner.position()), radius);
    }
    private ShieldCoverage() { }
}
