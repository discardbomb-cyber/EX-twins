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

    /** A Twins hive's Armageddon under way: transient, like the combat state, and seen by every client near its owner. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.drone.ArmageddonState>> HIVE_ARMAGEDDON =
            DATA_COMPONENTS.registerComponentType("hive_armageddon", builder -> builder
                    .networkSynchronized(dev.hurtify.relicsaddon.drone.ArmageddonState.STREAM_CODEC));

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

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.relic.DeviceProgression>> DEVICE_PROGRESSION =
            DATA_COMPONENTS.registerComponentType("device_progression", builder -> builder
                    .persistent(dev.hurtify.relicsaddon.relic.DeviceProgression.CODEC)
                    .networkSynchronized(dev.hurtify.relicsaddon.relic.DeviceProgression.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.hurtify.relicsaddon.power.DeviceEnergy>> DEVICE_ENERGY =
            DATA_COMPONENTS.registerComponentType("device_energy", builder -> builder
                    .persistent(dev.hurtify.relicsaddon.power.DeviceEnergy.CODEC)
                    .networkSynchronized(dev.hurtify.relicsaddon.power.DeviceEnergy.STREAM_CODEC));

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

    /** The charge of a Noctis weapon's core (0 to 100): fed by its blows, spent by its great works. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> NOCTIS_CORE =
            DATA_COMPONENTS.registerComponentType("noctis_core", builder -> builder
                    .persistent(Codec.intRange(0, 100))
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Whether the Eclipse scythe is open (its blade out) rather than folded to its hilt. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> SCYTHE_OPEN =
            DATA_COMPONENTS.registerComponentType("scythe_open", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /** The game time until which the scythe's Overdrive lasts (0 when it is not on). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> SCYTHE_OVERDRIVE =
            DATA_COMPONENTS.registerComponentType("scythe_overdrive", builder -> builder
                    .persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG));

    private ModDataComponents() {
    }
}
