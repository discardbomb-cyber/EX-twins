package dev.hurtify.relicsaddon.client;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

/**
 * The standalone model ids of every animated relic part (body, core, fx, shells, and the swarm
 * stand-ins of drones), built once per role so a frame never re-parses a {@link ResourceLocation}.
 * Ids are plain values: a resource reload re-bakes the models behind them, nothing here goes stale.
 */
final class RelicPartModels {
    static final String BODY = "body", CORE = "core", FX = "fx", SWARM = "swarm", DENSE = "dense";

    private static final int BODY_SLOT = 0, CORE_SLOT = 1, FX_SLOT = 2, SWARM_SLOT = 3, DENSE_SLOT = 4, SHELL_SLOT = 5;
    private static final ModelResourceLocation[][] IDS = build();

    static ModelResourceLocation body(RelicRole role) {
        return IDS[role.ordinal()][BODY_SLOT];
    }

    static ModelResourceLocation core(RelicRole role) {
        return IDS[role.ordinal()][CORE_SLOT];
    }

    static ModelResourceLocation fx(RelicRole role) {
        return IDS[role.ordinal()][FX_SLOT];
    }

    static ModelResourceLocation shell(RelicRole role, int index) {
        return IDS[role.ordinal()][SHELL_SLOT + index];
    }

    /** The flat stand-in of a drone: {@code dense} is the far one, {@code swarm} the near one. */
    static ModelResourceLocation swarm(RelicRole role, boolean dense) {
        return IDS[role.ordinal()][dense ? DENSE_SLOT : SWARM_SLOT];
    }

    /** Every id of the role that must be registered as a standalone model, in part order. */
    static List<ModelResourceLocation> all(RelicRole role) {
        List<ModelResourceLocation> all = new ArrayList<>();
        for (ModelResourceLocation id : IDS[role.ordinal()]) if (id != null) all.add(id);
        return all;
    }

    static int shellCount(RelicRole role) {
        return switch (role) {
            case RF_DRONE, MANA_SHIELD -> 4;
            case RF_HIVE -> 4;
            case MANA_HIVE -> 6;
            case TWINS_HIVE -> 12;
            case MANA_DRONE -> 6;
            case TWINS_SHIELD, TWINS_DRONE -> TwinsFacetPose.COUNT;
            default -> 0;
        };
    }

    /** Parses one part id; the table above is what the renderer uses, this is how it is filled. */
    static ModelResourceLocation parse(RelicRole role, String part) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID,
                "item/animated/" + role.itemId() + "_" + part));
    }

    private static ModelResourceLocation[][] build() {
        RelicRole[] roles = RelicRole.values();
        ModelResourceLocation[][] ids = new ModelResourceLocation[roles.length][];
        for (RelicRole role : roles) {
            int shells = shellCount(role);
            ModelResourceLocation[] parts = new ModelResourceLocation[SHELL_SLOT + shells];
            parts[BODY_SLOT] = parse(role, BODY);
            parts[CORE_SLOT] = parse(role, CORE);
            parts[FX_SLOT] = parse(role, FX);
            if (!role.isShield() && !role.isHive()) {
                parts[SWARM_SLOT] = parse(role, SWARM);
                parts[DENSE_SLOT] = parse(role, DENSE);
            }
            for (int index = 0; index < shells; index++) parts[SHELL_SLOT + index] = parse(role, "shell_" + index);
            ids[role.ordinal()] = parts;
        }
        return ids;
    }

    private RelicPartModels() { }
}
