package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The registry ids that saves, packets, recipes and data packs name: items, data components (and
 * which of them are saved), sounds, the console menu, the creative tab, damage types and tags. The
 * ids are written out as text, so moving the classes that register them cannot change them unseen.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ContractGameTests {
    private static final List<String> ITEMS = List.of("rf_shield", "mana_shield", "twins_shield", "rf_hive", "mana_hive", "twins_hive",
            "resonant_circuit", "energy_cell", "mana_cell", "rf_shield_core", "mana_shield_core", "twins_shield_core",
            "rf_drone_frame", "mana_drone_shell", "twins_drone_plate");
    /** Every data component, in declaration order. */
    private static final List<String> COMPONENTS = List.of("hive_settings", "hive_support_state", "hive_combat_state", "shield_settings",
            "hive_stack_state", "instance_id", "device_progression", "device_energy", "shield_stack_state", "drone_stack_state",
            "shield_impact", "shield_impacts");
    /** Components that are synced to clients but never saved. */
    private static final Set<String> SYNCED_ONLY = Set.of("hive_support_state", "hive_combat_state", "shield_impacts");
    private static final List<String> DAMAGE_TYPES = List.of("drone_shot", "swarm_strike", "swarm_void", "swarm_reflect",
            "shield_discharge", "shield_mana_burst", "shield_twin_surge");
    private static final List<String> DAMAGE_TYPE_TAGS = List.of("shield_passes", "shield_strike", "swarm_damage");

    @GameTest(template = TEMPLATE)
    public static void registryContractsHold(GameTestHelper helper) {
        for (String item : ITEMS) helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id(item)), "Item relics_addon:" + item + " is registered");
        int saved = 0;
        for (String component : COMPONENTS) {
            DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(id(component));
            helper.assertTrue(type != null, "Data component relics_addon:" + component + " is registered");
            helper.assertTrue(type.streamCodec() != null, "Data component relics_addon:" + component + " is synced");
            boolean persistent = type.codec() != null;
            helper.assertTrue(persistent != SYNCED_ONLY.contains(component), "Data component relics_addon:" + component
                    + (persistent ? " must not be saved" : " must be saved"));
            if (persistent) saved++;
        }
        helper.assertTrue(saved == 9, "Nine data components are saved, got " + saved);
        long sounds = BuiltInRegistries.SOUND_EVENT.keySet().stream().filter(key -> key.getNamespace().equals("relics_addon")).count();
        helper.assertTrue(sounds == 54, "54 sound events are registered, got " + sounds);
        helper.assertTrue(BuiltInRegistries.MENU.containsKey(id("device_control")), "Menu relics_addon:device_control is registered");
        helper.assertTrue(BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(id("main")), "Creative tab relics_addon:main is registered");
        var damageTypes = helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        for (String type : DAMAGE_TYPES) helper.assertTrue(damageTypes.containsKey(id(type)), "Damage type relics_addon:" + type + " is loaded");
        for (String tag : DAMAGE_TYPE_TAGS) {
            helper.assertTrue(damageTypes.getTag(TagKey.create(Registries.DAMAGE_TYPE, id(tag))).isPresent(),
                    "Damage type tag #relics_addon:" + tag + " is loaded");
        }
        var entityTypes = helper.getLevel().registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
        helper.assertTrue(entityTypes.getTag(TagKey.create(Registries.ENTITY_TYPE, id("shield_interceptable_projectiles"))).isPresent(),
                "Entity type tag #relics_addon:shield_interceptable_projectiles is loaded");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("relics_addon", path);
    }

    private ContractGameTests() {
    }
}
