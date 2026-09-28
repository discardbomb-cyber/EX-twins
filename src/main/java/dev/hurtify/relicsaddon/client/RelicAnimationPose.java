package dev.hurtify.relicsaddon.client;

/** Time-only motion formulas, kept free of Minecraft client state for deterministic pose checks. */
public final class RelicAnimationPose {
    public static final double RF_CYCLE_TICKS = 240.0D;
    public static final double RF_DEPLOY_TICKS = 40.0D;
    public static final double RF_DEPLOYED_HOLD_END_TICKS = 160.0D;
    public static final double RF_FOLD_END_TICKS = 200.0D;
    public static final double RF_WING_OFFSET_TICKS = 4.0D;
    public static final double RF_FOLDED_DEGREES = -58.0D;
    public static final double RF_DEPLOYED_DEGREES = 5.0D;
    public static final double RF_IDLE_DEGREES = 1.5D;
    public static final double MANA_DRONE_CYCLE_TICKS = 160.0D;
    public static final double MANA_DRONE_MIN_OFFSET = .025D;
    public static final double MANA_DRONE_MAX_OFFSET = .075D;
    public static final double MANA_DRONE_MAX_TILT_DEGREES = 7.0D;

    private RelicAnimationPose() {
    }

    public static float sharedBob(double time) {
        return (float) (Math.sin(time * 0.10D) * 0.012D);
    }

    public static float coreDegrees(double time) {
        double degrees = time * 0.055D % 360.0D;
        return (float) (degrees < 0.0D ? degrees + 360.0D : degrees);
    }

    public static double rfWingAngleRadians(int index) {
        return Math.toRadians(45.0D + index * 90.0D);
    }

    /** Returns the wing's folded-to-deployed progress for its staggered 240-tick cycle. */
    public static float rfDeployment(double ticks, int index) {
        double phase = rfCyclePhase(ticks, index);
        if (phase < RF_DEPLOY_TICKS) {
            return (float) smoothstep(phase / RF_DEPLOY_TICKS);
        }
        if (phase < RF_DEPLOYED_HOLD_END_TICKS) {
            return 1.0F;
        }
        if (phase < RF_FOLD_END_TICKS) {
            return (float) (1.0D - smoothstep((phase - RF_DEPLOYED_HOLD_END_TICKS) / RF_DEPLOY_TICKS));
        }
        return 0.0F;
    }

    public static float rfWingFlexDegrees(double time, int index) {
        double phase = rfCyclePhase(time, index);
        double flex = RF_FOLDED_DEGREES
                + (RF_DEPLOYED_DEGREES - RF_FOLDED_DEGREES) * rfDeployment(time, index);
        if (phase >= RF_DEPLOY_TICKS && phase < RF_DEPLOYED_HOLD_END_TICKS) {
            double holdProgress = (phase - RF_DEPLOY_TICKS) / (RF_DEPLOYED_HOLD_END_TICKS - RF_DEPLOY_TICKS);
            flex += RF_IDLE_DEGREES * Math.sin(Math.PI * holdProgress) * Math.sin(Math.PI * holdProgress);
        }
        return (float) flex;
    }

    private static double rfCyclePhase(double ticks, int index) {
        if (!Double.isFinite(ticks)) {
            return 0.0D;
        }
        double phase = (ticks - index * RF_WING_OFFSET_TICKS) % RF_CYCLE_TICKS;
        return phase < 0.0D ? phase + RF_CYCLE_TICKS : phase;
    }

    private static double smoothstep(double progress) {
        return progress * progress * (3.0D - 2.0D * progress);
    }

    public static double shellAngleRadians(int index, int shells) {
        return Math.PI * 2.0D * index / shells;
    }

    public static float manaBreath(double time, int index) {
        return (float) (0.012D * Math.sin(time * 0.12D + index * 0.7D));
    }

    public static float manaTiltDegrees(double time, int index) {
        return (float) (Math.sin(time * 0.10D + index) * 3.0D);
    }

    /** Six radially staggered petals breathe between .025 and .075 block units. */
    public static float manaDroneShellOffset(double time, int index) {
        return (float) (midpoint(MANA_DRONE_MIN_OFFSET, MANA_DRONE_MAX_OFFSET)
                + halfRange(MANA_DRONE_MIN_OFFSET, MANA_DRONE_MAX_OFFSET) * Math.sin(dronePhase(time,
                MANA_DRONE_CYCLE_TICKS, index, 6)));
    }

    public static float manaDroneShellTiltDegrees(double time, int index) {
        return (float) (MANA_DRONE_MAX_TILT_DEGREES * Math.sin(dronePhase(time, MANA_DRONE_CYCLE_TICKS, index, 6)));
    }

    private static double dronePhase(double time, double cycleTicks, int index, int shells) {
        double ticks = Double.isFinite(time) ? time : 0.0D;
        return Math.PI * 2.0D * ticks / cycleTicks + shellAngleRadians(index, shells);
    }

    private static double midpoint(double low, double high) {
        return (low + high) * .5D;
    }

    private static double halfRange(double low, double high) {
        return (high - low) * .5D;
    }
}
