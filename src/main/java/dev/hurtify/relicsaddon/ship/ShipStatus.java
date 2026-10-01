package dev.hurtify.relicsaddon.ship;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** What a ship hive is doing, in a word: shown when it is used and in its window (which gets it as a number). */
public enum ShipStatus {
    OFF("off", ChatFormatting.GRAY),
    GROUNDED("grounded", ChatFormatting.GOLD),
    UNPOWERED("unpowered", ChatFormatting.GOLD),
    WATCHING("watching", ChatFormatting.GREEN),
    LANCE_AIMING("lance.aiming", ChatFormatting.LIGHT_PURPLE),
    LANCE_FIRING("lance.firing", ChatFormatting.LIGHT_PURPLE),
    LANCE_BLOCKED("lance.blocked", ChatFormatting.GOLD),
    LANCE_OVERHEATED("lance.overheated", ChatFormatting.RED),
    AEGIS_UP("aegis.up", ChatFormatting.AQUA),
    AEGIS_DOWN("aegis.down", ChatFormatting.RED),
    ESCORT_FIGHTING("escort.fighting", ChatFormatting.AQUA),
    ESCORT_PATROL("escort.patrol", ChatFormatting.GREEN),
    ESCORT_DOCKED("escort.docked", ChatFormatting.GRAY);

    private final String key;
    public final ChatFormatting color;

    ShipStatus(String key, ChatFormatting color) {
        this.key = key;
        this.color = color;
    }

    public Line with(int value) {
        return new Line(this, value);
    }

    public Line line() {
        return new Line(this, 0);
    }

    /** A status with the number some of them show (a shield's charge in percent, the wings out). */
    public record Line(ShipStatus status, int value) {
        public Component text() {
            return Component.translatable("ship.relics_addon.status." + status.key, value).withStyle(status.color);
        }
    }

    public static ShipStatus of(int ordinal) {
        ShipStatus[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : WATCHING;
    }
}
