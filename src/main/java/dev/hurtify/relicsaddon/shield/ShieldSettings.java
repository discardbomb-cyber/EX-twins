package dev.hurtify.relicsaddon.shield;

public record ShieldSettings(double radius, String coverage) {
    public static final ShieldSettings DEFAULT = new ShieldSettings(2, "allies");
    public ShieldSettings {
        radius = Double.isFinite(radius) ? Math.clamp(radius, 2, 24) : 2;
        if (!"owner".equals(coverage) && !"allies".equals(coverage) && !"all".equals(coverage)) coverage = "allies";
    }
}
