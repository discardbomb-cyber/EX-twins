package dev.hurtify.relicsaddon;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

public final class RegistryFilterCheck {
    public static void main(String[] args) {
        List<String> entries = List.of("minecraft:fall", " arrow ", "#minecraft:is_fire", "#c:bullets", "Not An Id!", "", "#", "minecraft:", "#bad tag",
                "minecraft:fall", "relics_addon:shield_discharge");
        var parsed = RegistryFilter.parse(Registries.DAMAGE_TYPE, entries);
        require(parsed.source() == entries, "The parse remembers the exact list it came from");
        require(parsed.ids().equals(java.util.Set.of(ResourceLocation.withDefaultNamespace("fall"), ResourceLocation.withDefaultNamespace("arrow"),
                ResourceLocation.fromNamespaceAndPath("relics_addon", "shield_discharge"))), "Ids are trimmed, deduplicated and default to minecraft: " + parsed.ids());
        require(parsed.tags().equals(List.of(TagKey.create(Registries.DAMAGE_TYPE, ResourceLocation.withDefaultNamespace("is_fire")),
                TagKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("c", "bullets")))), "Tags keep their namespace: " + parsed.tags());
        require(parsed.rejected().equals(List.of("Not An Id!", "", "#", "minecraft:", "#bad tag")), "Malformed entries are reported, not thrown: " + parsed.rejected());
        require(RegistryFilter.parse(Registries.ENTITY_TYPE, List.of()).empty(), "An empty list matches nothing");
        System.out.println("Registry filter: ids, #tags, trimming, duplicates and malformed entries verified");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
