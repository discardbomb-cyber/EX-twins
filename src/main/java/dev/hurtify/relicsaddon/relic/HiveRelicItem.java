package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.drone.HiveType;

/** Autonomous hive variants share the item state and menu with shields. */
public abstract class HiveRelicItem extends AutonomousRelicItem {
    protected HiveRelicItem(Properties properties) { super(properties); }
    protected abstract HiveType type();
    @Override public RelicRole role() { return type().role; }
    public static final class Rf extends HiveRelicItem { public Rf(Properties p) { super(p); } @Override protected HiveType type() { return HiveType.RF; } }
    public static final class Mana extends HiveRelicItem { public Mana(Properties p) { super(p); } @Override protected HiveType type() { return HiveType.MANA; } }
    public static final class Twins extends HiveRelicItem { public Twins(Properties p) { super(p); } @Override protected HiveType type() { return HiveType.TWINS; } }
}
