package dev.hurtify.relicsaddon.registry;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.relic.TwinsSpearEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, RelicsAddon.MOD_ID);

    /** The glass twin of the Twins spear, thrown while the spear itself stays in hand. */
    public static final DeferredHolder<EntityType<?>, EntityType<TwinsSpearEntity>> TWINS_SPEAR = ENTITIES.register("twins_spear",
            () -> EntityType.Builder.<TwinsSpearEntity>of(TwinsSpearEntity::new, MobCategory.MISC)
                    .sized(.5F, .5F).clientTrackingRange(8).updateInterval(1).build("twins_spear"));

    private ModEntities() {
    }
}
