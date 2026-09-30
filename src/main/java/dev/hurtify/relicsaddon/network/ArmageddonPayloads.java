package dev.hurtify.relicsaddon.network;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.server.ArmageddonController;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Armageddon's two messages: the owner's request to fire at a point (checked again on the server), and
 * the blast, told to every client near enough to see it, wherever the owner is.
 */
public final class ArmageddonPayloads {
    /** Clients this far from a blast are told of it: well past its edge, since it lights the whole sky. */
    private static final double TOLD = 1024;
    private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /** The owner confirmed the shot at {@code target}. */
    public record Fire(Vec3 target) implements CustomPacketPayload {
        public static final Type<Fire> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "armageddon_fire"));
        public static final StreamCodec<ByteBuf, Fire> STREAM_CODEC = StreamCodec.composite(VEC3, Fire::target, Fire::new);

        @Override
        public Type<Fire> type() {
            return TYPE;
        }
    }

    /** A blast at {@code centre}, landing at game time {@code impactAt}. */
    public record Blast(Vec3 centre, long impactAt) implements CustomPacketPayload {
        public static final Type<Blast> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "armageddon_blast"));
        public static final StreamCodec<ByteBuf, Blast> STREAM_CODEC = StreamCodec.composite(
                VEC3, Blast::centre, ByteBufCodecs.VAR_LONG, Blast::impactAt, Blast::new);

        @Override
        public Type<Blast> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(Fire.TYPE, Fire.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // The server can still say no (the charge ran down while the question was open, say): tell the owner why.
            String refused = ArmageddonController.request(player, payload.target());
            if (refused != null) player.displayClientMessage(Component.translatable(refused).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }));
        // Only ever run on a client, so the client class is loaded there alone.
        registrar.playToClient(Blast.TYPE, Blast.STREAM_CODEC, (payload, context) -> context.enqueueWork(
                () -> dev.hurtify.relicsaddon.client.ArmageddonVisual.blast(payload.centre(), payload.impactAt())));
    }

    /** Tells every client near enough of a blast. */
    public static void blast(ServerLevel level, Vec3 centre, long impactAt) {
        PacketDistributor.sendToPlayersNear(level, null, centre.x, centre.y, centre.z, TOLD, new Blast(centre, impactAt));
    }

    private ArmageddonPayloads() {
    }
}
