package dev.hurtify.relicsaddon.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record DroneStackState(
        boolean enabled,
        long lastInterceptGameTime,
        float lastAbsorbed
) {
    public static final DroneStackState DEFAULT = new DroneStackState(true, 0L, 0);

    public static final Codec<DroneStackState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("enabled").forGetter(DroneStackState::enabled),
            Codec.LONG.fieldOf("lastInterceptGameTime").forGetter(DroneStackState::lastInterceptGameTime),
            Codec.FLOAT.fieldOf("lastAbsorbed").forGetter(DroneStackState::lastAbsorbed)
    ).apply(instance, DroneStackState::new));

    public static final StreamCodec<ByteBuf, DroneStackState> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public DroneStackState {
        lastInterceptGameTime = Math.max(0L, lastInterceptGameTime);
        lastAbsorbed = Float.isFinite(lastAbsorbed) ? Math.max(0, lastAbsorbed) : 0;
    }

    public DroneStackState withEnabled(boolean value, long gameTime) {
        return new DroneStackState(value, lastInterceptGameTime, lastAbsorbed);
    }

    public DroneStackState withIntercept(long gameTime, float absorbed) {
        return new DroneStackState(enabled, gameTime, absorbed);
    }

    public boolean ready(long gameTime, int cooldown) {
        return enabled && (lastAbsorbed == 0 || gameTime < lastInterceptGameTime
                || gameTime - lastInterceptGameTime >= cooldown);
    }
}
