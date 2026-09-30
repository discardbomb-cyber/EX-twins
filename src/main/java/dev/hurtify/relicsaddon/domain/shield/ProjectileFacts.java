package dev.hurtify.relicsaddon.domain.shield;

/** What the field asks about a projectile. Each fact is read only when it is asked for. */
public interface ProjectileFacts {
    /** Fixed field-integrity costs of projectiles that are not arrows. */
    enum CostClass { HEAVY_8, SMALL_FIREBALL_5, LIGHT_1, OTHER_4 }

    boolean removed();

    /** The server's ignore list names its type. */
    boolean ignoredListed();

    /** An arrow without physics: a loyalty trident flying home, not an attack. */
    boolean noPhysicsArrow();

    /** The server's intercept list names its type. */
    boolean interceptListed();

    boolean trident();

    boolean arrow();

    /** Its type is in the {@code shield_interceptable_projectiles} tag. */
    boolean inInterceptTag();

    /** A critical arrow. */
    boolean crit();

    /** An arrow's base damage. */
    double baseDamage();

    /** Its speed in blocks per tick. */
    double speed();

    CostClass costClass();
}
