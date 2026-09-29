package dev.hurtify.relicsaddon.power;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.RelicsAddon;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Where mana batteries refill from: Botania mana items (tablets, rings), the player's own mana in
 * Ars Nouveau and Iron's Spells 'n Spellbooks, and vanilla experience. The mods are optional and
 * reached by reflection, so none of them is a build or runtime dependency; an adapter that fails
 * to bind is logged once and skipped.
 */
final class ManaSources {
    private interface Source {
        /** Takes up to {@code points} of battery charge from the player and returns how much it gave. */
        int take(ServerPlayer player, ItemStack device, int points) throws Throwable;
    }

    private static List<Source> magic;

    static int draw(ServerPlayer player, ItemStack device, int points, DeviceEnergy.ManaSource mode) {
        int gained = 0;
        if (mode != DeviceEnergy.ManaSource.EXPERIENCE) {
            for (Source source : magic()) {
                if (gained >= points) break;
                try {
                    gained += Math.max(0, source.take(player, device, points - gained));
                } catch (Throwable failure) {
                    RelicsAddon.LOGGER.warn("Mana source {} failed; it is disabled for this session", source, failure);
                    magic.remove(source);
                    break;
                }
            }
        }
        if (mode != DeviceEnergy.ManaSource.MAGIC && gained < points) gained += experience(player, points - gained);
        return gained;
    }

    // --- vanilla experience -------------------------------------------------------------------

    private static int experience(Player player, int points) {
        int value = AddonConfig.XP_POINT_VALUE.get();
        int available = totalExperience(player) - pointsForLevel(AddonConfig.XP_RESERVE_LEVELS.get());
        int used = Math.min(available, (points + value - 1) / value);
        if (used <= 0) return 0;
        player.giveExperiencePoints(-used);
        return used * value;
    }

    static int totalExperience(Player player) {
        return pointsForLevel(player.experienceLevel) + Math.round(player.experienceProgress * player.getXpNeededForNextLevel());
    }

    /** Vanilla's cumulative experience to reach {@code level}. */
    static int pointsForLevel(int level) {
        if (level <= 16) return level * level + 6 * level;
        if (level <= 31) return (int) (2.5 * level * level - 40.5 * level + 360);
        return (int) (4.5 * level * level - 162.5 * level + 2220);
    }

    // --- optional magic mods -------------------------------------------------------------------

    private static synchronized List<Source> magic() {
        if (magic == null) {
            magic = new ArrayList<>();
            bind("botania", ManaSources::botania);
            bind("ars_nouveau", ManaSources::arsNouveau);
            bind("irons_spellbooks", ManaSources::ironsSpellbooks);
        }
        return magic;
    }

    private interface Binder { Source bind() throws ReflectiveOperationException; }

    private static void bind(String modId, Binder binder) {
        if (!ModList.get().isLoaded(modId)) return;
        try {
            Source source = binder.bind();
            magic.add(new Source() {
                @Override public int take(ServerPlayer player, ItemStack device, int points) throws Throwable { return source.take(player, device, points); }
                @Override public String toString() { return modId; }
            });
            RelicsAddon.LOGGER.info("Mana batteries can draw from {}", modId);
        } catch (ReflectiveOperationException | LinkageError failure) {
            RelicsAddon.LOGGER.warn("Could not bind the {} mana API; its mana will not charge batteries", modId, failure);
        }
    }

    /** Botania: mana tablets, rings and other mana items anywhere on the player. */
    private static Source botania() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> handler = Class.forName("vazkii.botania.api.mana.ManaItemHandler");
        MethodHandle instance = lookup.findStatic(handler, "instance", MethodType.methodType(handler));
        MethodHandle request = lookup.findVirtual(handler, "requestMana",
                MethodType.methodType(int.class, ItemStack.class, Player.class, int.class, boolean.class));
        return (player, device, points) -> {
            int perPoint = AddonConfig.BOTANIA_MANA_PER_POINT.get();
            int received = (int) request.invoke(instance.invoke(), device, (Player) player, points * perPoint, true);
            return received / perPoint;
        };
    }

    /** Ars Nouveau: the player's mana pool, keeping a reserve for spells. */
    private static Source arsNouveau() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> registry = Class.forName("com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry");
        Class<?> cap = Class.forName("com.hollingsworth.arsnouveau.common.capability.ManaCap");
        MethodHandle get = lookup.findStatic(registry, "getMana", MethodType.methodType(cap, LivingEntity.class));
        MethodHandle current = lookup.findVirtual(cap, "getCurrentMana", MethodType.methodType(double.class));
        MethodHandle max = lookup.findVirtual(cap, "getMaxMana", MethodType.methodType(int.class));
        MethodHandle remove = lookup.findVirtual(cap, "removeMana", MethodType.methodType(double.class, double.class));
        MethodHandle sync = lookup.findVirtual(cap, "syncToClient", MethodType.methodType(void.class, ServerPlayer.class));
        return (player, device, points) -> {
            Object mana = get.invoke((LivingEntity) player);
            if (mana == null) return 0;
            double value = AddonConfig.PLAYER_MANA_VALUE.get();
            double available = (double) current.invoke(mana) - (int) max.invoke(mana) * AddonConfig.PLAYER_MANA_RESERVE.get();
            double used = Math.min(available, points / value);
            if (used <= 0) return 0;
            remove.invoke(mana, used);
            sync.invoke(mana, player);
            return (int) Math.floor(used * value);
        };
    }

    /** Iron's Spells 'n Spellbooks: the player's mana, synced back with the mod's own packet. */
    private static Source ironsSpellbooks() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> data = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
        Class<?> packet = Class.forName("io.redspace.ironsspellbooks.network.SyncManaPacket");
        MethodHandle get = lookup.findStatic(data, "getPlayerMagicData", MethodType.methodType(data, LivingEntity.class));
        MethodHandle getMana = lookup.findVirtual(data, "getMana", MethodType.methodType(float.class));
        MethodHandle setMana = lookup.findVirtual(data, "setMana", MethodType.methodType(void.class, float.class));
        MethodHandle sync = lookup.findConstructor(packet, MethodType.methodType(void.class, data));
        var maxMana = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "max_mana"))
                .orElseThrow(() -> new NoSuchFieldException("irons_spellbooks:max_mana"));
        return (player, device, points) -> {
            Object magic = get.invoke((LivingEntity) player);
            if (magic == null || player.getAttribute(maxMana) == null) return 0;
            double value = AddonConfig.PLAYER_MANA_VALUE.get();
            float mana = (float) getMana.invoke(magic);
            double available = mana - player.getAttributeValue(maxMana) * AddonConfig.PLAYER_MANA_RESERVE.get();
            double used = Math.min(available, points / value);
            if (used <= 0) return 0;
            setMana.invoke(magic, (float) (mana - used));
            PacketDistributor.sendToPlayer(player, (CustomPacketPayload) sync.invoke(magic));
            return (int) Math.floor(used * value);
        };
    }

    private ManaSources() {
    }
}
