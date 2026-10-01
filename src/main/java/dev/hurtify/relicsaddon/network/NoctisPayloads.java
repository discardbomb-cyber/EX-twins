package dev.hurtify.relicsaddon.network;

import dev.hurtify.relicsaddon.RelicsAddon;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** The moments of the Noctis weapons that the clients near by are shown. */
public final class NoctisPayloads {
    /** Clients this far from a moment are told of it. */
    private static final double TOLD = 160;
    private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /** What happened. */
    public enum Kind {
        /** A thrust landed at {@code at}, the spear pointing along {@code along}. */
        COLLAPSE,
        /** The light cut: a disc of {@code size} blocks spun out from {@code at}, facing along {@code along}. */
        LIGHT_CUT,
        /** The quantum beam from {@code at} along {@code along} for {@code size} blocks. */
        BEAM,
        /** A light spear of the Black Halo falling from {@code at} to {@code along} (a point here). */
        HALO_SPEAR,
        /** The scythe swung at {@code at} along {@code along} (its reach {@code size}), its aura with it. */
        SWEEP,
        /** The wormhole's scan starting at {@code at}, {@code size} blocks across. */
        WORMHOLE_SCAN,
        /** The wormhole's dome bursting at {@code at}, {@code size} blocks across. */
        WORMHOLE_BURST,
        /** A creature at {@code at} thrown through the wormhole to {@code along}. */
        WORMHOLE_THROW
    }

    private static final StreamCodec<ByteBuf, Kind> KIND = ByteBufCodecs.VAR_INT.map(
            ordinal -> ordinal >= 0 && ordinal < Kind.values().length ? Kind.values()[ordinal] : Kind.COLLAPSE, Kind::ordinal);

    public record Moment(Kind kind, Vec3 at, Vec3 along, float size) implements CustomPacketPayload {
        public static final Type<Moment> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "noctis_moment"));
        public static final StreamCodec<ByteBuf, Moment> STREAM_CODEC = StreamCodec.composite(
                KIND, Moment::kind, VEC3, Moment::at, VEC3, Moment::along, ByteBufCodecs.FLOAT, Moment::size, Moment::new);

        @Override
        public Type<Moment> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("4");
        // Only ever run on a client, so the client class is loaded there alone.
        registrar.playToClient(Moment.TYPE, Moment.STREAM_CODEC, (payload, context) -> context.enqueueWork(
                () -> dev.hurtify.relicsaddon.client.NoctisFx.told(payload)));
    }

    /** Tells every client near enough of a moment. */
    public static void tell(ServerLevel level, Kind kind, Vec3 at, Vec3 along, double size) {
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, TOLD, new Moment(kind, at, along, (float) size));
    }

    private NoctisPayloads() {
    }
}
