package dev.hurtify.relicsaddon.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.RelicRuntime;
import dev.hurtify.relicsaddon.relic.ShieldUpgrades;
import dev.hurtify.relicsaddon.server.ShieldController;
import dev.hurtify.relicsaddon.server.ShieldProjectileInterceptor;
import dev.hurtify.relicsaddon.shield.ShieldCellDefense;
import dev.hurtify.relicsaddon.shield.ShieldCellMove;
import dev.hurtify.relicsaddon.shield.ShieldField;
import dev.hurtify.relicsaddon.shield.ShieldImpact;
import dev.hurtify.relicsaddon.shield.ShieldStackState;
import dev.hurtify.relicsaddon.shield.ShieldTopology;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShieldCellGameTests {
    private static final int FRONT = ShieldTopology.INSTANCE.nearest(0, 0, 1);

    @GameTest(template = "test_room")
    public static void itemBackedRelicsUseCharm(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var entry : ModItems.ITEMS.getEntries()) {
            ItemStack stack = new ItemStack(entry.get());
            helper.assertTrue(((AutonomousRelicItem) stack.getItem()).role().slot().equals("charm"), "Shared charm role");
            helper.assertTrue(CuriosApi.isStackValid(new SlotContext("charm", player, 0, false, true), stack),
                    "Every registered relic is valid in its charm slot");
            for (String old : new String[]{"necklace", "back"}) {
                helper.assertFalse(stack.is(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("curios", old))), "No obsolete slot tag");
                helper.assertFalse(CuriosApi.isStackValid(new SlotContext(old, player, 0, false, true), stack), "Old slot rejected");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void topologyHas420ClosedCellsAndCachedExactNeighbors(GameTestHelper helper) {
        var topology = ShieldTopology.INSTANCE;
        helper.assertTrue(topology.cells().length == ShieldTopology.CELL_COUNT, "Exactly 420 authoritative shield cells");
        boolean valid = true;
        for (var cell : topology.cells()) {
            int[] neighbors = topology.neighbors(cell.id());
            valid &= neighbors.length >= 3 && neighbors.length <= 8 && cell.perimeter().length >= 9;
            for (int neighbor : neighbors) valid &= topology.adjacent(cell.id(), neighbor) && java.util.Arrays.stream(topology.neighbors(neighbor)).anyMatch(id -> id == cell.id());
            for (int point = 0; point < cell.perimeter().length; point += 3) {
                float[] center = cell.center();
                double own = center[0] * cell.perimeter()[point] + center[1] * cell.perimeter()[point + 1] + center[2] * cell.perimeter()[point + 2];
                for (var other : topology.cells()) {
                    float[] otherCenter = other.center();
                    double otherDot = otherCenter[0] * cell.perimeter()[point] + otherCenter[1] * cell.perimeter()[point + 1] + otherCenter[2] * cell.perimeter()[point + 2];
                    valid &= otherDot <= own + 0.00002D;
                }
            }
        }
        helper.assertTrue(valid, "Voronoi vertices close the sphere and adjacency has no distance threshold");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void everyCellHasIndependentHpAndExactRepair(GameTestHelper helper) {
        for (int id = 0; id < ShieldTopology.CELL_COUNT; id++) {
            var state = ShieldStackState.DEFAULT.damageLocalCell(id, 12, 12, 100);
            helper.assertTrue(state.cellHp(id) == 0 && state.livingCells() == ShieldTopology.CELL_COUNT - 1 && state.totalIntegrity() == 5532, "Exactly one cell breaks");
            for (int other = 0; other < ShieldTopology.CELL_COUNT; other++) if (other != id) helper.assertTrue(state.cellHp(other) == 12, "Other cell untouched");
            var repaired = state.repairFirstDamagedPanel(160);
            helper.assertTrue(repaired.cellHp(id) == 1 && repaired.totalIntegrity() == 5533 && repaired.lastActiveGameTime() == 100, "One HP repair without hit replay");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void commonBufferPaysBeforeLocalCellsAndRepairsAfterQuietPeriod(GameTestHelper helper) {
        var buffered = ShieldCellDefense.damage(ShieldStackState.DEFAULT, FRONT, 8, 0).apply(ShieldStackState.DEFAULT, FRONT, 8, 100);
        helper.assertTrue(buffered.sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 8 && buffered.cellHp(FRONT) == 12,
                "Common buffer absorbs intact-cell damage before local HP");
        var bufferedHole = ShieldStackState.DEFAULT.damageLocalCell(FRONT, 12, 0, 0);
        bufferedHole = ShieldCellDefense.damage(bufferedHole, FRONT, 4, 0).apply(bufferedHole, FRONT, 4, 100);
        helper.assertTrue(bufferedHole.sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 4 && bufferedHole.cellHp(FRONT) == 0,
                "Common buffer can defend an existing local hole");
        var nearlyEmpty = ShieldStackState.DEFAULT.withCellsAndBuffer(ShieldStackState.DEFAULT.cells(), 3, List.of(), -1);
        var overflow = ShieldCellDefense.damage(nearlyEmpty, FRONT, 8, 0).apply(nearlyEmpty, FRONT, 8, 100);
        helper.assertTrue(overflow.sharedBuffer() == 0 && overflow.cellHp(FRONT) == 7,
                "Only damage beyond the common buffer reaches the hit cell");
        helper.assertTrue(overflow.repairFirstDamagedPanel(139).equals(overflow), "Buffer repair waits for quiet period");
        var localRepair = overflow.repairFirstDamagedPanel(140);
        helper.assertTrue(localRepair.cellHp(FRONT) == 8 && localRepair.sharedBuffer() == 0,
                "Repair restores visible local damage before refilling the common buffer");
        var intactButEmptyBuffer = ShieldStackState.DEFAULT.withCellsAndBuffer(ShieldStackState.DEFAULT.cells(), 0, List.of(), -1);
        helper.assertTrue(intactButEmptyBuffer.repairFirstDamagedPanel(140).sharedBuffer() == 1,
                "Common buffer refills only after every local cell is intact");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void distributionUsesOnlyTwoLiveAdjacentCells(GameTestHelper helper) {
        var state = localOnlyState();
        var result = ShieldCellDefense.damage(state, FRONT, 10, .4);
        helper.assertTrue(result.spent() == 10 && result.health().get(FRONT) == 6, "Six primary plus four shared");
        int changed = 0;
        for (int id = 0; id < ShieldTopology.CELL_COUNT; id++) if (id != FRONT && result.health().get(id) < 12) {
            helper.assertTrue(ShieldTopology.INSTANCE.adjacent(FRONT, id), "No remote donation");
            helper.assertTrue(result.health().get(id) == 10, "Two HP per closest neighbor");
            changed++;
        }
        helper.assertTrue(changed == 2, "Exactly two neighbors");
        helper.assertTrue(ShieldCellDefense.damage(state.damageLocalCell(FRONT, 12, 0, 0), FRONT, 10, .5).spent() == 0, "Distribution alone cannot close a hole");
        var current = state;
        for (int hit = 0; hit < 500; hit++) {
            int id = hit * 17 % ShieldTopology.CELL_COUNT, before = current.totalIntegrity();
            var damage = ShieldCellDefense.damage(current, id, 1 + hit % 19, .5);
            current = damage.apply(current, id, damage.spent(), hit);
            helper.assertTrue(before - current.totalIntegrity() == damage.spent(), "HP conservation under repeated hits");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void gatheringMovesHpLeavesHolesAndHasCooldown(GameTestHelper helper) {
        var state = ShieldStackState.DEFAULT.damageLocalCell(FRONT, 12, 0, 10);
        for (int id : ShieldTopology.INSTANCE.neighborsOf(FRONT)) state = state.damageLocalCell(id, 12, 0, 10);
        var gathered = ShieldCellDefense.gather(state, FRONT, 3, 100);
        helper.assertTrue(gathered.moves().size() == 3 && gathered.cellHp(FRONT) == 12, "Three surviving cells move to threatened patch");
        helper.assertTrue(gathered.totalIntegrity() == state.totalIntegrity() && gathered.livingCells() == state.livingCells(), "No healing and no duplication");
        for (var move : gathered.moves()) helper.assertTrue(gathered.cellHp(move.from()) == 0 && gathered.cellHp(move.to()) == state.cellHp(move.from()), "Donor leaves a real hole");
        var damagedAgain = gathered.damageCell(FRONT, 12, 0, 101);
        helper.assertTrue(ShieldCellDefense.gather(damagedAgain, FRONT, 3, 139).equals(damagedAgain), "40 tick cooldown");
        helper.assertTrue(ShieldCellDefense.gather(damagedAgain, FRONT, 3, 140).cellHp(FRONT) > 0, "Exact cooldown boundary");
        var empty = state.withCells(java.util.Collections.nCopies(ShieldTopology.CELL_COUNT, 0), List.of(), -1);
        helper.assertTrue(ShieldCellDefense.gather(empty, FRONT, 3, 100).equals(empty), "No donor means no shield");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void cellsAndRelocationPersistAndLegacyMigrates(GameTestHelper helper) {
        var old = JsonParser.parseString("{\"enabled\":false,\"front\":0,\"left\":3,\"right\":9,\"back\":12,\"lastHitPanel\":0,\"lastAbsorbed\":2,\"lastActiveGameTime\":50}");
        var migrated = ShieldStackState.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        int[] hp = {0, 3, 9, 12};
        for (var cell : ShieldTopology.INSTANCE.cells()) helper.assertTrue(migrated.cellHp(cell.id()) == hp[cell.panel()], "Legacy damage preserved across that sector");
        helper.assertTrue(migrated.sharedBuffer() == 0, "Sector-only saves do not gain a common buffer");
        JsonObject legacyCells = JsonParser.parseString("{\"enabled\":true,\"front\":12,\"left\":12,\"right\":12,\"back\":12,\"lastHitPanel\":0,\"lastAbsorbed\":0,\"lastActiveGameTime\":0}").getAsJsonObject();
        JsonArray oldCells = new JsonArray();
        for (int id = 0; id < ShieldTopology.LEGACY_CELL_COUNT; id++) oldCells.add(id == 7 ? 0 : 12);
        legacyCells.add("cells", oldCells);
        JsonArray oldMoves = new JsonArray();
        oldMoves.add(JsonParser.parseString("{\"from\":7,\"to\":8}"));
        legacyCells.add("moves", oldMoves);
        var remapped = ShieldStackState.CODEC.parse(JsonOps.INSTANCE, legacyCells).getOrThrow();
        int brokenLegacyRegion = ShieldTopology.INSTANCE.migrateLegacyCell(7);
        helper.assertTrue(remapped.cellHp(brokenLegacyRegion) == 0 && remapped.moves().getFirst().from() == brokenLegacyRegion,
                "42-cell damage and relocation retain their regions after migration");
        var highIndex = ShieldStackState.DEFAULT.withCellsAndBuffer(ShieldStackState.DEFAULT.cells(), 0,
                List.of(new ShieldCellMove(128, ShieldTopology.CELL_COUNT - 1)), 10);
        var highJson = ShieldStackState.CODEC.encodeStart(JsonOps.INSTANCE, highIndex).getOrThrow();
        helper.assertTrue(ShieldStackState.CODEC.parse(JsonOps.INSTANCE, highJson).getOrThrow().moves().getFirst().to() == ShieldTopology.CELL_COUNT - 1,
                "State codec preserves relocation indices above 63");
        var impact = new ShieldImpact(new Vec3(0, 0, 1), 5, 0, 2, true, List.of(128));
        var impactJson = ShieldImpact.CODEC.encodeStart(JsonOps.INSTANCE, impact).getOrThrow();
        helper.assertTrue(ShieldImpact.CODEC.parse(JsonOps.INSTANCE, impactJson).getOrThrow().brokenCells().equals(List.of(128)),
                "Impact codec preserves broken cell indices above 63");
        var state = ShieldCellDefense.gather(migrated.withEnabled(true, 60), FRONT, 2, 100);
        var buffer = Unpooled.buffer();
        try {
            ShieldStackState.STREAM_CODEC.encode(buffer, state);
            helper.assertTrue(state.equals(ShieldStackState.STREAM_CODEC.decode(buffer)), "Cell HP and move network roundtrip");
        } finally { buffer.release(); }
        var json = ShieldStackState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        helper.assertTrue(state.equals(ShieldStackState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()), "Persistent cell HP and moves");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void nativeUpgradeGatesAndTwinsExclusion(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = new ItemStack(item);
            RelicRuntime.ability(player, stack).getResearchData().complete();
            helper.assertTrue(ShieldUpgrades.sharing(player, stack) == 0 && ShieldUpgrades.gathering(player, stack) == 0, "Fresh shield upgrades locked");
            item.getRelicData(player, stack).getLevelingData().setLevel(4);
            helper.assertTrue(ShieldUpgrades.sharing(player, stack) == 0, "Level alone does not bypass Relics unlock");
            unlock(player, stack);
            helper.assertTrue(ShieldUpgrades.sharing(player, stack) > 0, "Distribution unlocks natively");
            helper.assertTrue(ShieldUpgrades.gathering(player, stack) == (item.role() == RelicRole.TWINS_SHIELD ? 0 : 1), "Gather only RF/Mana");
            RelicRuntime.setEnabled(player, stack, false);
            helper.assertTrue(ShieldUpgrades.sharing(player, stack) == 0 && ShieldUpgrades.gathering(player, stack) == 0, "No passive shield operation while disabled");
        }
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void holePassesArrowButAdjacentCellStillStopsIt(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        var stack = equip(player, ModItems.RF_SHIELD.get());
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), localOnlyState().damageLocalCell(FRONT, 12, 0, 0));
        var arrow = arrow(helper, player, FRONT, 2);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow, player), "Broken cell is a physical hole");
        var event = new LivingIncomingDamageEvent(player, new DamageContainer(player.damageSources().arrow(arrow, null), 4));
        ShieldController.onIncomingDamage(event);
        helper.assertTrue(event.getAmount() == 4, "No fallback shield catches the arrow after it passed a hole");
        int neighbor = java.util.Arrays.stream(ShieldTopology.INSTANCE.nearestTo(FRONT))
                .filter(id -> ShieldTopology.INSTANCE.cells()[id].center()[1] >= 0).findFirst().orElseThrow();
        var next = arrow(helper, player, neighbor, 2);
        helper.assertTrue(ShieldProjectileInterceptor.intercept(next, player) && next.isRemoved(), "Adjacent live cell remains protective");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void nearerShieldStopsShotBeforeFartherShieldStartsGathering(GameTestHelper helper) {
        var far = fieldPlayer(helper);
        var near = fieldPlayer(helper);
        near.setPos(near.position().add(0, 0, 1));
        var farShield = equip(far, ModItems.RF_SHIELD.get());
        ModItems.RF_SHIELD.get().getRelicData(far, farShield).getLevelingData().setLevel(4);
        unlock(far, farShield);
        var before = localOnlyState().damageLocalCell(FRONT, 12, 0, 0);
        farShield.set(ModDataComponents.SHIELD_STACK_STATE.get(), before);
        equip(near, ModItems.MANA_SHIELD.get());
        helper.getLevel().addNewPlayer(far);
        helper.getLevel().addNewPlayer(near);
        var shot = arrow(helper, far, FRONT, 2);
        shot.setPos(far.position().add(0, ShieldField.CENTER_Y, 5));
        shot.setDeltaMovement(0, 0, -4);
        ShieldProjectileInterceptor.onEntityTick(new net.neoforged.neoforge.event.tick.EntityTickEvent.Pre(shot));
        helper.assertTrue(shot.isRemoved(), "Nearer shield stops shot");
        helper.assertTrue(before.equals(farShield.get(ModDataComponents.SHIELD_STACK_STATE.get())), "Farther shield cannot gather for an already stopped shot");
        helper.succeed();
    }

    @GameTest(template = "field_arena")
    public static void rfGatheredCellWaitsForArrival(GameTestHelper helper) {
        gatherArrival(helper, ModItems.RF_SHIELD.get());
    }

    @GameTest(template = "field_arena")
    public static void manaGatheredCellWaitsForArrival(GameTestHelper helper) {
        gatherArrival(helper, ModItems.MANA_SHIELD.get());
    }

    private static void gatherArrival(GameTestHelper helper, AutonomousRelicItem item) {
        var player = fieldPlayer(helper);
        var stack = equip(player, item);
        item.getRelicData(player, stack).getLevelingData().setLevel(4);
        unlock(player, stack);
        var before = localOnlyState().damageLocalCell(FRONT, 12, 0, 0);
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), before);
        var arrow = arrow(helper, player, FRONT, 2);
        helper.assertFalse(ShieldProjectileInterceptor.intercept(arrow, player), "Cell in transit cannot stop first arrow through hole");
        var after = stack.get(ModDataComponents.SHIELD_STACK_STATE.get());
        helper.assertTrue(after.moves().size() == 1 && before.totalIntegrity() == after.totalIntegrity(), "Move persists even when no damage is absorbed");
        RelicRuntime.setEnabled(player, stack, false);
        RelicRuntime.setEnabled(player, stack, true);
        var json = ShieldStackState.CODEC.encodeStart(JsonOps.INSTANCE, stack.get(ModDataComponents.SHIELD_STACK_STATE.get())).getOrThrow();
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        helper.runAfterDelay(9, () -> {
            var incoming = arrow(helper, player, FRONT, 2);
            helper.assertFalse(ShieldProjectileInterceptor.intercept(incoming, player), "Hole remains open at tick nine after toggle and reload");
            var event = new LivingIncomingDamageEvent(player, new DamageContainer(explosionSource(helper, player), 8));
            ShieldController.onIncomingDamage(event);
            helper.assertTrue(event.getAmount() == 8, "Fallback explosion cannot use a moving cell or distribute through a hole");
        });
        helper.runAfterDelay(10, () -> {
            var incoming = arrow(helper, player, FRONT, 2);
            int cost = ShieldProjectileInterceptor.impactCost(incoming);
            helper.assertTrue(ShieldProjectileInterceptor.intercept(incoming, player) && incoming.isRemoved(), "Cell blocks exactly after ten ticks");
            var arrived = stack.get(ModDataComponents.SHIELD_STACK_STATE.get());
            helper.assertTrue(before.totalIntegrity() - arrived.totalIntegrity() == cost,
                    "Arrival creates no HP; only the actual projectile cost is consumed");
            helper.assertTrue(arrived.cellHp(after.moves().getFirst().from()) == 0, "Donor position remains a hole");
            var oldHit = new LivingIncomingDamageEvent(player, new DamageContainer(player.damageSources().arrow(arrow, null), 4));
            ShieldController.onIncomingDamage(oldHit);
            helper.assertTrue(oldHit.getAmount() == 4, "Arrival does not retroactively catch a projectile that entered the hole");
            helper.succeed();
        });
    }

    @GameTest(template = "test_room")
    public static void explosionConsumesOnlySourceSideAndNoRearHp(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            for (Vec3 direction : List.of(new Vec3(0, 0, 1), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, -1))) {
                var stack = equip(player, item);
                Vec3 origin = player.position().add(0, ShieldField.CENTER_Y, 0).add(direction.scale(4));
                var source = new DamageSource(helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.EXPLOSION), origin);
                helper.assertTrue(source.is(DamageTypeTags.IS_EXPLOSION), "Actual explosion damage tag");
                var event = new LivingIncomingDamageEvent(player, new DamageContainer(source, 8));
                ShieldController.onIncomingDamage(event);
                int cell = ShieldController.selectCell(player, direction);
                var state = stack.get(ModDataComponents.SHIELD_STACK_STATE.get());
                int spent = 8;
                helper.assertTrue(state.sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - spent && state.totalIntegrity() == ShieldStackState.MAX_TOTAL_INTEGRITY - spent, "Blast pays common buffer before local cells");
                helper.assertTrue(event.getAmount() == 0, "Intact cell completely absorbs this blast");
                for (int other = 0; other < ShieldTopology.CELL_COUNT; other++) helper.assertTrue(state.cellHp(other) == 12, "Shared buffer leaves all local cells intact");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void explosionThroughHoleAndDistributionConserveHp(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        var item = ModItems.TWINS_SHIELD.get();
        var stack = equip(player, item);
        var source = new DamageSource(helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.EXPLOSION),
                player.position().add(0, ShieldField.CENTER_Y, 4));
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), localOnlyState().damageLocalCell(FRONT, 12, 0, 0));
        var event = new LivingIncomingDamageEvent(player, new DamageContainer(source, 8));
        ShieldController.onIncomingDamage(event);
        helper.assertTrue(event.getAmount() == 8, "Hole does not absorb explosion");
        stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), ShieldStackState.DEFAULT);
        item.getRelicData(player, stack).getLevelingData().setLevel(4);
        unlock(player, stack);
        event = new LivingIncomingDamageEvent(player, new DamageContainer(source, 8));
        ShieldController.onIncomingDamage(event);
        var after = stack.get(ModDataComponents.SHIELD_STACK_STATE.get());
        helper.assertTrue(after.totalIntegrity() == ShieldStackState.MAX_TOTAL_INTEGRITY - 8 && after.sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - 8 && event.getAmount() == 0, "Distribution pays common HP without duplicating cost");
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void breakingHitOverflowsAndLaterHitsUseOnlyThatHole(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = equip(player, item);
            stack.set(ModDataComponents.SHIELD_STACK_STATE.get(), localOnlyState());
            var source = explosionSource(helper, player);
            for (float[] hit : new float[][]{{8, 0, 4}, {7, 3, 0}, {5, 5, 0}}) {
                player.setHealth(20);
                player.invulnerableTime = 0;
                player.hurt(source, hit[0]);
                helper.assertTrue(Math.abs(player.getHealth() - (20 - hit[1])) < .0001, "Real damage: intact block, overflow, then open hole");
                helper.assertTrue(stack.get(ModDataComponents.SHIELD_STACK_STATE.get()).cellHp(FRONT) == (int) hit[2], "Facing cell spends exact HP");
            }
            var side = new DamageSource(helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.MOB_ATTACK),
                    player.position().add(4, ShieldField.CENTER_Y, 0));
            player.setHealth(20);
            player.invulnerableTime = 0;
            player.hurt(side, 4);
            helper.assertTrue(player.getHealth() == 20, "Broken front does not disable intact side against melee");
        }
        helper.succeed();
    }

    @GameTest(template = "test_room")
    public static void transitCellsCannotDonateHpToDistribution(GameTestHelper helper) {
        var state = ShieldCellDefense.gather(localOnlyState().damageLocalCell(FRONT, 12, 0, 90), FRONT, 1, 100);
        int neighbor = java.util.Arrays.stream(ShieldTopology.INSTANCE.nearestTo(FRONT))
                .filter(id -> ShieldTopology.INSTANCE.adjacent(FRONT, id) && state.cellHp(id) > 0).findFirst().orElseThrow();
        var damage = ShieldCellDefense.damage(state, neighbor, 30, .5, 109);
        helper.assertTrue(damage.health().get(FRONT) == 12, "Moving cell cannot absorb a neighbor's shared damage");
        helper.assertTrue(ShieldCellDefense.damage(state, FRONT, 4, .5, 109).spent() == 0, "Transit destination has no protection");
        helper.assertTrue(ShieldCellDefense.damage(state, FRONT, 4, .5, 110).spent() == 4, "Exact arrival tick activates protection");
        helper.succeed();
    }

    private static DamageSource explosionSource(GameTestHelper helper, ServerPlayer player) {
        return new DamageSource(helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.EXPLOSION),
                player.position().add(0, ShieldField.CENTER_Y, 4));
    }

    private static ServerPlayer fieldPlayer(GameTestHelper helper) {
        var player = AutonomousRelicGameTests.survivalPlayer(helper);
        player.setPos(helper.absoluteVec(new Vec3(6.5, 2, 6.5)));
        return player;
    }

    @GameTest(template = "field_arena")
    public static void actualWorldExplosionTriggersRegisteredDamageProtection(GameTestHelper helper) {
        var player = fieldPlayer(helper);
        helper.getLevel().addNewPlayer(player);
        for (var item : List.of(ModItems.RF_SHIELD.get(), ModItems.MANA_SHIELD.get(), ModItems.TWINS_SHIELD.get())) {
            var stack = equip(player, item);
            player.setHealth(20);
            player.invulnerableTime = 0;
            player.setDeltaMovement(Vec3.ZERO);
            Vec3 origin = player.position().add(0, ShieldField.CENTER_Y, 1.5);
            helper.getLevel().explode(null, origin.x, origin.y, origin.z, 1.5F, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
            var state = stack.get(ModDataComponents.SHIELD_STACK_STATE.get());
            helper.assertTrue(state != null && state.lastAbsorbed() > 0 && player.getHealth() > 0, "Real world explosion invokes registered protection");
            int cost = (int) Math.ceil(state.lastAbsorbed());
            helper.assertTrue(ShieldStackState.MAX_TOTAL_INTEGRITY - state.totalIntegrity() == cost && state.sharedBuffer() == ShieldStackState.MAX_SHARED_BUFFER - cost, "Real blast charges the common buffer first");
            helper.assertTrue(state.back() == 12 && state.left() == 12 && state.right() == 12, "Other directions survive actual explosion");
        }
        helper.succeed();
    }

    private static void unlock(ServerPlayer player, ItemStack stack) {
        var item = (AutonomousRelicItem) stack.getItem();
        var abilities = item.getRelicData(player, stack).getAbilitiesData();
        for (String id : List.of(ShieldUpgrades.DISTRIBUTION, ShieldUpgrades.GATHER)) {
            if (!abilities.getAbilityIDs().contains(id)) continue;
            var lock = abilities.getAbilityData(id).getLockData();
            lock.setUnlocks(lock.getMaxUnlocks());
            abilities.getAbilityData(id).getResearchData().complete();
        }
    }

    private static ItemStack equip(ServerPlayer player, AutonomousRelicItem item) {
        var stack = new ItemStack(item);
        RelicRuntime.ability(player, stack).getResearchData().complete();
        CuriosApi.getCuriosInventory(player).orElseThrow().setEquippedCurio("charm", 0, stack);
        return stack;
    }

    private static Arrow arrow(GameTestHelper helper, ServerPlayer player, int cell, double baseDamage) {
        float[] normal = ShieldTopology.INSTANCE.cells()[cell].center();
        Vec3 direction = new Vec3(-normal[0], normal[1], normal[2]).normalize();
        var arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setPos(player.position().add(0, ShieldField.CENTER_Y, 0).add(direction.scale(3)));
        arrow.setDeltaMovement(direction.scale(-2));
        arrow.setBaseDamage(baseDamage);
        arrow.setNoGravity(true);
        return arrow;
    }

    private static ShieldStackState localOnlyState() {
        return ShieldStackState.DEFAULT.withCellsAndBuffer(ShieldStackState.DEFAULT.cells(), 0, List.of(), -1);
    }
}
