package dev.hurtify.relicsaddon.gametest;

import com.mojang.authlib.GameProfile;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.domain.shield.ShieldStackState;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHooks;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Fixtures for the standalone GameTests: a real survival ServerPlayer (only its network transport
 * is stubbed), placed in the test level, with devices equipped in real Curios charm slots.
 */
final class DeviceTestSupport {
    static final String TEMPLATE = "test_room";
    static final String ARENA = "field_arena";

    /** A survival player at the test origin, added to the level so mobs and projectiles can reach it. */
    static ServerPlayer player(GameTestHelper helper) {
        return player(helper, new Vec3(2.5, 1, 2.5));
    }

    /** Same, standing at a position relative to the test structure. */
    static ServerPlayer player(GameTestHelper helper, Vec3 relative) {
        helper.assertTrue(GameTestHooks.isGametestServer(), "Run with runGameTestServer");
        var server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "device-test");
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { }
        };
        helper.testInfo.addListener(new Cleanup(player, channel));
        player.setGameMode(GameType.SURVIVAL);
        Vec3 position = helper.absoluteVec(relative);
        player.moveTo(position.x, position.y, position.z, 0, 0);
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.setHealth(20);
        player.invulnerableTime = 0;
        helper.getLevel().addNewPlayer(player);
        ICuriosItemHandler curios = curios(helper, player);
        curios.reset();
        var charms = curios.getStacksHandler(RelicRole.EQUIPMENT_SLOT);
        helper.assertTrue(charms.isPresent() && charms.get().getSlots() >= 2, "Players must get two charm slots from the mod's Curios data");
        // Burn off spawn immunity so the first hit in a test is a real hit.
        for (int tick = 0; tick < 61; tick++) player.tick();
        player.setHealth(20);
        player.invulnerableTime = 0;
        return player;
    }

    static ICuriosItemHandler curios(GameTestHelper helper, ServerPlayer player) {
        var inventory = CuriosApi.getCuriosInventory(player);
        helper.assertTrue(inventory.isPresent(), "Curios inventory must exist for the test player");
        return inventory.orElseThrow();
    }

    /** Equips a fresh, switched-on, fully charged device of {@code role} in charm slot {@code slot}. */
    static ItemStack equip(GameTestHelper helper, ServerPlayer player, RelicRole role, int slot) {
        AutonomousRelicItem item = switch (role) {
            case RF_SHIELD -> ModItems.RF_SHIELD.get();
            case MANA_SHIELD -> ModItems.MANA_SHIELD.get();
            case TWINS_SHIELD -> ModItems.TWINS_SHIELD.get();
            case RF_HIVE -> ModItems.RF_HIVE.get();
            case MANA_HIVE -> ModItems.MANA_HIVE.get();
            case TWINS_HIVE -> ModItems.TWINS_HIVE.get();
            default -> throw new IllegalArgumentException("Not an item-backed device: " + role);
        };
        ItemStack stack = new ItemStack(item);
        AutonomousRelicItem.ensureState(stack);
        stack.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(stack));
        curios(helper, player).setEquippedCurio(RelicRole.EQUIPMENT_SLOT, slot, stack);
        ItemStack equipped = charm(helper, player, slot);
        helper.assertTrue(equipped.is(item), "Curios did not equip " + role);
        return equipped;
    }

    static ItemStack charm(GameTestHelper helper, ServerPlayer player, int slot) {
        return curios(helper, player).getStacksHandler(RelicRole.EQUIPMENT_SLOT).orElseThrow().getStacks().getStackInSlot(slot);
    }

    static int integrity(ItemStack shield) {
        return shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT).totalIntegrity();
    }

    static void close(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-3, message + " (expected " + expected + ", got " + actual + ")");
    }

    private static final class Cleanup implements GameTestListener {
        private final ServerPlayer player;
        private final EmbeddedChannel channel;
        private boolean closed;

        Cleanup(ServerPlayer player, EmbeddedChannel channel) {
            this.player = player;
            this.channel = channel;
        }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                player.getAdvancements().stopListening();
                player.discard();
            } finally {
                channel.finishAndReleaseAll();
            }
        }

        @Override public void testStructureLoaded(GameTestInfo test) { }
        @Override public void testPassed(GameTestInfo test, GameTestRunner runner) { close(); }
        @Override public void testFailed(GameTestInfo test, GameTestRunner runner) { close(); }
        @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) { }
    }

    private DeviceTestSupport() {
    }
}
