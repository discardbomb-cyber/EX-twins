package dev.hurtify.relicsaddon.gametest;

import com.mojang.serialization.JsonOps;
import dev.hurtify.relicsaddon.drone.HiveCombatState;
import dev.hurtify.relicsaddon.drone.HiveStackState;
import dev.hurtify.relicsaddon.drone.HiveType;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.List;
import java.util.UUID;

/** Focused state/geometry contracts; live volley coverage is added with the event wiring. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class HiveCombatGameTests {
    @GameTest(template = "test_room")
    public static void individualCadencesStayWithinUpgradeBounds(GameTestHelper helper) {
        UUID owner = UUID.fromString("0023a0ba-6fe1-4bad-9c58-344e3932a946");
        for (HiveType type : HiveType.values()) {
            for (int unit = 0; unit < HiveType.MAX_DRONES; unit++) {
                int early = HiveCombatController.attackIntervalFor(owner, type, unit, 100, 100);
                int late = HiveCombatController.attackIntervalFor(owner, type, unit, 100, 40);
                helper.assertTrue(early >= 20 && early <= 100, type + " level-zero cadence stays in 1..5 seconds");
                helper.assertTrue(late >= 20 && late <= 40, type + " level-ten cadence stays in 1..2 seconds");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void toggleAndRepairDoNotResetUnitAttackTimers(GameTestHelper helper) {
        HiveStackState.Unit unit = new HiveStackState.Unit(5, 7, 3, 0, 0, 0, 1234);
        HiveStackState state = new HiveStackState(true, List.of(unit));
        HiveStackState repaired = state.prepare(1, 8, 40, true);
        HiveStackState toggled = repaired.withEnabled(false).withEnabled(true);
        helper.assertTrue(toggled.units().getFirst().attackReadyAt() == 1234,
                "Toggle and repair preserve a virtual drone's independent attack timer");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void combatComponentBoundsAndRoundTrips(GameTestHelper helper) {
        HiveCombatState state = new HiveCombatState(true, 9, 4, 1, 2, 3,
                java.util.Collections.nCopies(105, new HiveCombatState.Shot(249, -1, 9,
                        Double.NaN, 0, 0, 1, 2, 3, -2)));
        helper.assertTrue(HiveType.MAX_DRONES == 250 && HiveCombatState.MAX_SHOTS == 100
                        && state.shots().size() == HiveCombatState.MAX_SHOTS && state.shots().getFirst().unit() == 249
                        && state.shots().getFirst().kind() == 3 && state.shots().getFirst().startX() == 0,
                "Combat packets are finite and capped to one hundred visual shots");
        var buffer = Unpooled.buffer();
        try {
            HiveCombatState.STREAM_CODEC.encode(buffer, state);
            helper.assertTrue(state.equals(HiveCombatState.STREAM_CODEC.decode(buffer)), "Combat component network roundtrip");
        } finally {
            buffer.release();
        }
        helper.assertTrue(state.equals(HiveCombatState.CODEC.parse(JsonOps.INSTANCE,
                HiveCombatState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow()),
                "Combat component persistent roundtrip");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void solidBlocksPreventVirtualBoltLineOfSight(GameTestHelper helper) {
        BlockPos block = new BlockPos(3, 2, 3);
        helper.setBlock(block, Blocks.STONE);
        ServerPlayer player = AutonomousRelicGameTests.survivalPlayer(helper);
        Vec3 start = helper.absoluteVec(new Vec3(3.5, 2.5, 1.5));
        Vec3 end = helper.absoluteVec(new Vec3(3.5, 2.5, 5.5));
        helper.assertFalse(HiveCombatController.lineOfSight(player.serverLevel(), start, end),
                "Virtual bolt damage cannot pass through a collider");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void friendlyTargetsAreRejected(GameTestHelper helper) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        owner.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        ServerPlayer ally = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "combat-hive-ally"), ClientInformation.createDefault());
        PlayerTeam team = owner.getScoreboard().addPlayerTeam("combat_hive_team");
        team.setAllowFriendlyFire(false);
        owner.getScoreboard().addPlayerToTeam(owner.getScoreboardName(), team);
        owner.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
        try {
            ally.setPos(owner.position().add(2, 0, 0));
            helper.assertFalse(HiveCombatController.validTarget(owner, ally, false),
                    "PvP-disabled teammates are never swarm targets");
        } finally {
            ally.discard();
            owner.getScoreboard().removePlayerTeam(team);
        }
        helper.succeed();
    }

    @GameTest(template = "field_arena", timeoutTicks = 180)
    public static void manaHiveFiresRealBlueBoltsAndToggleStopsThem(GameTestHelper helper) {
        liveVolley(helper, HiveType.MANA, 1);
    }

    @GameTest(template = "field_arena", timeoutTicks = 180)
    public static void rfHiveFiresRealLightningShots(GameTestHelper helper) {
        liveVolley(helper, HiveType.RF, 0);
    }

    private static void liveVolley(GameTestHelper helper, HiveType type, int expectedKind) {
        ServerPlayer owner = AutonomousRelicGameTests.survivalPlayer(helper);
        owner.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        ItemStack hive = equipHive(owner, type);
        // Husk is naturally sunlight-immune, so post-toggle health is attributable to the hive, not daylight.
        Husk target = new Husk(EntityType.HUSK, helper.getLevel());
        target.setPos(owner.position().add(0, 0, 3));
        target.setNoAi(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(200);
        helper.assertTrue(helper.getLevel().addFreshEntity(target), "Combat target enters the real field arena");
        owner.setLastHurtMob(target);
        float healthBefore = target.getHealth();
        boolean[] sawExpectedShot = {false};
        float[] healthAfterDisable = {Float.NaN};
        for (int delay = 1; delay <= 160; delay++) {
            helper.runAfterDelay(delay, () -> {
                HiveCombatController.tick(owner);
                HiveCombatState state = hive.getOrDefault(ModDataComponents.HIVE_COMBAT_STATE.get(), HiveCombatState.DEFAULT);
                sawExpectedShot[0] |= state.shots().stream().anyMatch(shot -> shot.kind() == expectedKind && finite(shot));
            });
        }
        helper.runAfterDelay(161, () -> {
            helper.assertTrue(target.getHealth() < healthBefore, type + " hive deals real server-authoritative combat damage");
            helper.assertTrue(sawExpectedShot[0], type + " hive synchronizes its expected shot type with finite world coordinates");
            RelicRuntime.setEnabled(owner, hive, false);
            healthAfterDisable[0] = target.getHealth();
        });
        for (int delay = 162; delay <= 171; delay++) {
            helper.runAfterDelay(delay, () -> HiveCombatController.tick(owner));
        }
        helper.runAfterDelay(172, () -> {
            HiveCombatController.tick(owner);
            helper.assertTrue(target.getHealth() == healthAfterDisable[0] && !RelicRuntime.enabled(hive),
                    "Disabling a hive stops future combat damage without relying on private flight storage");
            target.discard();
            helper.succeed();
        });
    }

    private static ItemStack equipHive(ServerPlayer owner, HiveType type) {
        ItemStack hive = new ItemStack(switch (type) {
            case RF -> ModItems.RF_HIVE.get();
            case MANA -> ModItems.MANA_HIVE.get();
            case TWINS -> ModItems.TWINS_HIVE.get();
        });
        CuriosApi.getCuriosInventory(owner).orElseThrow().setEquippedCurio("charm", 0, hive);
        RelicRuntime.ability(owner, hive).setLevel(0);
        RelicRuntime.ability(owner, hive).getResearchData().complete();
        return hive;
    }

    private static boolean finite(HiveCombatState.Shot shot) {
        return Double.isFinite(shot.startX()) && Double.isFinite(shot.startY()) && Double.isFinite(shot.startZ())
                && Double.isFinite(shot.endX()) && Double.isFinite(shot.endY()) && Double.isFinite(shot.endZ());
    }

    private HiveCombatGameTests() { }
}
