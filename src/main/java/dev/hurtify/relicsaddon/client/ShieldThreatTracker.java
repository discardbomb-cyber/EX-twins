package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.shield.ShieldField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class ShieldThreatTracker {
    private static final Map<UUID, List<ShieldResponse.Threat>> THREATS = new HashMap<>();

    public static void onTick(ClientTickEvent.Post event) {
        THREATS.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        List<Projectile> projectiles = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof Projectile projectile && ShieldProjectileInterceptor.supported(projectile)) projectiles.add(projectile);
        }
        if (projectiles.isEmpty()) return;
        for (var player : minecraft.level.players()) {
            if (!player.isAlive() || player.isSpectator() || player.distanceToSqr(minecraft.player) > 64 * 64) continue;
            ItemStack shield = EquippedRelicSetResolver.findFirstActive(player, RelicRole.EQUIPMENT_SLOT, RelicRole.shields()).orElse(ItemStack.EMPTY);
            if (shield.isEmpty()) continue;
            ShieldStackState state = shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
            List<ShieldResponse.Threat> incoming = new ArrayList<>();
            for (Projectile projectile : projectiles) {
                if (!ShieldProjectileInterceptor.threatens(projectile, player)) continue;
                var crossing = ShieldProjectileInterceptor.crossing(projectile, player, ShieldField.PREVIEW_TICKS, shield);
                if (crossing == null || (state.sharedBuffer() == 0 && state.cellHp(ShieldController.selectCell(player, crossing.normal())) == 0
                        && dev.hurtify.relicsaddon.relic.ShieldUpgrades.gathering(player, shield) == 0)
                        || !ShieldProjectileInterceptor.unobstructed(projectile, crossing.time())) continue;
                incoming.add(new ShieldResponse.Threat(crossing.normal(), crossing.time()));
            }
            incoming.sort(Comparator.comparingDouble(ShieldResponse.Threat::ticks));
            if (!incoming.isEmpty()) THREATS.put(player.getUUID(), List.copyOf(incoming.subList(0, Math.min(8, incoming.size()))));
        }
    }

    static List<ShieldResponse.Threat> threats(UUID player) {
        return THREATS.getOrDefault(player, List.of());
    }

    private ShieldThreatTracker() {
    }
}
