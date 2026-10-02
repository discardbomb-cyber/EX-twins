package dev.hurtify.relicsaddon.client.workbench;

import dev.hurtify.relicsaddon.client.fx.ExFx;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Tick-driven Photon accents; a block renderer can share this adapter with the preview. */
public final class RfWorkbenchEffects {
    private final Vec3[] previous = new Vec3[4];
    private String lastClip = "";
    private long lastTick = Long.MIN_VALUE;

    public void tick(Level level, Vec3 origin, RfWorkbenchModel model, RfWorkbenchModel.Pose pose) {
        long tick = level.getGameTime();
        if (lastTick == tick) return;
        lastTick = tick;
        String clip = pose.clip();
        boolean changed = !clip.equals(lastClip);
        boolean orbit = "crafting".equals(clip);
        boolean open = orbit || "charged_open".equals(clip) || clip.startsWith("crafting_");
        Vec3 core = model.point(pose, "pulsing_core", origin);
        if (open && tick % (orbit ? 10 : 24) == 0) ExFx.rfWorkbenchCore(level, core, orbit);
        if (open && tick % 4 == 0) {
            int sector = (int) ((tick / 4) % 3), edge = (int) ((tick / 12) % 3);
            Vec3 wall = model.point(pose, "wall_contact_" + sector + "_" + edge, origin);
            Vec3 coreContact = model.point(pose, "core_contact_" + ((tick / 4) % 4), origin);
            // Alternate the flash origin between the moving frame and the rotating core shell.
            if ((tick / 4) % 2 == 0) ExFx.rfWorkbenchArc(level, wall, coreContact);
            else ExFx.rfWorkbenchArc(level, coreContact, wall);
        }
        for (int i = 0; i < 4; i++) {
            Vec3 node = model.point(pose, "orbit_corner_" + i, origin);
            if (changed && ("player_approach".equals(clip) || "crafting_start".equals(clip) || "crafting_end".equals(clip))) {
                ExFx.rfWorkbenchUnfold(level, node);
            }
            // Effects start at the current sample and move to the next tick's exact pose.
            if (orbit) {
                var next = model.pose(clip, pose.time() + .05f);
                Vec3 to = model.point(next, "orbit_corner_" + i, origin);
                if (!changed && previous[i] != null) ExFx.rfWorkbenchTrail(level, node, to);
                if (tick % 16 == i * 4) {
                    Vec3 contact = model.point(pose, "core_contact_" + i, origin);
                    ExFx.rfWorkbenchArc(level, contact, node);
                }
            }
            previous[i] = node;
        }
        lastClip = clip;
    }

    public void clear() {
        java.util.Arrays.fill(previous, null);
        lastClip = ""; lastTick = Long.MIN_VALUE;
    }
}
