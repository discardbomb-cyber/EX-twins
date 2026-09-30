package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * Who a ship's hives fight for and whom they fight. Sworn to the ship are the hives' owners, their teams and their
 * pets: never fired on, whatever they do. Whoever else rides the ship (players and peaceful creatures; a monster that
 * climbs aboard is still a monster) is friendly until they hurt the crew. Threats are monsters, creatures going for
 * the crew, and anyone who has hurt the crew or the ship lately.
 */
final class ShipAllies {
    /** Threat scores: a grudge outweighs everything, then creatures after the crew, then a creeper at the rail. */
    static final double GRUDGE = 60, HUNTING_CREW = 40, MONSTER = 20, CREEPER_CLOSE = 25, PLAYER = 20;

    /** Whether {@code entity} is sworn to the ship: an owner, a teammate or a pet of theirs, or a player in creative. */
    static boolean sworn(ShipBrain brain, Entity entity) {
        if (entity instanceof Player player && (brain.owns(player.getUUID()) || player.isCreative() || player.isSpectator())) return true;
        if (entity instanceof OwnableEntity pet) {
            UUID master = pet.getOwnerUUID();
            if (master != null && brain.owns(master)) return true;
            Entity owner = pet.getOwner();
            if (owner != null && owner != entity && teammate(brain, owner)) return true;
        }
        return teammate(brain, entity);
    }

    /** Whether the hives hold their fire for {@code entity}: sworn to the ship, or a peaceful rider without a grudge. */
    static boolean friendly(ShipBrain brain, Entity entity, long now) {
        if (sworn(brain, entity)) return true;
        return !(entity instanceof Enemy) && aboard(brain, entity) && !(entity instanceof LivingEntity living && brain.grudges(living, now));
    }

    /** How much of a threat {@code entity} is to the ship, 0 for none. */
    static double threat(ShipBrain brain, LivingEntity entity, long now) {
        if (!entity.isAlive() || entity.isRemoved() || entity.isSpectator() || entity.isInvulnerable() || entity instanceof ArmorStand) return 0;
        if (sworn(brain, entity)) return 0;
        boolean grudge = brain.grudges(entity, now);
        if (entity instanceof Player) {
            if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isPvpAllowed()) return 0;
            if (grudge) return GRUDGE;
            if (aboard(brain, entity)) return 0;
            return AddonConfig.SPEC.isLoaded() && AddonConfig.SHIP_TARGET_PLAYERS.get() ? PLAYER : 0;
        }
        if (!grudge && !(entity instanceof Enemy) && aboard(brain, entity)) return 0;
        double score = grudge ? GRUDGE : 0;
        if (entity instanceof Mob mob && mob.getTarget() != null && mob.getTarget() != entity && friendly(brain, mob.getTarget(), now)) {
            score = Math.max(score, HUNTING_CREW);
        }
        if (entity instanceof Enemy) {
            score = Math.max(score, MONSTER);
            if (entity instanceof Creeper && brain.near(entity.position(), 8)) score += CREEPER_CLOSE;
        }
        return score;
    }

    /** Whether {@code entity} is on the same team as one of the hives' owners (asked of the owner when they are on). */
    static boolean teammate(ShipBrain brain, Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        for (UUID owner : brain.owners()) {
            Player player = level.getPlayerByUUID(owner);
            if (player == null) player = level.getServer().getPlayerList().getPlayer(owner);
            if (player != null && player != entity && (player.isAlliedTo(entity) || entity.isAlliedTo(player))) return true;
        }
        Team team = entity.getTeam();
        if (team == null) return false;
        for (String name : brain.ownerNames()) {
            PlayerTeam theirs = level.getScoreboard().getPlayersTeam(name);
            if (theirs != null && theirs == team) return true;
        }
        return false;
    }

    /** Whether {@code entity} stands on (or rides something on) the brain's ship. */
    static boolean aboard(ShipBrain brain, Entity entity) {
        UUID ship = brain.ship();
        if (ship == null) return false;
        SubLevelAccess on = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(entity);
        return on != null && ship.equals(on.getUniqueId());
    }

    private ShipAllies() {
    }
}
