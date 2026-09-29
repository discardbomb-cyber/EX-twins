package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.drone.HiveFormation;
import dev.hurtify.relicsaddon.drone.HiveType;
import net.minecraft.world.phys.Vec3;

/** Pure geometry guard for all server/client shared swarm slots. */
public final class HiveFormationCheck {
    public static void main(String[] args) {
        Vec3 owner = new Vec3(3, 64, -2), target = new Vec3(-4, 63, 7);
        for (HiveType type : HiveType.values()) for (int count = 1; count <= HiveType.MAX_DRONES; count++) {
            Vec3[] idle = new Vec3[count], combat = new Vec3[count];
            for (int index = 0; index < count; index++) {
                idle[index] = HiveFormation.idle(owner, 35, index, count, type, 187.25);
                combat[index] = HiveFormation.combat(target, 1.1, 1.9, index, count, type, 187.25);
                requireFinite(idle[index], "idle"); requireFinite(combat[index], "combat");
                require(idle[index].distanceToSqr(HiveFormation.belt(owner, 35, type)) < .003, "docked drones must converge inside the belt");
                Vec3 healed = HiveFormation.healing(owner, 35, index, count, type, 187.25, 1);
                requireFinite(healed, "healing");
                double reach = Math.hypot(healed.x - owner.x, healed.z - owner.z);
                require(reach >= 1.1 && reach <= 2.8, "healers ring the owner outside arm's reach: " + reach);
                require(healed.y - owner.y > .3 && healed.y - owner.y < 1.75, "healers circle the owner's chest");
                require(HiveFormation.healing(owner, 35, index, count, type, 187.25, 0).equals(idle[index]), "healers recall to the same belt slots");
                require(combat[index].distanceToSqr(target) < 49, "combat position escaped target");
                double around = combat[index].distanceTo(target.add(0, 1.9 * .55, 0));
                require(around > 1.0, "a drone must not sit inside its target");
                Vec3 blend = HiveFormation.position(owner, 35, target, 1.1, 1.9, index, count, type, 187.25, .5);
                requireFinite(blend, "transition");
                require(HiveFormation.position(owner, 35, target, 1.1, 1.9, index, count, type, 187.25, 0).equals(idle[index]),
                        "a recalled drone is back in its hive slot");
                require(HiveFormation.position(owner, 35, target, 1.1, 1.9, index, count, type, 187.25, 1).equals(combat[index]),
                        "a deployed drone is at its combat station");
                for (double progress : new double[]{0, .01, .219, .22, .45, .5, .55, .78, .781, .99, 1}) {
                    Vec3 before = HiveFormation.position(owner, 35, target, 1.1, 1.9, index, count, type, 187.25, progress);
                    Vec3 after = HiveFormation.position(owner, 35, target, 1.1, 1.9, index, count, type, 187.251, progress + .00001);
                    requireFinite(before, "drop");
                    require(before.distanceToSqr(after) < .01, "drop transition must be continuous");
                }
            }
            for (int a = 0; a < count; a++) for (int b = a + 1; b < count; b++) {
                require(idle[a].distanceToSqr(idle[b]) > 1e-8, "coincident idle slots");
                require(combat[a].distanceToSqr(combat[b]) > 1e-8, "coincident combat slots");
            }
        }
        Vec3 rf = HiveFormation.idle(owner, 0, 0, 12, HiveType.RF, 10);
        Vec3 mana = HiveFormation.idle(owner, 0, 0, 12, HiveType.MANA, 10);
        Vec3 twins = HiveFormation.idle(owner, 0, 0, 12, HiveType.TWINS, 10);
        require(rf.distanceToSqr(mana) > 1e-5 && mana.distanceToSqr(twins) > 1e-5, "families must have distinct poses");
        // Departures are staggered: halfway through a deployment some drones are still home and some have landed.
        int home = 0, landed = 0;
        for (int index = 0; index < 200; index++) {
            double share = HiveFormation.stagger(index, HiveType.RF, .5);
            if (share == 0) home++;
            if (share == 1) landed++;
        }
        require(home == 0 && landed == 0 || home + landed < 200, "a deployment must be a stream, not one jump");
        require(HiveFormation.stagger(7, HiveType.MANA, 0) == 0 && HiveFormation.stagger(7, HiveType.MANA, 1) == 1, "streams start home and end on station");
        // A big swarm is a thick cloud, not a thin shell: its drones spread over a range of distances.
        double nearest = Double.MAX_VALUE, farthest = 0;
        Vec3 core = target.add(0, 1.9 * .55, 0);
        for (int index = 0; index < HiveType.MAX_DRONES; index++) {
            double distance = HiveFormation.combat(target, 1.1, 1.9, index, HiveType.MAX_DRONES, HiveType.RF, 50).distanceTo(core);
            nearest = Math.min(nearest, distance);
            farthest = Math.max(farthest, distance);
        }
        require(farthest - nearest > 1.5, "500 RF drones must fill a volume, not a shell (" + nearest + ".." + farthest + ")");
        require(HiveFormation.rings(HiveType.MAX_DRONES) > 1 && HiveFormation.rings(12) == 1, "big Mana swarms add rings");
        System.out.println("Hive formation: 1..500 slots finite, bounded, separated, family-distinct and streamed");
    }
    private static void requireFinite(Vec3 point, String what) { require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z), what + " must be finite"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
