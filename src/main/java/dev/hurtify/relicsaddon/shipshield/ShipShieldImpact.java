package dev.hurtify.relicsaddon.shipshield;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** A confirmed hit, relative to the generator so Sable plot coordinates retain their precision. */
public record ShipShieldImpact(Vec3 position, long time, int absorbed, int brokenLayers, boolean overload) {
    public static final int LIFETIME = 40, MAX = 12;
    public static final Codec<ShipShieldImpact> CODEC = RecordCodecBuilder.create(i -> i.group(
            Vec3.CODEC.fieldOf("position").forGetter(ShipShieldImpact::position),
            Codec.LONG.fieldOf("time").forGetter(ShipShieldImpact::time),
            Codec.INT.fieldOf("absorbed").forGetter(ShipShieldImpact::absorbed),
            Codec.INT.fieldOf("broken_layers").forGetter(ShipShieldImpact::brokenLayers),
            Codec.BOOL.fieldOf("overload").forGetter(ShipShieldImpact::overload)
    ).apply(i, ShipShieldImpact::new));
    public static final StreamCodec<ByteBuf, ShipShieldImpact> STREAM_CODEC = new StreamCodec<>() {
        public ShipShieldImpact decode(ByteBuf b) {
            return new ShipShieldImpact(new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), VarLong.read(b), VarInt.read(b), VarInt.read(b), b.readBoolean());
        }
        public void encode(ByteBuf b, ShipShieldImpact hit) {
            b.writeDouble(hit.position.x); b.writeDouble(hit.position.y); b.writeDouble(hit.position.z);
            VarLong.write(b, hit.time); VarInt.write(b, hit.absorbed); VarInt.write(b, hit.brokenLayers); b.writeBoolean(hit.overload);
        }
    };
}
