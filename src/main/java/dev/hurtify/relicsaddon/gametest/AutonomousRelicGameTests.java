package dev.hurtify.relicsaddon.gametest;

import com.mojang.authlib.GameProfile;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.server.EquippedRelicSetResolver;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import io.netty.channel.embedded.EmbeddedChannel;
import it.hurts.sskirillss.relics.api.relics.abilities.activation.AbilityActivationStage;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** Dev-server integration tests for the isolated run-gametest world and its 5x4x5 test_room. */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class AutonomousRelicGameTests {
    private static final float EPSILON = 0.0001F;
    private static final Vec3 FRONT = new Vec3(0, 0, 1);
    private static final Vec3 LEFT = new Vec3(1, 0, 0);
    private static final RelicRole[] ITEM_ROLES = {
            RelicRole.RF_SHIELD, RelicRole.MANA_SHIELD, RelicRole.TWINS_SHIELD,
            RelicRole.RF_HIVE, RelicRole.MANA_HIVE, RelicRole.TWINS_HIVE
    };

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void rfShieldArrowBaseline(GameTestHelper helper) {
        baseline(helper, RelicRole.RF_SHIELD, 8);
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void manaShieldArrowBaseline(GameTestHelper helper) {
        baseline(helper, RelicRole.MANA_SHIELD, 8);
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void twinsShieldArrowBaseline(GameTestHelper helper) {
        baseline(helper, RelicRole.TWINS_SHIELD, 8);
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void brokenSectorHasNoFallback(GameTestHelper helper) {
        runTest(helper, player -> {
            ItemStack shield = equip(helper, player, RelicRole.RF_SHIELD);
            ShieldStackState broken = new ShieldStackState(true, 0, 12, 12, 12,
                    ShieldStackState.PANEL_FRONT, 2.75F, helper.getLevel().getGameTime());
            broken = broken.withCellsAndBuffer(broken.cells(), 0, broken.moves(), broken.gatherTime());
            shield.set(ModDataComponents.SHIELD_STACK_STATE.get(), broken);
            close(helper, controllerArrow(helper, player, 8, FRONT), 8, "Broken front sector has no fallback");
            helper.assertTrue(shieldState(shield).equals(broken), "Broken shield sector is not hit again");
            close(helper, experience(player, shield), 0, "Broken sector earns no XP");
            close(helper, controllerArrow(helper, player, 8, LEFT), 0, "Intact side sector still protects");
            helper.assertTrue(shieldState(shield).lastHitPanel() == ShieldStackState.PANEL_LEFT,
                    "Side projectile must select the intact left sector");
            helper.assertTrue(shieldState(shield).front() == 0 && shieldState(shield).left() == 4,
                    "Only the selected intact side loses local integrity when its buffer is empty");
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void disabledRelicsDoNotProtect(GameTestHelper helper) {
        runTest(helper, player -> {
            for (RelicRole role : ITEM_ROLES) {
                clearRelics(helper, player);
                ItemStack stack = equip(helper, player, role);
                RelicRuntime.setEnabled(player, stack, false);
                ItemStack before = stack.copy();
                helper.assertTrue(EquippedRelicSetResolver.findFirstActive(player, role.slot(), role).isEmpty(),
                        role + " must not resolve while disabled");
                hurtWithArrow(helper, player, 8, 8);
                helper.assertTrue(ItemStack.isSameItemSameComponents(before, stack), role + " changed while disabled");
                close(helper, experience(player, stack), 0, role + " must not earn disabled XP");
            }
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void inventoryRelicsDoNotProtect(GameTestHelper helper) {
        runTest(helper, player -> {
            for (RelicRole role : ITEM_ROLES) {
                clearRelics(helper, player);
                ItemStack stack = equip(helper, player, role);
                curios(helper, player).setEquippedCurio(role.slot(), 0, ItemStack.EMPTY);
                player.getInventory().setItem(0, stack);
                helper.assertTrue(EquippedRelicSetResolver.findFirstActive(player, role.slot(), role).isEmpty(),
                        role + " in ordinary inventory must not resolve as equipped");
                hurtWithArrow(helper, player, 8, 8);
                close(helper, experience(player, stack), 0, role + " must not earn unequipped XP");
                helper.assertTrue(defaultState(stack, role), role + " unequipped state changed");
                player.getInventory().clearContent();
            }
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void inactiveCuriosSlotsDoNotProtect(GameTestHelper helper) {
        runTest(helper, player -> {
            for (RelicRole role : ITEM_ROLES) {
                clearRelics(helper, player);
                ItemStack stack = equip(helper, player, role);
                ICuriosItemHandler inventory = curios(helper, player);
                inventory.setSlotActive(role.slot(), 0, false);
                helper.assertTrue(!inventory.isSlotActive(role.slot(), 0), "Curios slot must actually be inactive");
                helper.assertTrue(EquippedRelicSetResolver.findFirstActive(player, role.slot(), role).isEmpty(),
                        role + " in an inactive Curios slot must not resolve");
                hurtWithArrow(helper, player, 8, 8);
                close(helper, experience(player, stack), 0, role + " inactive slot earns no XP");
                helper.assertTrue(defaultState(stack, role), role + " inactive-slot state changed");
                inventory.setSlotActive(role.slot(), 0, true);
                hurtWithArrow(helper, player, 8, role.isShield() ? 0 : 8);
                close(helper, experience(player, stack), role.isShield() ? 2 : 0, role + " only shields use the incoming-damage listener");
            }
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void nativeShieldTogglePreservesDamage(GameTestHelper helper) {
        runTest(helper, player -> {
            ItemStack shield = equip(helper, player, RelicRole.MANA_SHIELD);
            hurtWithArrow(helper, player, 4, 0);
            ShieldStackState damaged = shieldState(shield);
            nativeToggle(helper, player, shield, AbilityActivationStage.END);
            helper.assertTrue(shieldState(shield).equals(damaged.withEnabled(false, 0)), "END preserves shield history");
            hurtWithArrow(helper, player, 4, 4);
            close(helper, experience(player, shield), 1, "END prevents further XP");
            nativeToggle(helper, player, shield, AbilityActivationStage.START);
            helper.assertTrue(shieldState(shield).equals(damaged), "START preserves damaged integrity and hit time");
            hurtWithArrow(helper, player, 4, 0);
            helper.assertTrue(shieldState(shield).sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 8
                    && shieldState(shield).front() == 12, "Reactivated shield resumes using remaining common buffer");
            close(helper, experience(player, shield), 2, "START resumes XP awards");
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void legacyAbsorptionOverrideCannotLeakDamage(GameTestHelper helper) {
        runTest(helper, player -> {
            ItemStack shield = equip(helper, player, RelicRole.RF_SHIELD);
            emptyBuffer(shield);
            var ability = RelicRuntime.ability(player, shield);
            ability.setLevel(0);
            hurtWithArrow(helper, player, 4, 0);
            close(helper, experience(player, shield), 1, "Baseline source XP");

            ability.getStatData("absorption").setOverrideValue(0.80D);
            hurtWithArrow(helper, player, 4, 0);
            close(helper, shieldState(shield).lastAbsorbed(), 4, "Legacy override cannot make an intact cell leak");
            close(helper, experience(player, shield), 2, "Actual absorption determines XP");

            ability.getStatData("absorption").setOverrideValue(100.0D);
            hurtWithArrow(helper, player, 4, 0);
            close(helper, shieldState(shield).lastAbsorbed(), 4, "Exact remaining HP is fully absorbed");
            close(helper, experience(player, shield), 3, "Actual absorption determines source XP");
            hurtWithArrow(helper, player, 4, 4);
            close(helper, experience(player, shield), 3, "Hole cannot earn XP");
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void shieldAbsorptionIsFixedAndUpgradesRemainSeparate(GameTestHelper helper) {
        runTest(helper, player -> {
            for (RelicRole role : RelicRole.shields()) {
                clearRelics(helper, player);
                ItemStack stack = equip(helper, player, role);
                var ability = RelicRuntime.ability(player, stack);
                var absorption = ability.getStatData("absorption");
                close(helper, absorption.getTemplate().getTargetValue().getTargetValue(), 1,
                        role + " always blocks all damage allowed by HP");
                // Rank/level live on this stack. Never mutate the globally cached Relics templates.
                ((AutonomousRelicItem) stack.getItem()).getRelicData(player, stack).getLevelingData().setRank(1);
                int levels = ability.getTemplate().getInitialMaxLevel();
                helper.assertTrue(levels == 10, "Ten shield levels improve radius and buffer, not absorption ratio");
                for (int level = 0; level <= levels; level++) {
                    ability.setLevel(level);
                    close(helper, absorption.getValue(), 1, role + " fixed absorption");
                }
                hurtWithArrow(helper, player, 4, 0);
                close(helper, experience(player, stack), 1, role + " actual absorption determines source XP");
            }
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void capacityAndExperienceAreCapped(GameTestHelper helper) {
        runTest(helper, player -> {
            ItemStack shield = equip(helper, player, RelicRole.TWINS_SHIELD);
            emptyBuffer(shield);
            RelicRuntime.ability(player, shield).getStatData("absorption").setOverrideValue(0.9D);
            close(helper, controllerArrow(helper, player, 20, FRONT), 8, "Shield cannot absorb more than sector integrity");
            close(helper, shieldState(shield).lastAbsorbed(), 12, "Sector capacity is twelve");
            close(helper, experience(player, shield), 2, "XP award is capped at two per hit");
        });
    }

    @GameTest(template = "test_room", timeoutTicks = 160)
    public static void fakePlayerCannotOperateRelics(GameTestHelper helper) {
        requireGameTestServer(helper);
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "relic-fake-test"));
        positionPlayer(helper, player);
        var charms = curios(helper, player).getStacksHandler("charm").orElseThrow();
        if (charms.getSlots() < 2) charms.grow(2 - charms.getSlots());
        try {
            for (RelicRole role : ITEM_ROLES) {
                clearRelics(helper, player);
                ItemStack stack = equip(helper, player, role);
                ItemStack before = stack.copy();
                helper.assertTrue(!EquippedRelicSetResolver.isRealPlayer(player), "NeoForge FakePlayer must be rejected");
                helper.assertTrue(EquippedRelicSetResolver.findEquipped(player, role).isEmpty(), "Fake equipment lookup is gated");
                helper.assertTrue(EquippedRelicSetResolver.findFirstActive(player, role.slot(), role).isEmpty(),
                        "Fake active lookup is gated");
                close(helper, controllerArrow(helper, player, 8, FRONT), 8, "Fake player damage remains unchanged");
                helper.assertTrue(ItemStack.isSameItemSameComponents(before, stack), role + " fake-player state changed");
                close(helper, experience(player, stack), 0, "Fake player earns no absorption XP");
            }
            helper.succeed();
        } finally {
            player.discard();
        }
    }

    private static void baseline(GameTestHelper helper, RelicRole role, float absorbed) {
        runTest(helper, player -> {
            ItemStack stack = equip(helper, player, role);
            close(helper, RelicRuntime.ability(player, stack).getStatData("absorption").getValue(), absorbed / 8.0,
                    role + " fixed initial absorption");
            helper.assertTrue(EquippedRelicSetResolver.findFirstActive(player, role.slot(), role).orElseThrow() == stack,
                    "Resolver must return the actual equipped stack");
            long now = helper.getLevel().getGameTime();
            hurtWithArrow(helper, player, 8, 8 - absorbed);
            ShieldStackState state = shieldState(stack);
            helper.assertTrue(state.sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 8
                    && state.cells().stream().allMatch(hp -> hp == 12), role + " spends eight common HP before damaging any cell");
            helper.assertTrue(state.lastHitPanel() == ShieldStackState.PANEL_FRONT && state.lastActiveGameTime() == now,
                    role + " records the hit sector and time");
            close(helper, state.lastAbsorbed(), absorbed, role + " fractional absorbed state");
            close(helper, experience(player, stack), absorbed * 0.25, role + " source XP through the real damage event bus");
        });
    }

    private static void nativeToggle(GameTestHelper helper, ServerPlayer player, ItemStack stack, AbilityActivationStage stage) {
        var ability = RelicRuntime.ability(player, stack);
        helper.assertTrue(ability.activate(player, stage), "Native Relics activation must accept " + stage);
        boolean enabled = stage == AbilityActivationStage.START;
        helper.assertTrue(RelicRuntime.enabled(stack) == enabled, "Native " + stage + " synchronizes the addon enabled component");
        helper.assertTrue(ability.isActivationTicking() == enabled, "Native " + stage + " synchronizes activation ticking");
    }

    private static void runTest(GameTestHelper helper, Consumer<ServerPlayer> assertions) {
        ServerPlayer player = survivalPlayer(helper);
        assertions.accept(player);
        helper.succeed();
    }

    /** Shared with StateGameTests: no login, no network handshake, no automatic connection ticks. */
    static ServerPlayer survivalPlayer(GameTestHelper helper) {
        requireGameTestServer(helper);
        var server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "relic-test-player");
        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        // Only test transport is stubbed. Relics/Curios still use a real survival ServerPlayer;
        // sending login payloads here would require a real NeoForge client handshake.
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
            }

            @Override
            public void send(Packet<?> packet, PacketSendListener listener) {
            }
        };
        helper.testInfo.addListener(new PlayerCleanup(player, channel));
        positionPlayer(helper, player);
        ICuriosItemHandler inventory = curios(helper, player);
        inventory.reset();
        helper.assertTrue(inventory.getStacksHandler("charm").isPresent(),
                "Curios must initialize slots from the loaded server data managers");
        var charms = inventory.getStacksHandler("charm").orElseThrow();
        if (charms.getSlots() < 2) charms.grow(2 - charms.getSlots());
        // Extra slot is fixture-only; production keeps the modpack slot count.
        // Prime only this player's spawn-immunity counter before equipping anything. This does not
        // advance world time, tick its connection, or run PlayerTickEvent repair logic.
        for (int tick = 0; tick < 61; tick++) {
            player.tick();
        }
        helper.assertTrue(!player.isCreative() && !player.isSpectator() && !(player instanceof FakePlayer),
                "Fixture must be a survival server player");
        return player;
    }

    private static void requireGameTestServer(GameTestHelper helper) {
        helper.assertTrue(GameTestHooks.isGametestServer(), "Run these fixtures with runGameTestServer in the separate run-gametest world");
    }

    private static void positionPlayer(GameTestHelper helper, ServerPlayer player) {
        player.setGameMode(GameType.SURVIVAL);
        Vec3 position = helper.absoluteVec(new Vec3(2.5, 1, 2.5));
        player.moveTo(position.x, position.y, position.z, 0, 0);
        player.setYRot(0);
        player.setXRot(0);
        player.setNoGravity(true);
        player.setInvulnerable(false);
        player.getAbilities().invulnerable = false;
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.setAbsorptionAmount(0);
        player.setHealth(20);
        player.invulnerableTime = 0;
    }

    private static ICuriosItemHandler curios(GameTestHelper helper, ServerPlayer player) {
        var inventory = CuriosApi.getCuriosInventory(player);
        helper.assertTrue(inventory.isPresent(), "Curios must provide a server-side player inventory");
        return inventory.orElseThrow();
    }

    private static ItemStack equip(GameTestHelper helper, ServerPlayer player, RelicRole role) {
        AutonomousRelicItem item = switch (role) {
            case RF_SHIELD -> ModItems.RF_SHIELD.get();
            case MANA_SHIELD -> ModItems.MANA_SHIELD.get();
            case TWINS_SHIELD -> ModItems.TWINS_SHIELD.get();
            default -> throw new IllegalArgumentException("Role is not item-backed: " + role);
            case RF_HIVE -> ModItems.RF_HIVE.get();
            case MANA_HIVE -> ModItems.MANA_HIVE.get();
            case TWINS_HIVE -> ModItems.TWINS_HIVE.get();
        };
        ItemStack stack = new ItemStack(item);
        helper.assertTrue(stack.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("curios", role.slot()))) == role.available(),
                role + " requires its loaded Curios slot tag");
        ICuriosItemHandler inventory = curios(helper, player);
        var slot = inventory.getStacksHandler(role.slot());
        helper.assertTrue(slot.isPresent() && slot.orElseThrow().getSlots() > 0, "Missing loaded Curios slot: " + role.slot());
        helper.assertTrue(CuriosApi.isStackValid(new SlotContext(role.slot(), player, 0, false, true), stack) == role.available(),
                role + " must be valid in its real Curios slot");
        inventory.setEquippedCurio(role.slot(), 0, stack);
        ItemStack equipped = slot.orElseThrow().getStacks().getStackInSlot(0);
        helper.assertTrue(equipped.is(item), "Curios did not equip " + role);
        var ability = RelicRuntime.ability(player, equipped);
        ability.setLevel(0);
        ability.getResearchData().complete();
        helper.assertTrue(ability.getTemplate().getStats().containsKey(role.isHive() ? "drone_count" : "absorption"),
                "Fresh test config must contain its primary stat");
        helper.assertTrue(ability.canPlayerUse(player), role + " baseline ability must be usable");
        return equipped;
    }

    private static void clearRelics(GameTestHelper helper, ServerPlayer player) {
        ICuriosItemHandler inventory = curios(helper, player);
        inventory.setEquippedCurio("charm", 0, ItemStack.EMPTY);
        inventory.setEquippedCurio("charm", 1, ItemStack.EMPTY);
    }

    private static void hurtWithArrow(GameTestHelper helper, ServerPlayer player, float damage, float expectedDamage) {
        player.setHealth(20);
        player.setAbsorptionAmount(0);
        player.invulnerableTime = 0;
        player.setDeltaMovement(Vec3.ZERO);
        withArrow(helper, player, FRONT, source -> {
            boolean accepted = player.hurt(source, damage);
            helper.assertTrue(accepted || expectedDamage == 0, "Unblocked arrow damage must be accepted by ServerPlayer.hurt");
            close(helper, player.getHealth(), 20 - expectedDamage, "Real damage must match relic reduction");
            return null;
        });
    }

    private static float controllerArrow(GameTestHelper helper, ServerPlayer player, float damage, Vec3 direction) {
        return withArrow(helper, player, direction, source -> controllerDamage(player, source, damage));
    }

    private static float controllerDamage(ServerPlayer player, DamageSource source, float damage) {
        player.setHealth(20);
        player.invulnerableTime = 0;
        LivingIncomingDamageEvent event = new LivingIncomingDamageEvent(player, new DamageContainer(source, damage));
        ShieldController.onIncomingDamage(event);
        return event.getAmount();
    }

    private static <T> T withArrow(GameTestHelper helper, ServerPlayer player, Vec3 direction, Function<DamageSource, T> action) {
        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        Vec3 position = player.position().add(direction.scale(1.25)).add(0, 0.5, 0);
        arrow.setPos(position.x, position.y, position.z);
        arrow.setNoGravity(true);
        helper.assertTrue(helper.getLevel().addFreshEntity(arrow), "Real Arrow entity must enter the test level");
        try {
            DamageSource source = player.damageSources().arrow(arrow, null);
            helper.assertTrue(source.getDirectEntity() == arrow && source.is(DamageTypeTags.IS_PROJECTILE),
                    "Arrow damage must carry a real projectile and loaded damage-type tags");
            return action.apply(source);
        } finally {
            arrow.discard();
        }
    }

    private static ShieldStackState shieldState(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
    }

    private static void emptyBuffer(ItemStack stack) {
        var state = shieldState(stack);
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), state.withCellsAndBuffer(state.cells(), 0, state.moves(), state.gatherTime()));
    }

    private static boolean defaultState(ItemStack stack, RelicRole role) {
        return role.isShield() ? shieldState(stack).equals(ShieldStackState.DEFAULT)
                : role.isHive() ? stack.getOrDefault(ModDataComponents.HIVE_STACK_STATE.get(),
                dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT).equals(dev.hurtify.relicsaddon.drone.HiveStackState.DEFAULT)
                : false;
    }

    private static double experience(ServerPlayer player, ItemStack stack) {
        AutonomousRelicItem item = (AutonomousRelicItem) stack.getItem();
        return item.getRelicData(player, stack).getLevelingData()
                .getSourceExperience(item.role().abilityId(), item.role().abilityId() + "_activity");
    }

    private static void close(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) < EPSILON,
                message + ": expected " + expected + ", got " + actual);
    }

    /** Test-scoped cleanup also runs on assertion failure, timeout, and rerun. */
    private static final class PlayerCleanup implements GameTestListener {
        private final ServerPlayer player;
        private final EmbeddedChannel channel;
        private boolean closed;

        private PlayerCleanup(ServerPlayer player, EmbeddedChannel channel) {
            this.player = player;
            this.channel = channel;
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                player.getAdvancements().stopListening();
                player.getTextFilter().leave();
                player.discard();
            } finally {
                channel.finishAndReleaseAll();
            }
        }

        @Override
        public void testStructureLoaded(GameTestInfo test) {
        }

        @Override
        public void testPassed(GameTestInfo test, GameTestRunner runner) {
            close();
        }

        @Override
        public void testFailed(GameTestInfo test, GameTestRunner runner) {
            close();
        }

        @Override
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
            close();
        }
    }

    private AutonomousRelicGameTests() {
    }
}
