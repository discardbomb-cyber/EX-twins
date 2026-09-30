package dev.hurtify.relicsaddon.domain.hive;

import java.util.List;

/** The swarm's recent world-space events, which clients draw. */
public final class ShotLog {
    /** Ticks an event stays in the log after its impact. */
    public static final int VISUAL_TICKS = 40;

    /** The events still worth drawing at {@code now}, at most the newest {@link HiveCombatState#MAX_SHOTS}. */
    public static List<HiveCombatState.Shot> recent(List<HiveCombatState.Shot> shots, long now) {
        List<HiveCombatState.Shot> recent = shots.stream().filter(shot -> now - shot.impactAt() <= VISUAL_TICKS).toList();
        int start = Math.max(0, recent.size() - HiveCombatState.MAX_SHOTS);
        return List.copyOf(recent.subList(start, recent.size()));
    }

    private ShotLog() { }
}
