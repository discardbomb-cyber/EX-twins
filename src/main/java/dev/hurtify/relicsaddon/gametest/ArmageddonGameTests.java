package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.drone.Armageddon;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.ArmageddonController;
import dev.hurtify.relicsaddon.server.HiveCombatController;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Armageddon on a real server: who may fire it, whom its blast strikes and whom it spares, what it
 * leaves of the hive's charge, calling it off, and the black hole devouring the land (unless the server
 * keeps it safe). Every test that lets a blast land runs in a batch of its own, so its blast, which
 * reaches 256 blocks, never strikes another test's creatures.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ArmageddonGameTests {
    private static final String NO_HIVE = "message.relics_addon.armageddon.no_hive", LEVEL = "message.relics_addon.armageddon.level",
            CHARGE = "message.relics_addon.armageddon.charge", RUNNING = "message.relics_addon.armageddon.running";

    @GameTest(template = ARENA, batch = "armageddon_rules")
    public static void onlyAFullTopLevelTwinsHiveFiresArmageddon(GameTestHelper helper) {
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        Vec3 target = helper.absoluteVec(new Vec3(9.5, 1, 6.5));
        int blasts = ArmageddonController.blasts(helper.getLevel());
        DeviceTestSupport.equip(helper, owner, RelicRole.RF_HIVE, 0);
        helper.assertTrue(NO_HIVE.equals(ArmageddonController.request(owner, target)), "Only a Twins hive fires Armageddon");
        ItemStack hive = DeviceTestSupport.equip(helper, owner, RelicRole.TWINS_HIVE, 0);
        helper.assertTrue(LEVEL.equals(ArmageddonController.request(owner, target)), "A hive short of the top level cannot fire it");
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        RelicRuntime.setEnabled(owner, hive, false);
        helper.assertTrue(NO_HIVE.equals(ArmageddonController.request(owner, target)), "A switched-off hive cannot fire it");
        RelicRuntime.setEnabled(owner, hive, true);
        if (DevicePower.required()) {
            hive.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(hive).withMana(DevicePower.capacity(hive) / 2));
            helper.assertTrue(CHARGE.equals(ArmageddonController.request(owner, target)), "A hive short of a full charge cannot fire it");
            hive.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(hive));
        }
        helper.assertTrue(ArmageddonController.request(owner, target) == null, "A full top-level Twins hive fires");
        helper.assertTrue(ArmageddonController.shooting(owner) && hive.has(ModDataComponents.HIVE_ARMAGEDDON.get()), "The shot is under way on the hive");
        helper.assertTrue(RUNNING.equals(ArmageddonController.request(owner, target)), "One shot at a time");
        ArmageddonController.abort(owner);
        helper.assertTrue(!ArmageddonController.shooting(owner) && !hive.has(ModDataComponents.HIVE_ARMAGEDDON.get()),
                "A shot called off before it leaves is gone without a trace");
        helper.assertTrue(ArmageddonController.blasts(helper.getLevel()) == blasts, "and bursts nowhere");
        helper.succeed();
    }

    @GameTest(template = ARENA, batch = "armageddon_strike", timeoutTicks = 320)
    public static void theBlastStrikesFoesAndSparesFriends(GameTestHelper helper) {
        Setting setting = Setting.of(helper, true, true);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 2.5));
        topHive(helper, owner);
        ServerPlayer stranger = DeviceTestSupport.player(helper, new Vec3(10.5, 1, 10.5));
        Husk husk = creature(helper, EntityType.HUSK, new Vec3(9.5, 1, 4.5));
        Wolf wolf = creature(helper, EntityType.WOLF, new Vec3(7.5, 1, 8.5));
        wolf.tame(owner);
        Vec3 target = helper.absoluteVec(new Vec3(8.5, 1, 6.5));
        helper.assertTrue(ArmageddonController.request(owner, target, Armageddon.IMPACT - 3) == null, "The shot fires");
        int[] ticks = {0};
        helper.onEachTick(() -> {
            HiveCombatController.tick(owner);
            if (++ticks[0] < Armageddon.BALL + 20) return;
            helper.assertTrue(husk.getHealth() < husk.getMaxHealth() && husk.getLastDamageSource() != null
                    && husk.getLastDamageSource().is(ArmageddonController.DAMAGE), "The blast strikes creatures");
            helper.assertTrue(husk.getLastDamageSource().getEntity() == owner, "The owner gets the credit");
            helper.assertTrue(stranger.getHealth() < stranger.getMaxHealth(), "The blast strikes other players when the server allows fights");
            helper.assertTrue(owner.isAlive() && owner.getHealth() == owner.getMaxHealth(), "The blast spares its owner");
            helper.assertTrue(wolf.isAlive() && (wolf.getLastDamageSource() == null || !wolf.getLastDamageSource().is(ArmageddonController.DAMAGE)),
                    "The blast spares its owner's pets");
            ArmageddonController.abort(owner);
            setting.restore();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "armageddon_peace", timeoutTicks = 320)
    public static void theBlastSparesPlayersWhenTheServerForbidsFights(GameTestHelper helper) {
        Setting setting = Setting.of(helper, false, true);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 2.5));
        topHive(helper, owner);
        ServerPlayer stranger = DeviceTestSupport.player(helper, new Vec3(10.5, 1, 10.5));
        Husk husk = creature(helper, EntityType.HUSK, new Vec3(9.5, 1, 4.5));
        helper.assertTrue(ArmageddonController.request(owner, helper.absoluteVec(new Vec3(8.5, 1, 6.5)), Armageddon.IMPACT - 3) == null, "The shot fires");
        int[] ticks = {0};
        helper.onEachTick(() -> {
            HiveCombatController.tick(owner);
            if (++ticks[0] < Armageddon.BALL + 20) return;
            helper.assertTrue(husk.getHealth() < husk.getMaxHealth(), "Creatures are still struck");
            helper.assertTrue(stranger.getHealth() == stranger.getMaxHealth(), "Players are spared where the server forbids fights");
            ArmageddonController.abort(owner);
            setting.restore();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "armageddon_drain", timeoutTicks = 980)
    public static void theShotSpendsTheHivesWholeCharge(GameTestHelper helper) {
        Setting setting = Setting.of(helper, false, true);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        ItemStack hive = topHive(helper, owner);
        helper.assertTrue(ArmageddonController.request(owner, helper.absoluteVec(new Vec3(9.5, 1, 6.5)), Armageddon.IMPACT - 2) == null, "The shot fires");
        helper.onEachTick(() -> {
            HiveCombatController.tick(owner);
            if (ArmageddonController.shooting(owner)) return;
            helper.assertTrue(!hive.has(ModDataComponents.HIVE_ARMAGEDDON.get()), "The hive lets go of the shot once its drones are home");
            if (DevicePower.required()) {
                helper.assertTrue(DevicePower.energy(hive).rf() == 0 && DevicePower.energy(hive).mana() == 0, "The shot took the hive's whole charge");
            }
            setting.restore();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "armageddon_abort", timeoutTicks = 120)
    public static void aShotAlreadyFiredStillLandsWhenCalledOff(GameTestHelper helper) {
        Setting setting = Setting.of(helper, false, true);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        ItemStack hive = topHive(helper, owner);
        int blasts = ArmageddonController.blasts(helper.getLevel());
        helper.assertTrue(ArmageddonController.request(owner, helper.absoluteVec(new Vec3(9.5, 1, 6.5)), Armageddon.FIRE + 2) == null, "The shot fires");
        HiveCombatController.tick(owner);
        ArmageddonController.abort(owner);
        helper.assertTrue(!ArmageddonController.shooting(owner) && !hive.has(ModDataComponents.HIVE_ARMAGEDDON.get()), "The shot is called off");
        helper.assertTrue(ArmageddonController.blasts(helper.getLevel()) == blasts, "and nothing bursts before its time");
        helper.onEachTick(() -> {
            if (ArmageddonController.blasts(helper.getLevel()) <= blasts) return;
            setting.restore();
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, batch = "armageddon_devour", timeoutTicks = 160, skyAccess = true)
    public static void theBlackHoleDevoursTheLand(GameTestHelper helper) {
        devours(helper, false);
    }

    @GameTest(template = ARENA, batch = "armageddon_safe", timeoutTicks = 160, skyAccess = true)
    public static void aSafeServerKeepsItsLand(GameTestHelper helper) {
        devours(helper, true);
    }

    /** The black hole hangs high over the arena, where a few blocks float round it and nothing else is in its reach. */
    private static void devours(GameTestHelper helper, boolean safe) {
        Setting setting = Setting.of(helper, false, safe);
        ServerPlayer owner = DeviceTestSupport.player(helper, new Vec3(2.5, 1, 6.5));
        topHive(helper, owner);
        BlockPos middle = new BlockPos(6, 120, 6);
        BlockPos[] floating = {middle.offset(3, 0, 0), middle.offset(0, -2, 2), middle.offset(-4, 1, -1)};
        for (BlockPos at : floating) helper.setBlock(at, Blocks.STONE);
        helper.assertTrue(ArmageddonController.request(owner, helper.absoluteVec(Vec3.atCenterOf(middle)), Armageddon.ARRIVE - 1) == null, "The shot fires");
        int[] ticks = {0};
        helper.onEachTick(() -> {
            HiveCombatController.tick(owner);
            if (++ticks[0] < 40) return;
            for (BlockPos at : floating) {
                helper.assertTrue(helper.getBlockState(at).isAir() != safe, safe ? "A safe server keeps its blocks" : "The black hole devours the blocks round it");
                helper.setBlock(at, Blocks.AIR);
            }
            ArmageddonController.abort(owner);
            setting.restore();
            helper.succeed();
        });
    }

    /** Equips a switched-on, fully charged Twins hive at the top level. */
    private static ItemStack topHive(GameTestHelper helper, ServerPlayer owner) {
        ItemStack hive = DeviceTestSupport.equip(helper, owner, RelicRole.TWINS_HIVE, 0);
        hive.set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, DeviceProgression.MAX_LEVEL, 0, 0));
        RelicRuntime.setEnabled(owner, hive, true);
        hive.set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.full(hive));
        return hive;
    }

    private static <T extends LivingEntity> T creature(GameTestHelper helper, EntityType<T> type, Vec3 relative) {
        T creature = type.create(helper.getLevel());
        helper.assertTrue(creature != null, type + " fixture");
        Vec3 at = helper.absoluteVec(relative);
        creature.moveTo(at.x, at.y, at.z);
        if (creature instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(creature), "The creature enters the level");
        return creature;
    }

    /** The server's fights and Armageddon's safety for one test, put back as they were when it ends, however it ends. */
    private static final class Setting implements GameTestListener {
        private final MinecraftServer server;
        private final boolean pvp, safe;
        private boolean restored;

        private Setting(MinecraftServer server) {
            this.server = server;
            this.pvp = server.isPvpAllowed();
            this.safe = AddonConfig.ARMAGEDDON_SAFE.get();
        }

        static Setting of(GameTestHelper helper, boolean pvp, boolean safe) {
            Setting setting = new Setting(helper.getLevel().getServer());
            helper.testInfo.addListener(setting);
            helper.getLevel().getServer().setPvpAllowed(pvp);
            AddonConfig.ARMAGEDDON_SAFE.set(safe);
            return setting;
        }

        void restore() {
            if (restored) return;
            restored = true;
            server.setPvpAllowed(pvp);
            AddonConfig.ARMAGEDDON_SAFE.set(safe);
            for (var level : server.getAllLevels()) ArmageddonController.forgetAll(level);
        }

        @Override public void testStructureLoaded(GameTestInfo test) { }
        @Override public void testPassed(GameTestInfo test, GameTestRunner runner) { restore(); }
        @Override public void testFailed(GameTestInfo test, GameTestRunner runner) { restore(); }
        @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) { }
    }

    private ArmageddonGameTests() {
    }
}
