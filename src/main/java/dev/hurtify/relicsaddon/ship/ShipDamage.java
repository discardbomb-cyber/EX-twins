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

    private ShipDamage() {
    }
}
