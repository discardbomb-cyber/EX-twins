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
                require(healed.distanceToSqr(owner.add(0, 1.12, 0)) < 1.1, "healers must stay around their owner");
                require(HiveFormation.healing(owner, 35, index, count, type, 187.25, 0).equals(idle[index]), "healers recall to the same belt slots");
                require(combat[index].distanceToSqr(target) < 49, "combat position escaped target");
                Vec3 blend = HiveFormation.position(owner, 35, target, 1.1, 1.9, index, count, type, 187.25, .5);
                requireFinite(blend, "transition");
                for (double progress : new double[]{0, .01, .219, .22, .5, .78, .781, .99, 1}) {
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
        require(HiveFormation.travelWeight(0) == 0 && HiveFormation.travelWeight(1) == 0
                && HiveFormation.travelWeight(.5) == 1, "drop morph preserves endpoint formations");
        System.out.println("Hive formation: 1..250 slots finite, bounded, separated and family-distinct");
    }
    private static void requireFinite(Vec3 point, String what) { require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z), what + " must be finite"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
