package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.network.ArmageddonPayloads;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** The dedicated server must advertise both halves of the Armageddon protocol. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class NetworkRegistrationGameTests {
    @GameTest(template = TEMPLATE)
    public static void dedicatedServerRegistersArmageddonChannels(GameTestHelper helper) {
        helper.assertTrue(FMLEnvironment.dist.isDedicatedServer(), "Run this regression on the dedicated GameTest server");
        helper.assertTrue(NetworkRegistry.getCodec(ArmageddonPayloads.Blast.TYPE.id(), ConnectionProtocol.PLAY,
                PacketFlow.CLIENTBOUND) != null, "Dedicated server must register relics_addon:armageddon_blast");
        helper.assertTrue(NetworkRegistry.getCodec(ArmageddonPayloads.Fire.TYPE.id(), ConnectionProtocol.PLAY,
                PacketFlow.SERVERBOUND) != null, "Dedicated server must register Armageddon fire requests");
        helper.succeed();
    }
}
