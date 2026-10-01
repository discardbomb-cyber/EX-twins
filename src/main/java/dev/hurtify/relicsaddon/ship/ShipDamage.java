package dev.hurtify.relicsaddon.ship;

import dev.hurtify.relicsaddon.RelicsAddon;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/** The ship hives' damage types; the owner is credited when they are on, and nobody is knocked about by them. */
public final class ShipDamage {
    /** A lance hive's beam; it ignores a creature's moment of invulnerability after a hit, so each strike lands. */
    public static final ResourceKey<DamageType> LANCE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "ship_lance"));
    /** An escort wing's arc and its little drones' dives. */
    public static final ResourceKey<DamageType> ESCORT = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "ship_escort"));

    /**
     * A blow of a hive's: its owner is credited when they are on, but it comes from where the hive's drones struck
     * it from, so a shield between them stops it and nothing else takes it for the owner's own.
     */
    static net.minecraft.world.damagesource.DamageSource source(net.minecraft.server.level.ServerLevel level, ResourceKey<DamageType> type,
            ShipHiveBlockEntity hive, net.minecraft.world.phys.Vec3 from) {
        var owner = hive.owner() == null ? null : level.getPlayerByUUID(hive.owner());
        return new net.minecraft.world.damagesource.DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type),
                null, owner, from);
    }

    private ShipDamage() {
    }
}
