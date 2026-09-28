package dev.hurtify.relicsaddon.shield;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record ShieldSettings(double radius, String coverage) {
    public static final ShieldSettings DEFAULT = new ShieldSettings(2, "allies");
    public static final Codec<ShieldSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("radius", 2.0).forGetter(ShieldSettings::radius),
            Codec.STRING.optionalFieldOf("coverage", "allies").forGetter(ShieldSettings::coverage)
    ).apply(instance, ShieldSettings::new));
    public static final StreamCodec<ByteBuf, ShieldSettings> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    public ShieldSettings {
        radius = Double.isFinite(radius) ? Math.clamp(radius, 2, 24) : 2;
        if (!"owner".equals(coverage) && !"allies".equals(coverage) && !"all".equals(coverage)) coverage = "allies";
    }
}
