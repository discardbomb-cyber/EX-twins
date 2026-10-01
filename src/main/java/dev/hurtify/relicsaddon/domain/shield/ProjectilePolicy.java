package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.math.Trig;

/** Which projectiles the field stops in flight, and what stopping one costs. */
public final class ProjectilePolicy {
    /**
     * Arrows and the intercept tag, never tridents. The server's ignore list overrides everything; its
     * intercept list adds types, tridents included. Facts are asked in that order, and only as far as needed.
     */
    public static boolean supported(ProjectileFacts facts) {
        if (facts.removed()) return false;
        if (facts.ignoredListed()) return false;
        if (facts.noPhysicsArrow()) return false;
        if (facts.interceptListed()) return true;
        if (facts.trident()) return false;
        return facts.arrow() || facts.inInterceptTag();
    }

    /**
     * Field integrity an impact costs: an arrow's damage from its speed (a critical one rounded up and
     * raised by half, plus one), 1 to 10 000; a fixed cost for everything else.
     */
    public static int impactCost(ProjectileFacts facts) {
        if (facts.arrow()) {
            double damage = facts.baseDamage() * facts.speed();
            if (facts.crit()) damage = Math.ceil(damage) * 1.5D + 1;
            return Double.isFinite(damage) ? Math.clamp(Trig.ceil(damage), 1, 10000) : 10000;
        }
        return switch (facts.costClass()) {
            case HEAVY_8 -> 8;
            case SMALL_FIREBALL_5 -> 5;
            case LIGHT_1 -> 1;
            case OTHER_4 -> 4;
        };
    }

    private ProjectilePolicy() { }
}
