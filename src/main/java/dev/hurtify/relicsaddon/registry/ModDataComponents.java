package dev.hurtify.relicsaddon.registry;

import com.mojang.serialization.Codec;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.drone.DroneStackState;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, RelicsAddon.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.drone.HiveSettings>> HIVE_SETTINGS =
            DATA_COMPONENTS.registerComponentType("hive_settings", builder -> builder
                    .persistent(dev.hurtify.relicsaddon.drone.HiveSettings.CODEC)
                    .networkSynchronized(dev.hurtify.relicsaddon.drone.HiveSettings.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.drone.HiveSupportState>> HIVE_SUPPORT_STATE =
            DATA_COMPONENTS.registerComponentType("hive_support_state", builder -> builder
                    .networkSynchronized(dev.hurtify.relicsaddon.drone.HiveSupportState.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.drone.HiveCombatState>> HIVE_COMBAT_STATE =
            DATA_COMPONENTS.registerComponentType("hive_combat_state", builder -> builder
                    .networkSynchronized(dev.hurtify.relicsaddon.drone.HiveCombatState.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.shield.ShieldSettings>> SHIELD_SETTINGS =
            DATA_COMPONENTS.registerComponentType("shield_settings", builder -> builder
                    .persistent(dev.hurtify.relicsaddon.shield.ShieldSettings.CODEC)
                    .networkSynchronized(dev.hurtify.relicsaddon.shield.ShieldSettings.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.drone.HiveStackState>> HIVE_STACK_STATE =
            DATA_COMPONENTS.registerComponentType("hive_stack_state", builder -> builder
                    .persistent(dev.hurtify.relicsaddon.drone.HiveStackState.CODEC)
                    .networkSynchronized(dev.hurtify.relicsaddon.drone.HiveStackState.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> INSTANCE_ID =
            DATA_COMPONENTS.registerComponentType("instance_id", builder -> builder
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShieldStackState>> SHIELD_STACK_STATE =
            DATA_COMPONENTS.registerComponentType("shield_stack_state", builder -> builder
                    .persistent(ShieldStackState.CODEC)
                    .networkSynchronized(ShieldStackState.STREAM_CODEC));

    // Retained only to decode existing stacks that still carry the pre-hive component.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DroneStackState>> DRONE_STACK_STATE =
            DATA_COMPONENTS.registerComponentType("drone_stack_state", builder -> builder
                    .persistent(DroneStackState.CODEC)
                    .networkSynchronized(DroneStackState.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShieldImpact>> SHIELD_IMPACT =
            DATA_COMPONENTS.registerComponentType("shield_impact", builder -> builder
                    .persistent(ShieldImpact.CODEC)
                    .networkSynchronized(ShieldImpact.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.shield.ShieldImpactHistory>> SHIELD_IMPACTS =
            DATA_COMPONENTS.registerComponentType("shield_impacts", builder -> builder
                    .networkSynchronized(dev.hurtify.relicsaddon.shield.ShieldImpactHistory.STREAM_CODEC));

    private ModDataComponents() {
    }
}
