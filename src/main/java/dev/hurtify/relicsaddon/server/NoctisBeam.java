package dev.hurtify.relicsaddon.server;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.network.NoctisPayloads;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.relic.NoctisCore;
import dev.hurtify.relicsaddon.sound.RelicSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The all-piercing quantum: the spear's beam, straight out from the wielder for {@code spear.beamLength} blocks. It
 * strikes every creature on its line at once (through any field: its damage passes shields), and unless the server
 * runs Armageddon safe, bores a round tunnel the whole way, a few hundred blocks a tick, leaving nothing behind.
 */
public final class NoctisBeam {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(dev.hurtify.relicsaddon.RelicsAddon.MOD_ID, "noctis_beam"));
    /** The core and the hive charge a beam costs, its blow, and the tunnel's radius. */
    public static final int CORE_COST = 60, HIVE_COST = 200;
    public static final float DAMAGE_DEALT = 30;
    public static final double RADIUS = 1.5;
    /** Blocks bored a tick, and how far from the wielder the boring starts (their own footing is spared). */
    private static final int BITES = 320;
    private static final double SPARED = 2;

    /** A tunnel still being bored: along the beam from {@code from}, the next distance to bore at. */
    private static final class Tunnel {
        final ServerLevel level;
        final ServerPlayer owner;
        final Vec3 from, heading;
        final double length;
        double bored = SPARED;

        Tunnel(ServerLevel level, ServerPlayer owner, Vec3 from, Vec3 heading, double length) {
            this.level = level;
            this.owner = owner;
            this.from = from;
            this.heading = heading;
            this.length = length;
        }
    }

    private static final List<Tunnel> TUNNELS = new ArrayList<>();

    /** Fires the beam; false, and nothing spent, when the core or the hive is short. */
    public static boolean fire(ServerLevel level, ServerPlayer owner, ItemStack weapon, ItemStack hive) {
        if (NoctisCore.charge(weapon) < CORE_COST || !DevicePower.canAfford(owner, hive, HIVE_COST)) return false;
        NoctisCore.spend(weapon, CORE_COST);
        DevicePower.drain(owner, hive, HIVE_COST);
        Vec3 from = owner.getEyePosition(), heading = owner.getLookAngle().normalize();
        double length = AddonConfig.SPEC.isLoaded() ? AddonConfig.SPEAR_BEAM_LENGTH.get() : 96;
        Vec3 to = from.add(heading.scale(length));
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(RADIUS + 1),
                target -> HiveCombatController.validTarget(owner, target, true))) {
            Vec3 middle = target.getBoundingBox().getCenter();
            double along = Math.clamp(middle.subtract(from).dot(heading), 0, length);
            if (from.add(heading.scale(along)).distanceTo(middle) > RADIUS + target.getBbWidth() / 2) continue;
            target.hurt(level.damageSources().source(DAMAGE, owner), DAMAGE_DEALT);
        }
        if (!ArmageddonController.safe()) TUNNELS.add(new Tunnel(level, owner, from, heading, length));
        // The wielder is thrown back by it.
        owner.setDeltaMovement(owner.getDeltaMovement().add(heading.scale(-.9)).add(0, .2, 0));
        owner.hurtMarked = true;
        NoctisPayloads.tell(level, NoctisPayloads.Kind.BEAM, from, heading, length);
        RelicSounds.spear(level, from, RelicSounds.Spear.BEAM_FIRE);
        return true;
    }

    /** Bores each tunnel on, a few hundred blocks a tick, from the wielder outwards. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (TUNNELS.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        for (Iterator<Tunnel> tunnels = TUNNELS.iterator(); tunnels.hasNext(); ) {
            Tunnel tunnel = tunnels.next();
            if (tunnel.level != level) continue;
            if (bore(tunnel)) tunnels.remove();
        }
    }

    /** Bores the next stretch of {@code tunnel}; true once it is through. */
    private static boolean bore(Tunnel tunnel) {
        int budget = BITES;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        Vec3[] axes = dev.hurtify.relicsaddon.drone.HiveShapes.axes(tunnel.heading);
        // Asked each tick, so a server turned safe partway keeps the rest of its land.
        while (tunnel.bored < tunnel.length && budget > 0 && !ArmageddonController.safe()) {
            Vec3 centre = tunnel.from.add(tunnel.heading.scale(tunnel.bored));
            int span = (int) Math.ceil(RADIUS);
            for (int u = -span; u <= span; u++) for (int v = -span; v <= span; v++) {
                Vec3 point = centre.add(axes[1].scale(u)).add(axes[2].scale(v));
                if (point.distanceTo(centre) > RADIUS) continue;
                at.set(Math.floor(point.x), Math.floor(point.y), Math.floor(point.z));
                if (!tunnel.level.isLoaded(at) || !tunnel.level.mayInteract(tunnel.owner, at)) continue;
                BlockState state = tunnel.level.getBlockState(at);
                // Nothing unbreakable (bedrock, the end's frame) and nothing already open.
                if (state.isAir() || state.getDestroySpeed(tunnel.level, at) < 0) continue;
                tunnel.level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                budget--;
            }
            tunnel.bored += .7;
        }
        return tunnel.bored >= tunnel.length || ArmageddonController.safe();
    }

    /** For tests: whether any tunnel is still being bored. */
    public static boolean boring() {
        return !TUNNELS.isEmpty();
    }

    private NoctisBeam() { }
}
