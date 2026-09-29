package dev.hurtify.relicsaddon;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * A server config list of registry ids and {@code #tags}. It is parsed once for each list the config
 * hands out (a load, a reload or a {@code set}), since damage and projectile checks ask it constantly;
 * tags are asked of the holder on every match, so a datapack reload changes what they cover.
 */
public final class RegistryFilter<T> {
    private final ModConfigSpec spec;
    private final ResourceKey<? extends Registry<T>> registry;
    private final ModConfigSpec.ConfigValue<List<? extends String>> value;
    private volatile Parsed<T> parsed = Parsed.none();

    RegistryFilter(ModConfigSpec spec, ResourceKey<? extends Registry<T>> registry, ModConfigSpec.ConfigValue<List<? extends String>> value) {
        this.spec = spec;
        this.registry = registry;
        this.value = value;
    }

    public boolean matches(Holder<T> holder) {
        Parsed<T> current = current();
        return !current.empty() && current.test(holder);
    }

    /** For built-in registries: the value is only looked up once the list has entries. */
    public boolean matches(Registry<T> registry, T value) {
        Parsed<T> current = current();
        return !current.empty() && current.test(registry.wrapAsHolder(value));
    }

    /** Parses the current list now, so bad entries are reported when the config is read rather than on the first hit. */
    void refresh() {
        current();
    }

    private Parsed<T> current() {
        if (!spec.isLoaded()) return Parsed.none();
        List<? extends String> entries = value.get();
        Parsed<T> cached = parsed;
        return cached.source() == entries ? cached : reparse(entries);
    }

    private synchronized Parsed<T> reparse(List<? extends String> entries) {
        if (parsed.source() == entries) return parsed;
        Parsed<T> next = parse(registry, entries);
        String option = String.join(".", value.getPath());
        String kind = registry.location().getPath();
        for (String entry : next.rejected()) {
            RelicsAddon.LOGGER.warn("Server config {}: ignoring \"{}\", expected a {} id such as minecraft:example or a #tag", option, entry, kind);
        }
        // Unknown names are kept (the mod adding them may simply be absent) but are almost always typos.
        var server = ServerLifecycleHooks.getCurrentServer();
        Registry<T> live = server == null ? null : server.registryAccess().registry(registry).orElse(null);
        if (live != null) {
            for (ResourceLocation id : next.ids()) {
                if (!live.containsKey(id)) RelicsAddon.LOGGER.warn("Server config {}: no {} \"{}\" is registered, the entry matches nothing", option, kind, id);
            }
            for (TagKey<T> tag : next.tags()) {
                if (live.getTag(tag).isEmpty()) RelicsAddon.LOGGER.warn("Server config {}: no {} tag \"#{}\" exists, the entry matches nothing", option, kind, tag.location());
            }
        }
        parsed = next;
        return next;
    }

    /** Splits raw entries into ids and tags; blank or malformed entries come back as rejected. */
    static <T> Parsed<T> parse(ResourceKey<? extends Registry<T>> registry, List<? extends String> entries) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        Set<TagKey<T>> tags = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            boolean tag = entry.startsWith("#");
            ResourceLocation location = ResourceLocation.tryParse(tag ? entry.substring(1) : entry);
            if (location == null || location.getPath().isEmpty()) rejected.add(String.valueOf(raw));
            else if (tag) tags.add(TagKey.create(registry, location));
            else ids.add(location);
        }
        return new Parsed<>(entries, Set.copyOf(ids), List.copyOf(tags), List.copyOf(rejected));
    }

    /** Entries parsed from {@code source}, the exact list object the config handed out. */
    record Parsed<T>(List<?> source, Set<ResourceLocation> ids, List<TagKey<T>> tags, List<String> rejected) {
        private static final Parsed<?> NONE = new Parsed<>(null, Set.of(), List.of(), List.of());

        @SuppressWarnings("unchecked")
        static <T> Parsed<T> none() {
            return (Parsed<T>) NONE;
        }

        boolean empty() {
            return ids.isEmpty() && tags.isEmpty();
        }

        boolean test(Holder<T> holder) {
            if (!ids.isEmpty() && holder.unwrapKey().filter(key -> ids.contains(key.location())).isPresent()) return true;
            for (TagKey<T> tag : tags) if (holder.is(tag)) return true;
            return false;
        }
    }
}
