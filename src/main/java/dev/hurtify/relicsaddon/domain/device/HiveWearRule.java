package dev.hurtify.relicsaddon.domain.device;

import java.util.List;
import java.util.Optional;

/** Only one hive may be worn. */
public final class HiveWearRule {
    /**
     * Whether a hive may go into slot {@code index} of {@code identifier}. {@code worn} lists the slots
     * that already hold a hive, empty when the wearer has no curio inventory (then anything goes). Every
     * worn hive must sit in that very slot, so moving the worn hive between slots stays allowed.
     */
    public static boolean allows(Optional<List<WornSlot>> worn, String identifier, int index) {
        return worn.map(slots -> slots.stream().allMatch(slot -> slot.identifier().equals(identifier) && slot.index() == index)).orElse(true);
    }

    private HiveWearRule() { }
}
