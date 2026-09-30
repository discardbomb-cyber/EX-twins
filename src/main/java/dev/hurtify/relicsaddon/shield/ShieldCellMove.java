package dev.hurtify.relicsaddon.shield;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hurtify.relicsaddon.domain.shield.ShieldTopology;

public record ShieldCellMove(int from, int to) {
    public static final Codec<ShieldCellMove> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, ShieldTopology.CELL_COUNT - 1).fieldOf("from").forGetter(ShieldCellMove::from),
            Codec.intRange(0, ShieldTopology.CELL_COUNT - 1).fieldOf("to").forGetter(ShieldCellMove::to)
    ).apply(instance, ShieldCellMove::new));
}
