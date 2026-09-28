package dev.hurtify.relicsaddon.gametest.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.client.ShieldResponse;
import dev.hurtify.relicsaddon.client.ShieldVisualRenderer;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Native renderer exercise, not a claim of multiplayer or live combat acceptance. */
final class NativeShieldGallery extends Screen {
    private final long started = Util.getMillis();
    private final Set<String> captures = new HashSet<>();
    private final Set<String> saved = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private long completedAt;
    private static final Vec3 NORMAL = new Vec3(.35, .1, .93).normalize();
    private static final boolean CELLS = Boolean.getBoolean("relics_addon.cellSmoke");
    private static final List<String> STAGES = CELLS ? List.of("approach", "absorption", "break", "idle", "hp-full", "hp-half", "hp-critical", "gather-start", "gather-end", "wave", "overlapping-waves")
            : List.of("approach", "absorption", "break", "idle");

    NativeShieldGallery() {
        super(Component.literal("Relics / Reactive Shield Test"));
    }

    private double animationTick() {
        return (Util.getMillis() - started) / 50.0D % (CELLS ? 290 : 100);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF202226);
        graphics.drawString(font, title, 12, 10, 0xFFF1F4F8, false);
        double tick = animationTick();
        String phase = tick < 25 ? "Approach" : tick < 45 ? "Absorption" : tick < 70 ? "Panel break" : "Idle";
        if (tick >= 100) phase = tick < 125 ? "420 cells / full local HP" : tick < 150 ? "210 cells / 50% local HP" : tick < 175 ? "90 cells / critical local HP" : tick < 225 ? "Gathering surviving cells / no healing" : "Impact wave / across the sphere";
        if (tick >= 250) phase = "Independent impact waves / continuous animation";
        graphics.drawString(font, phase, 12, 24, 0xFFC1D0D8, false);
        int cellWidth = width / 3;
        float scale = Math.min((cellWidth - 24) / 4.7F, (height - 70) / 4.7F);
        RelicRole[] roles = {RelicRole.RF_SHIELD, RelicRole.MANA_SHIELD, RelicRole.TWINS_SHIELD};
        List<ShieldResponse.Threat> threats = tick < 25 ? List.of(new ShieldResponse.Threat(NORMAL, (25 - tick) * 4 / 25)) : List.of();
        int struck = ShieldTopology.INSTANCE.nearest(-NORMAL.x, NORMAL.y, NORMAL.z);
        ShieldImpact impact = tick >= 25 && tick < 45 ? new ShieldImpact(NORMAL, 25, 0, 6, false)
                : tick >= 45 && tick < 70 ? new ShieldImpact(NORMAL, 45, 0, 6, true, List.of(struck))
                : tick >= 225 ? new ShieldImpact(NORMAL, 225, 0, 6, false) : null;
        ShieldStackState state = tick >= 45 && tick < 70 ? ShieldStackState.DEFAULT.damageCell(struck, 12, 6, 45) : ShieldStackState.DEFAULT;
        if (tick >= 100 && tick < 225) {
            threats = java.util.Arrays.stream(ShieldTopology.INSTANCE.cells()).map(cell -> {
                float[] c = cell.center();
                return new ShieldResponse.Threat(new Vec3(-c[0], c[1], c[2]), 0);
            }).toList();
            var health = new java.util.ArrayList<>(state.cells());
            for (int id = 0; id < ShieldTopology.CELL_COUNT; id++) {
                int order = id * 17 % ShieldTopology.CELL_COUNT;
                health.set(id, tick < 125 ? 12 : tick < 150 ? (order < 210 ? 12 : 0) : tick < 175 ? (order < 90 ? 3 : 0) : 12);
            }
            state = state.withCellsAndBuffer(health, 0, List.of(), -1);
            if (tick >= 175) {
                int target = ShieldTopology.INSTANCE.nearest(-NORMAL.x, NORMAL.y, NORMAL.z);
                state = state.damageCell(target, 12, 0, 0);
                for (int id : ShieldTopology.INSTANCE.nearestTo(target)) if (ShieldTopology.INSTANCE.adjacent(target, id)) state = state.damageCell(id, 12, 0, 0);
            }
        }
        for (int index = 0; index < roles.length; index++) {
            int cx = index * cellWidth + cellWidth / 2;
            int cy = height / 2 + 18;
            graphics.drawString(font, Component.translatable("item.relics_addon." + roles[index].itemId()), index * cellWidth + 12, 44, 0xFFD5DBE2, false);
            // A neutral 1.8-block scale reference stays visible beneath the translucent field.
            int top = Math.round(cy - scale * .88F), bottom = Math.round(cy + scale * .92F);
            graphics.fill(Math.round(cx - scale * .22F), top, Math.round(cx + scale * .22F), top + Math.round(scale * .42F), 0xFF66727A);
            graphics.fill(Math.round(cx - scale * .3F), top + Math.round(scale * .44F), Math.round(cx + scale * .3F), bottom, 0xFF465158);
            graphics.flush();
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 180);
            graphics.pose().scale(scale, -scale, scale);
            graphics.pose().mulPose(new Quaternionf().rotationY(.20F));
            var buffers = minecraft.renderBuffers().bufferSource();
            ShieldStackState shown = tick >= 180 && tick < 225 && roles[index] != RelicRole.TWINS_SHIELD
                    ? ShieldCellDefense.gather(state, ShieldTopology.INSTANCE.nearest(-NORMAL.x, NORMAL.y, NORMAL.z), 3, 180) : state;
            List<ShieldImpact> waves = tick >= 265 ? List.of(new ShieldImpact(NORMAL, 252, 0, 6, false),
                    new ShieldImpact(new Vec3(-.8, .45, .5).normalize(), 265, 0, 6, false))
                    : tick >= 250 ? List.of(new ShieldImpact(NORMAL, 252, 0, 6, false))
                    : impact == null ? List.of() : List.of(impact);
            List<ShieldResponse.Threat> shownThreats = roles[index] == RelicRole.MANA_SHIELD && threats.size() > 8
                    ? List.of(new ShieldResponse.Threat(new Vec3(.85, .15, .5).normalize(), 0)) : threats;
            ShieldVisualRenderer.renderWaves(roles[index], shown, waves, shownThreats, tick,
                    buffers.getBuffer(ShieldVisualRenderer.renderType()), graphics.pose().last().pose(), 0, 0, 0, 0, 1, false,
                    new Vec3(-Math.sin(.20), 0, Math.cos(.20)));
            buffers.endBatch(ShieldVisualRenderer.renderType());
            graphics.pose().popPose();
        }
    }

    void capture(Minecraft minecraft) {
        if (Boolean.getBoolean("relics_addon.captureAndExit")) {
            String prefix = "relics-shields-" + minecraft.getMainRenderTarget().width + "x" + minecraft.getMainRenderTarget().height + "-";
            boolean complete = STAGES.stream()
                    .allMatch(stage -> saved.contains(prefix + stage + ".png"));
            if (complete && completedAt == 0) completedAt = Util.getMillis();
            if (complete && Util.getMillis() - completedAt > 1000) minecraft.stop();
        }
        double tick = animationTick();
        String phase = tick >= 14 && tick < 24 ? "approach" : tick >= 27 && tick < 34 ? "absorption"
                : tick >= 47 && tick < 54 ? "break" : tick >= 80 && tick < 90 ? "idle" : null;
        if (CELLS && tick >= 105) phase = tick < 120 ? "hp-full" : tick >= 130 && tick < 145 ? "hp-half"
                : tick >= 155 && tick < 170 ? "hp-critical" : tick >= 180 && tick < 183 ? "gather-start"
                : tick >= 191 && tick < 210 ? "gather-end" : tick >= 236 && tick < 246 ? "wave"
                : tick >= 268 && tick < 277 ? "overlapping-waves" : null;
        if (phase == null) return;
        String name = "relics-shields-" + minecraft.getMainRenderTarget().width + "x" + minecraft.getMainRenderTarget().height + "-" + phase + ".png";
        if (captures.add(name)) Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(), message -> {
            RelicsAddon.LOGGER.info("Native shield evidence: {}", message.getString());
            saved.add(name);
        });
    }

    @Override
    public void onClose() {
        minecraft.setScreen(new TitleScreen());
    }
}
