package dev.hurtify.relicsaddon.domain.energy;

import java.util.Locale;

/**
 * Charge of a device's built-in batteries. {@code rf} is stored in FE, {@code mana} in battery
 * points; each battery has its own switch, and the mana battery remembers where it refills from.
 */
public record DeviceEnergy(int rf, int mana, boolean rfOn, boolean manaOn, ManaSource source) {
    /** Where an empty mana battery draws from: magic mods first, magic only, or player experience. */
    public enum ManaSource {
        AUTO, MAGIC, EXPERIENCE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static ManaSource byId(String id) {
            for (ManaSource value : values()) if (value.id().equals(id)) return value;
            return AUTO;
        }
    }

    public static final DeviceEnergy EMPTY = new DeviceEnergy(0, 0, true, true, ManaSource.AUTO);

    public DeviceEnergy {
        rf = Math.max(0, rf);
        mana = Math.max(0, mana);
        if (source == null) source = ManaSource.AUTO;
    }

    public DeviceEnergy withRf(int value) { return new DeviceEnergy(value, mana, rfOn, manaOn, source); }
    public DeviceEnergy withMana(int value) { return new DeviceEnergy(rf, value, rfOn, manaOn, source); }
    public DeviceEnergy withRfOn(boolean value) { return new DeviceEnergy(rf, mana, value, manaOn, source); }
    public DeviceEnergy withManaOn(boolean value) { return new DeviceEnergy(rf, mana, rfOn, value, source); }
    public DeviceEnergy withSource(ManaSource value) { return new DeviceEnergy(rf, mana, rfOn, manaOn, value); }
}
