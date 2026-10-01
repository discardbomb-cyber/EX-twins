package dev.hurtify.relicsaddon.domain.shield;

import dev.hurtify.relicsaddon.domain.math.Vec3d;

/** Whether a creature stands inside a field. */
public final class CoverageRule {
    /** Whether {@code victimCentre} lies within {@code radius} of the field centre above {@code ownerFeet}, the boundary included. */
    public static boolean withinRadius(Vec3d victimCentre, Vec3d ownerFeet, double radius) {
        return victimCentre.distanceToSqr(ownerFeet.add(0, ShieldField.CENTER_Y, 0)) <= radius * radius;
    }

    private CoverageRule() { }
}
