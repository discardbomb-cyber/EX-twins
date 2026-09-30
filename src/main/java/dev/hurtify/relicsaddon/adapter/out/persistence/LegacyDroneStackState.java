package dev.hurtify.relicsaddon.adapter.out.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record LegacyDroneStackState(
        boolean enabled,
        long lastInterceptGameTime,
        float lastAbsorbed
) {
    public static final LegacyDroneStackState DEFAULT = new LegacyDroneStackState(true, 0L, 0);

    public static final Codec<LegacyDroneStackState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("enabled").forGetter(LegacyDroneStackState::enabled),
            Codec.LONG.fieldOf("lastInterceptGameTime").forGetter(LegacyDroneStackState::lastInterceptGameTime),
            Codec.FLOAT.fieldOf("lastAbsorbed").forGetter(LegacyDroneStackState::lastAbsorbed)
    ).apply(instance, LegacyDroneStackState::new));

    public static final StreamCodec<ByteBuf, LegacyDroneStackState> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public LegacyDroneStackState {
        lastInterceptGameTime = Math.max(0L, lastInterceptGameTime);
        lastAbsorbed = Float.isFinite(lastAbsorbed) ? Math.max(0, lastAbsorbed) : 0;
    }

    public LegacyDroneStackState withEnabled(boolean value, long gameTime) {
        return new LegacyDroneStackState(value, lastInterceptGameTime, lastAbsorbed);
    }

    public LegacyDroneStackState withIntercept(long gameTime, float absorbed) {
        return new LegacyDroneStackState(enabled, gameTime, absorbed);
    }

    public boolean ready(long gameTime, int cooldown) {
        return enabled && (lastAbsorbed == 0 || gameTime < lastInterceptGameTime
                || gameTime - lastInterceptGameTime >= cooldown);
    }
}
