package dev.hurtify.relicsaddon.relic;

import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.hive.HiveType;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/** Autonomous hive variants share the item state and menu with shields. Only one hive may be worn. */
public abstract class HiveRelicItem extends AutonomousRelicItem implements ICurioItem {
    protected HiveRelicItem(Properties properties) { super(properties); }
    protected abstract HiveType type();
    @Override public RelicRole role() { return type().role; }

    /** Rejects a second hive in any curio slot; moving the worn hive between slots stays allowed. */
    @Override public boolean canEquip(SlotContext context, ItemStack stack) {
        return CuriosApi.getCuriosInventory(context.entity()).map(handler -> handler.findCurios(worn -> worn.getItem() instanceof HiveRelicItem)
                .stream().allMatch(result -> result.slotContext().identifier().equals(context.identifier())
                        && result.slotContext().index() == context.index())).orElse(true);
    }

    public static final class Rf extends HiveRelicItem { public Rf(Properties p) { super(p); } @Override protected HiveType type() { return HiveType.RF; } }
    public static final class Mana extends HiveRelicItem { public Mana(Properties p) { super(p); } @Override protected HiveType type() { return HiveType.MANA; } }
    public static final class Twins extends HiveRelicItem { public Twins(Properties p) { super(p); } @Override protected HiveType type() { return HiveType.TWINS; } }
}
