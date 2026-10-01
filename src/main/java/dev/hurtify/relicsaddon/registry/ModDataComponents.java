package dev.hurtify.relicsaddon.registry;

import com.mojang.serialization.Codec;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.adapter.out.persistence.DeviceCodecs;
import dev.hurtify.relicsaddon.adapter.out.persistence.HiveCodecs;
import dev.hurtify.relicsaddon.adapter.out.persistence.LegacyDroneStackState;
import dev.hurtify.relicsaddon.adapter.out.persistence.ShieldCodecs;
import dev.hurtify.relicsaddon.domain.shield.ShieldImpact;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, RelicsAddon.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.hive.HiveSettings>> HIVE_SETTINGS =
            DATA_COMPONENTS.registerComponentType("hive_settings", builder -> builder
                    .persistent(HiveCodecs.SETTINGS)
                    .networkSynchronized(HiveCodecs.SETTINGS_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.hive.HiveSupportState>> HIVE_SUPPORT_STATE =
            DATA_COMPONENTS.registerComponentType("hive_support_state", builder -> builder
                    .networkSynchronized(HiveCodecs.SUPPORT_STATE_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.hive.HiveCombatState>> HIVE_COMBAT_STATE =
            DATA_COMPONENTS.registerComponentType("hive_combat_state", builder -> builder
                    .networkSynchronized(HiveCodecs.COMBAT_STATE_STREAM));

    /** A Twins hive's Armageddon under way: transient, like the combat state, and seen by every client near its owner. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.hive.ArmageddonState>> HIVE_ARMAGEDDON =
            DATA_COMPONENTS.registerComponentType("hive_armageddon", builder -> builder
                    .networkSynchronized(HiveCodecs.ARMAGEDDON_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.shield.ShieldSettings>> SHIELD_SETTINGS =
            DATA_COMPONENTS.registerComponentType("shield_settings", builder -> builder
                    .persistent(ShieldCodecs.SETTINGS)
                    .networkSynchronized(ShieldCodecs.SETTINGS_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.hive.HiveStackState>> HIVE_STACK_STATE =
            DATA_COMPONENTS.registerComponentType("hive_stack_state", builder -> builder
                    .persistent(HiveCodecs.STACK_STATE)
                    .networkSynchronized(HiveCodecs.STACK_STATE_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> INSTANCE_ID =
            DATA_COMPONENTS.registerComponentType("instance_id", builder -> builder
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.device.DeviceProgression>> DEVICE_PROGRESSION =
            DATA_COMPONENTS.registerComponentType("device_progression", builder -> builder
                    .persistent(DeviceCodecs.PROGRESSION)
                    .networkSynchronized(DeviceCodecs.PROGRESSION_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.energy.DeviceEnergy>> DEVICE_ENERGY =
            DATA_COMPONENTS.registerComponentType("device_energy", builder -> builder
                    .persistent(DeviceCodecs.ENERGY)
                    .networkSynchronized(DeviceCodecs.ENERGY_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShieldStackState>> SHIELD_STACK_STATE =
            DATA_COMPONENTS.registerComponentType("shield_stack_state", builder -> builder
                    .persistent(ShieldCodecs.STACK_STATE)
                    .networkSynchronized(ShieldCodecs.STACK_STATE_STREAM));

    // Retained only to decode existing stacks that still carry the pre-hive component.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<LegacyDroneStackState>> DRONE_STACK_STATE =
            DATA_COMPONENTS.registerComponentType("drone_stack_state", builder -> builder
                    .persistent(LegacyDroneStackState.CODEC)
                    .networkSynchronized(LegacyDroneStackState.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShieldImpact>> SHIELD_IMPACT =
            DATA_COMPONENTS.registerComponentType("shield_impact", builder -> builder
                    .persistent(ShieldCodecs.IMPACT)
                    .networkSynchronized(ShieldCodecs.IMPACT_STREAM));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.domain.shield.ShieldImpactHistory>> SHIELD_IMPACTS =
            DATA_COMPONENTS.registerComponentType("shield_impacts", builder -> builder
                    .networkSynchronized(ShieldCodecs.IMPACT_HISTORY_STREAM));

    private ModDataComponents() {
    }
}
