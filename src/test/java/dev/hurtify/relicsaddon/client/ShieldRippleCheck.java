package dev.hurtify.relicsaddon.client;

public final class ShieldRippleCheck {
    public static void main(String[] args) {
        for (int age = 2; age <= 26; age += 4) {
            double front = ShieldRipple.front(age);
            double ahead = ShieldRipple.profile(Math.cos(front + .12), age);
            double behind = ShieldRipple.profile(Math.cos(front - .12), age);
            require(ahead > .25, "Crest must lead the front at age " + age);
            require(behind < -.2, "Trough must trail the front at age " + age);
            if (front + 1.2 <= Math.PI) require(Math.abs(ShieldRipple.profile(Math.cos(front + 1.2), age)) < .02,
                    "Surface ahead of the wave must be undisturbed at age " + age);
        }
        require(ShieldRipple.profile(1, -1) == 0 && ShieldRipple.profile(1, 36) == 0, "No idle or stale ripple");
        double max = 0;
        for (int age = 0; age < 36; age++) for (int step = 0; step <= 180; step++) {
            double value = ShieldRipple.profile(Math.cos(step * Math.PI / 180), age);
            require(Double.isFinite(value), "Finite ripple");
            max = Math.max(max, Math.abs(value));
        }
        require(max <= 1.05, "Ripple profile must stay within one unit, got " + max);
        require(ShieldRipple.fade(35.9) < .02, "Ripple fades out before the impact expires");
        require(ShieldRipple.strength(0F) == .45 && ShieldRipple.strength(50F) == 1, "Bounded hit strength");
        System.out.println("Shield ripple: crest leads, trough trails, calm ahead, bounded and fading");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
