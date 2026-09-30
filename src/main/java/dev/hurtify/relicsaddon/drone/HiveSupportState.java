package dev.hurtify.relicsaddon.drone;

/** Transient support deployment; no persisted healing session can resume on another owner. */
public record HiveSupportState(boolean active, long changedAt) {
    public static final HiveSupportState DEFAULT = new HiveSupportState(false, 0);
    public HiveSupportState { changedAt = Math.max(0, changedAt); }
}
