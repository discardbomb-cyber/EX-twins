package dev.hurtify.relicsaddon.domain.energy;

public final class BatteryRulesCheck {
    public static void main(String[] args) {
        requireSplit(100, 100, 20, 10, 10, "Twins share a cost evenly");
        requireSplit(100, 100, 21, 11, 10, "An odd point goes to the RF battery");
        requireSplit(3, 100, 20, 3, 17, "A short RF battery hands the rest to mana");
        requireSplit(100, 4, 20, 16, 4, "A short mana battery hands the rest to RF");
        requireSplit(50, 0, 20, 20, 0, "An RF-only device pays from RF");
        requireSplit(0, 50, 20, 0, 20, "A mana-only device pays from mana");
        for (int rf = 0; rf <= 40; rf++) for (int mana = 0; mana <= 40; mana++) for (int cost = 1; cost <= rf + mana; cost++) {
            int[] share = BatteryRules.split(rf, mana, cost);
            require(share[0] + share[1] == cost && share[0] >= 0 && share[1] >= 0 && share[0] <= rf && share[1] <= mana,
                    "Split must pay exactly and never overdraw: rf=" + rf + " mana=" + mana + " cost=" + cost);
        }
        require(ExperienceCurve.pointsForLevel(0) == 0 && ExperienceCurve.pointsForLevel(16) == 352
                && ExperienceCurve.pointsForLevel(17) == 394 && ExperienceCurve.pointsForLevel(30) == 1395
                && ExperienceCurve.pointsForLevel(32) == 1628, "Vanilla experience curve");
        System.out.println("Device power: Twins split, fallback, exact payment and vanilla XP curve verified");
    }

    private static void requireSplit(int rf, int mana, int cost, int fromRf, int fromMana, String message) {
        int[] share = BatteryRules.split(rf, mana, cost);
        require(share[0] == fromRf && share[1] == fromMana, message + ": got " + share[0] + "/" + share[1]);
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
