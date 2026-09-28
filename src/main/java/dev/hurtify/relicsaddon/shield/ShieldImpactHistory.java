package dev.hurtify.relicsaddon.shield;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Short-lived network events, not saved shield HP. Keeps multiple hits within a single tick. */
public record ShieldImpactHistory(List<ShieldImpact> impacts) {
    public static final int LIMIT = 12;
    public static final ShieldImpactHistory EMPTY = new ShieldImpactHistory(List.of());
    public static final Codec<ShieldImpactHistory> CODEC = ShieldImpact.CODEC.listOf(0, LIMIT)
            .xmap(ShieldImpactHistory::new, ShieldImpactHistory::impacts);
    public static final StreamCodec<ByteBuf, ShieldImpactHistory> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    public ShieldImpactHistory {
        impacts = List.copyOf(impacts.subList(Math.max(0, impacts.size() - LIMIT), impacts.size()));
    }
    public ShieldImpactHistory append(ShieldImpact impact) {
        var recent = new ArrayList<>(impacts.stream().filter(old -> old.gameTime() <= impact.gameTime()
                && impact.gameTime() - old.gameTime() < 36).toList());
        recent.add(impact);
        return new ShieldImpactHistory(recent);
    }
}
