package dev.hurtify.relicsaddon.network;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveTaskController;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client requests only; state changes are authorized and applied by the server. */
public record DeviceControlPayload(boolean charm, int slot, String identity, Action action, int value, String upgrade) implements CustomPacketPayload {
    public enum Action { TOGGLE, MODULE, UPGRADE }
    public static final Type<DeviceControlPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "device_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeviceControlPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public DeviceControlPayload decode(RegistryFriendlyByteBuf b) {
            boolean charm = b.readBoolean();
            int slot = b.readVarInt();
            String identity = b.readUtf(64);
            int action = b.readVarInt();
            return new DeviceControlPayload(charm, slot, identity, Action.values()[Math.clamp(action, 0, Action.values().length - 1)], b.readVarInt(), b.readUtf(64));
        }
        @Override public void encode(RegistryFriendlyByteBuf b, DeviceControlPayload p) {
            b.writeBoolean(p.charm).writeVarInt(p.slot).writeUtf(p.identity, 64).writeVarInt(p.action.ordinal()).writeVarInt(p.value).writeUtf(p.upgrade, 64);
        }
    };
    @Override public Type<DeviceControlPayload> type() { return TYPE; }
    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            var player = context.player();
            var stack = HiveTaskController.locate(player, payload.charm, payload.slot);
            if (!(stack.getItem() instanceof AutonomousRelicItem) || payload.identity.isEmpty() || !payload.identity.equals(stack.getOrDefault(ModDataComponents.INSTANCE_ID.get(), ""))) return;
            switch (payload.action) {
                case TOGGLE -> RelicRuntime.setEnabled(player, stack, !RelicRuntime.enabled(stack));
                case MODULE -> dev.hurtify.relicsaddon.relic.DeviceModules.toggle(player, stack, payload.value);
                case UPGRADE -> RelicRuntime.purchaseUpgrade(stack, payload.upgrade);
            }
        }));
    }
}
