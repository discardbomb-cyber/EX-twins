package dev.hurtify.relicsaddon.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.hurtify.relicsaddon.drone.DroneStackState;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationStage;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class StateGameTests {
    @GameTest(template = "test_room")
    public static void fractionalDamageAndCaps(GameTestHelper helper) {
        close(helper, ShieldController.reduction(0.25F, .6, 12), .15F, "fractional hit");
        close(helper, ShieldController.reduction(10, .35, 4), 3.5F, "drone fraction");
        close(helper, ShieldController.reduction(100, .9, 4), 4, "capacity cap");
        close(helper, ShieldController.reduction(1, 500, 4), 1, "no overabsorption");
        close(helper, ShieldController.reduction(Float.NaN, .6, 12), 0, "NaN damage");
        close(helper, ShieldController.reduction(2, Double.NaN, 12), 0, "NaN ratio");
        close(helper, ShieldController.reduction(2, .6, Float.NaN), 0, "NaN capacity");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void repairAndTogglePreserveHitHistory(GameTestHelper helper) {
        var state = ShieldStackState.DEFAULT.damagePanel(ShieldStackState.PANEL_LEFT, 5, 2.5F, 100);
        var repaired = state.repairFirstDamagedPanel(140).repairFirstDamagedPanel(160);
        helper.assertTrue(repaired.left() == 9, "Two repairs restore exactly two integrity");
        helper.assertTrue(repaired.lastActiveGameTime() == 100 && repaired.lastHitPanel() == ShieldStackState.PANEL_LEFT,
                "Repair must not trigger a fresh hit pulse or delay");
        helper.assertTrue(repaired.withEnabled(false, 180).withEnabled(true, 200).lastActiveGameTime() == 100,
                "Toggle must preserve hit time");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void droneCooldownAtWorldStartAndAfterToggle(GameTestHelper helper) {
        helper.assertTrue(DroneStackState.DEFAULT.ready(0, 80), "Fresh drones must work at world tick zero");
        var hit = DroneStackState.DEFAULT.withIntercept(0, .1F);
        helper.assertFalse(hit.ready(79, 80), "Cooldown must also work for a tick-zero hit");
        helper.assertTrue(hit.ready(80, 80), "Cooldown opens at exact boundary");
        var toggled = hit.withEnabled(false, 20).withEnabled(true, 25);
        helper.assertFalse(toggled.ready(79, 80), "Toggle cannot bypass cooldown");
        helper.assertTrue(toggled.ready(80, 80), "Toggle cannot prolong cooldown");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void componentCodecsAndLegacyIntegers(GameTestHelper helper) {
        var old = JsonParser.parseString("{\"enabled\":true,\"lastInterceptGameTime\":3,\"lastAbsorbed\":2}");
        var drone = DroneStackState.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().withIntercept(3, .175F);
        var shield = ShieldStackState.DEFAULT.damagePanel(2, 3, .375F, 15);
        var buffer = Unpooled.buffer();
        try {
            DroneStackState.STREAM_CODEC.encode(buffer, drone);
            helper.assertTrue(drone.equals(DroneStackState.STREAM_CODEC.decode(buffer)), "Drone network roundtrip");
            ShieldStackState.STREAM_CODEC.encode(buffer, shield);
            helper.assertTrue(shield.equals(ShieldStackState.STREAM_CODEC.decode(buffer)), "Shield network roundtrip");
        } finally {
            buffer.release();
        }
        var encoded = ShieldStackState.CODEC.encodeStart(JsonOps.INSTANCE, shield).getOrThrow();
        helper.assertTrue(shield.equals(ShieldStackState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()),
                "Shield persisted roundtrip");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void pitchIndependentDirectionalSectors(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (float yaw : new float[]{0, 40, 90, 180, -90}) {
            player.setYRot(yaw);
            Vec3 forward = Vec3.directionFromRotation(0, yaw);
            Vec3 right = new Vec3(-forward.z, 0, forward.x);
            for (float pitch : new float[]{-90, 0, 90}) {
                player.setXRot(pitch);
                Vec3[] directions = {forward, right.scale(-1), right, forward.scale(-1)};
                for (int panel = 0; panel < directions.length; panel++) {
                    var source = new DamageSource(player.damageSources().generic().typeHolder(),
                            player.position().add(directions[panel].scale(3)));
                    helper.assertTrue(ShieldController.selectPanel(player, source) == panel,
                            "Wrong sector at yaw=" + yaw + " pitch=" + pitch + " panel=" + panel);
                }
            }
        }
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "test_room", timeoutTicks = 100)
    public static void repairUsesIntervalNotFreshHitDelay(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack shield = new ItemStack(ModItems.RF_SHIELD.get());
        RelicRuntime.ability(player, shield).getResearchData().complete();
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, shield);
        helper.assertTrue(RelicRuntime.canOperate(player, shield), "Default shield ability must be usable");
        long hitTime = helper.getLevel().getGameTime();
        shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT.damagePanel(0, 5, 3, hitTime));
        helper.runAfterDelay(39, () -> {
            ShieldController.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(shield.get(ModDataComponents.SHIELD_STACK_STATE.get()).front() == 7, "No repair before 40 ticks");
        });
        long firstRepairDelay = 40 + Math.floorMod(-(hitTime + 40), 20);
        helper.runAfterDelay(firstRepairDelay, () -> {
            ShieldController.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(shield.get(ModDataComponents.SHIELD_STACK_STATE.get()).front() == 8, "First repair");
        });
        helper.runAfterDelay(firstRepairDelay + 20, () -> {
            ShieldController.onPlayerTick(new PlayerTickEvent.Post(player));
            var state = shield.get(ModDataComponents.SHIELD_STACK_STATE.get());
            helper.assertTrue(state.front() == 9 && state.lastActiveGameTime() == hitTime, "Second repair after 20 ticks");
            player.discard();
            helper.succeed();
        });
    }

    private static void close(GameTestHelper helper, float actual, float expected, String message) {
        helper.assertTrue(Float.isFinite(actual) && Math.abs(actual - expected) < .0001, message + ": " + actual);
    }

    @GameTest(template = "test_room")
    public static void bypassAndCanceledDamageDoNotConsumeState(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack shield = new ItemStack(ModItems.RF_SHIELD.get());
        RelicRuntime.ability(player, shield).getResearchData().complete();
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, shield);
        for (var source : new DamageSource[]{player.damageSources().fall(), player.damageSources().genericKill()}) {
            var event = new LivingIncomingDamageEvent(player, new DamageContainer(source, 8));
            ShieldController.onIncomingDamage(event);
            close(helper, event.getAmount(), 8, "Bypass damage stays unchanged");
        }
        var canceled = new LivingIncomingDamageEvent(player, new DamageContainer(player.damageSources().generic(), 8));
        canceled.setCanceled(true);
        ShieldController.onIncomingDamage(canceled);
        close(helper, canceled.getAmount(), 8, "Canceled damage stays unchanged");
        helper.assertTrue(shield.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT)
                .equals(ShieldStackState.DEFAULT), "Bypass and canceled hits must not consume integrity");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void curioTickKeepsIdentityAndNativeToggle(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        ItemStack drone = new ItemStack(ModItems.MANA_SHIELD.get());
        RelicRuntime.ability(player, drone).getResearchData().complete();
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, drone);
        var context = new SlotContext("charm", player, 0, false, true);
        ModItems.MANA_SHIELD.get().curioTick(context, drone);
        String id = drone.get(ModDataComponents.INSTANCE_ID.get());
        helper.assertTrue(id != null && RelicRuntime.ability(player, drone).isActivationTicking(), "Curio initializes identity and activation");
        RelicRuntime.ability(player, drone).activate(player, AbilityActivationStage.END);
        ModItems.MANA_SHIELD.get().curioTick(context, drone);
        helper.assertFalse(RelicRuntime.enabled(drone) || RelicRuntime.ability(player, drone).isActivationTicking(),
                "Curio ticking cannot undo a native END toggle");
        helper.assertTrue(id.equals(drone.get(ModDataComponents.INSTANCE_ID.get())), "Curio identity is stable");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void fullItemPersistenceAndNetworkRoundTrip(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var entry : ModItems.ITEMS.getEntries()) {
            ItemStack original = new ItemStack(entry.get());
            original.set(ModDataComponents.INSTANCE_ID.get(), "playable-p01-persistence-test");
            original.set(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT.damagePanel(1, 4, 2.35F, 50));
            original.set(ModDataComponents.DRONE_STACK_STATE.get(), DroneStackState.DEFAULT.withIntercept(75, 1.275F));
            RelicRuntime.ability(player, original).setLevel(1);
            RelicRuntime.setEnabled(player, original, false);
            ItemStack saved = ItemStack.parse(helper.getLevel().registryAccess(), original.save(helper.getLevel().registryAccess())).orElseThrow();
            helper.assertTrue(ItemStack.matches(original, saved), "All components survive NBT save/load: " + entry.getId());
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                ItemStack.STREAM_CODEC.encode(buffer, original);
                ItemStack decoded = ItemStack.STREAM_CODEC.decode(buffer);
                helper.assertTrue(ItemStack.matches(original, decoded) && buffer.readableBytes() == 0,
                        "All components survive native stack network codec: " + entry.getId());
            } finally {
                buffer.release();
            }
        }
        helper.succeed();
    }
}
