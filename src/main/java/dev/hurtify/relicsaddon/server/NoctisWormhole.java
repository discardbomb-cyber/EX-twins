package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.network.NoctisPayloads;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.NoctisCore;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The wormhole: the Eclipse scythe planted in the ground. For {@link #SCAN} ticks its circuit reads the land round it
 * (the wielder stands still), then a dome bursts {@code scythe.wormholeRadius} blocks out: every creature inside is
 * struck and thrown through the wormhole, {@link #THROW_NEAR} to {@link #THROW_FAR} blocks off in some direction,
 * onto the surface there. The land itself stays whole.
 */
public final class NoctisWormhole {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(dev.hurtify.relicsaddon.RelicsAddon.MOD_ID, "noctis_void"));
    public static final int SCAN = 40, CORE_COST = NoctisCore.MAX, HIVE_COST = 300;
    public static final float DAMAGE_DEALT = 20;
    public static final double THROW_NEAR = 48, THROW_FAR = 96;

    private record Planted(ServerLevel level, ServerPlayer owner, Vec3 at, double radius, long burstsAt) { }

    private static final List<Planted> PLANTED = new ArrayList<>();

    /** Plants the scythe; false, and nothing spent, when the core or the hive is short or one is planted already. */
    public static boolean plant(ServerLevel level, ServerPlayer owner, ItemStack weapon, ItemStack hive) {
        if (planted(owner) || NoctisCore.charge(weapon) < CORE_COST || !DevicePower.canAfford(owner, hive, HIVE_COST)) return false;
        NoctisCore.spend(weapon, CORE_COST);
        DevicePower.drain(owner, hive, HIVE_COST);
        double radius = AddonConfig.SPEC.isLoaded() ? AddonConfig.SCYTHE_WORMHOLE_RADIUS.get() : 16;
        Vec3 at = owner.position();
        PLANTED.add(new Planted(level, owner, at, radius, level.getGameTime() + SCAN));
        NoctisPayloads.tell(level, NoctisPayloads.Kind.WORMHOLE_SCAN, at, owner.getLookAngle(), radius);
        RelicSounds.spear(level, at, RelicSounds.Spear.WORMHOLE_SCAN);
        return true;
    }

    /** Whether {@code owner}'s scythe stands planted. */
    public static boolean planted(ServerPlayer owner) {
        for (Planted planted : PLANTED) if (planted.owner == owner) return true;
        return false;
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (PLANTED.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        for (Iterator<Planted> iterator = PLANTED.iterator(); iterator.hasNext(); ) {
            Planted planted = iterator.next();
            if (planted.level != level) continue;
            if (!planted.owner.isAlive() || planted.owner.level() != level) {
                iterator.remove();
                continue;
            }
            // The wielder holds the scythe in the ground while it reads the land.
            planted.owner.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 3, 9, false, false));
            if (now < planted.burstsAt) continue;
            iterator.remove();
            burst(planted);
        }
    }

    private static void burst(Planted planted) {
        ServerLevel level = planted.level;
        Vec3 at = planted.at;
        NoctisPayloads.tell(level, NoctisPayloads.Kind.WORMHOLE_BURST, at, Vec3.ZERO, planted.radius);
        RelicSounds.spear(level, at, RelicSounds.Spear.WORMHOLE_BURST);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(planted.radius),
                target -> HiveCombatController.validTarget(planted.owner, target, true))) {
            if (target.position().distanceTo(at) > planted.radius) continue;
            target.hurt(level.damageSources().source(DAMAGE, planted.owner), DAMAGE_DEALT);
            if (!target.isAlive()) continue;
            double angle = level.getRandom().nextDouble() * Math.PI * 2, distance = THROW_NEAR + level.getRandom().nextDouble() * (THROW_FAR - THROW_NEAR);
            double x = at.x + Math.cos(angle) * distance, z = at.z + Math.sin(angle) * distance;
            level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            Vec3 from = target.position(), to = new Vec3(x, y, z);
            target.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), target.getYRot(), target.getXRot());
            target.setDeltaMovement(Vec3.ZERO);
            target.fallDistance = 0;
            NoctisPayloads.tell(level, NoctisPayloads.Kind.WORMHOLE_THROW, from, to, 1);
        }
    }

    private NoctisWormhole() { }
}
