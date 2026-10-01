package dev.hurtify.relicsaddon.domain.device;

/** Device experience: what each level costs, what one blow earns, and how experience becomes levels and points. */
public final class ProgressionRules {
    /** Experience from one level to the next: 60, 120, 220 ... 2 040; 8 100 in total to reach level 10. */
    public static int experienceToNext(int level) { return 60 + 40 * level + 20 * level * level; }

    /** Experience for absorbing or dealing {@code value} damage, before the per-minute cap: a quarter of it, 1 to 3. */
    public static int gain(float value) { return Math.clamp(Math.round(value * .25F), 1, 3); }

    /** Adds {@code gain}, levelling up while it covers the next level; experience keeps accruing at the top level. */
    public static DeviceProgression addExperience(DeviceProgression before, int gain) {
        int experience = before.experience() + gain;
        int level = before.level(), points = before.points();
        while (level < DeviceProgression.MAX_LEVEL && experience >= experienceToNext(level)) { experience -= experienceToNext(level); level++; points++; }
        return new DeviceProgression(experience, level, points, before.upgrades());
    }

    private ProgressionRules() { }
}
