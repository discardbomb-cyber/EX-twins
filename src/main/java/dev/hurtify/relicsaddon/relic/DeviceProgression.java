package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.DeviceUpgrade;

/** Persistent, per-stack progression owned entirely by this mod: experience, level, points and upgrade ranks. */
public record DeviceProgression(int experience, int level, int points, int upgrades) {
    public static final int MAX_LEVEL = 10;
    public static final DeviceProgression DEFAULT = new DeviceProgression(0, 0, 0, 0);

    public DeviceProgression normalized() {
        return new DeviceProgression(Math.max(0, experience), Math.clamp(level, 0, MAX_LEVEL), Math.max(0, points), upgrades);
    }

    public int rank(String id) { return (upgrades >>> (DeviceUpgrade.byId(id).bit() * 2)) & 3; }
    public DeviceProgression withRank(String id, int rank) {
        DeviceUpgrade upgrade = DeviceUpgrade.byId(id);
        int shift = upgrade.bit() * 2;
        int mask = 3 << shift;
        return new DeviceProgression(experience, level, points, (upgrades & ~mask) | (Math.clamp(rank, 0, 3) << shift));
    }
    public DeviceProgression withPoints(int value) { return new DeviceProgression(experience, level, Math.max(0, value), upgrades); }
}
