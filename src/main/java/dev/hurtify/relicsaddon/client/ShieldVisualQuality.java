package dev.hurtify.relicsaddon.client;

enum ShieldVisualQuality {
    LOW(64.0D),
    BALANCED(96.0D),
    HIGH(128.0D);

    private final double renderDistance;

    ShieldVisualQuality(double renderDistance) {
        this.renderDistance = renderDistance;
    }

    double renderDistanceSqr() {
        return renderDistance * renderDistance;
    }
}
