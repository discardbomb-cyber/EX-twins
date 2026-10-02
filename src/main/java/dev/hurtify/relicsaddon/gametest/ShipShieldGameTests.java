package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;
import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.shipshield.ShieldLayers;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.registry.ShipBlocks;
import dev.hurtify.relicsaddon.shipshield.EmitterDrone;
import dev.hurtify.relicsaddon.shipshield.ShellMesh;
import dev.hurtify.relicsaddon.shipshield.ShellPatches;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity;
import dev.hurtify.relicsaddon.shipshield.ShipFamily;
import dev.hurtify.relicsaddon.shipshield.ShipShield;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The shield a generator raises round a static build (no Aeronautics here): the shell traced off
 * the server thread and nowhere nearer the blocks than the offset, drones seated on it, blows
 * held, spread, stripped and overloaded, drones home and back, and the dock's repairs.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShipShieldGameTests {
    /** Clears the room but its floor and lays blocks in a row at y=2 (the floor is natural stone and does not count). */
    static void row(GameTestHelper helper, net.minecraft.world.level.block.Block... blocks) {
        for (int x = 0; x < 5; x++) for (int y = 1; y < 4; y++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        for (int x = 0; x < blocks.length; x++) helper.setBlock(new BlockPos(x, 2, 2), blocks[x]);
    }

    /**
     * The arena (13x7x13) emptied but its floor, with a row of blocks at y=2 from x=4: room above
     * for the shell and for what flies at it (the small room's barrier ceiling sits right on the shell).
     */
    static ShipDeviceBlockEntity arenaRow(GameTestHelper helper, ShipFamily family, int drones, net.minecraft.world.level.block.Block filler) {
        for (int x = 0; x < 13; x++) for (int y = 1; y < 7; y++) for (int z = 0; z < 13; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        net.minecraft.world.level.block.Block[] blocks = {ShipBlocks.GENERATORS.get(family).get(), ShipBlocks.DOCKS.get(family).get(), filler, filler, filler};
        for (int x = 0; x < blocks.length; x++) helper.setBlock(new BlockPos(4 + x, 2, 6), blocks[x]);
        ShipDeviceBlockEntity generator = device(helper, new BlockPos(4, 2, 6)), dock = device(helper, new BlockPos(5, 2, 6));
        if (drones > 0) dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(family).get(), drones));
        helper.assertTrue(generator.setEnabled(null, true), "The generator comes on");
        return generator;
    }

    static ShipDeviceBlockEntity device(GameTestHelper helper, BlockPos relative) {
        helper.assertTrue(helper.getBlockEntity(relative) instanceof ShipDeviceBlockEntity, "A ship device stands at " + relative);
        return (ShipDeviceBlockEntity) helper.getBlockEntity(relative);
    }

    /** A generator at x=0 with a dock holding {@code drones} drones beside it, switched on. */
    static ShipDeviceBlockEntity shieldedRow(GameTestHelper helper, ShipFamily family, int drones) {
        row(helper, ShipBlocks.GENERATORS.get(family).get(), ShipBlocks.DOCKS.get(family).get(), Blocks.IRON_BLOCK, Blocks.IRON_BLOCK, Blocks.IRON_BLOCK);
        ShipDeviceBlockEntity generator = device(helper, new BlockPos(0, 2, 2)), dock = device(helper, new BlockPos(1, 2, 2));
        if (drones > 0) dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(family).get(), drones));
        helper.assertTrue(generator.setEnabled(null, true), "The generator comes on");
        return generator;
    }

    /** Runs {@code then} once the shield's shell is traced (polled every few ticks, up to the test's timeout). */
    static void whenTraced(GameTestHelper helper, ShipDeviceBlockEntity generator, Consumer<ShipShield> then) {
        ShipShield shield = generator.shield();
        helper.assertTrue(shield != null, "A generator has a shield");
        when(helper, () -> shield.outer() != null, () -> then.accept(shield));
    }

    /**
     * Runs {@code then} once {@code ready} holds, looking every other tick. Each look is a fresh
     * Runnable: the helper keeps its tasks by identity, so a task that re-adds itself is dropped.
     */
    static void when(GameTestHelper helper, java.util.function.BooleanSupplier ready, Runnable then) {
        helper.runAfterDelay(2, () -> {
            if (ready.getAsBoolean()) then.run();
            else when(helper, ready, then);
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void shellIsTracedOffThreadAndKeepsItsOffsetFromEveryBlock(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = shieldedRow(helper, ShipFamily.RF, 8);
        helper.assertTrue(generator.shield().outer() == null, "The shell is not traced on the spot");
        whenTraced(helper, generator, shield -> {
            helper.assertTrue(generator.structure() != null && generator.structure().size() == 5, "The row is the structure, not the floor: " + generator.structure().size());
            double offset = shield.offset();
            for (int layer = 0; layer < shield.layers().size(); layer++) {
                ShellMesh mesh = shield.layers().get(layer);
                double want = offset + layer;
                helper.assertTrue(!mesh.isEmpty(), "Layer " + layer + " has a shell");
                for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                    Vec3 at = mesh.vertex(vertex);
                    double nearest = Double.MAX_VALUE;
                    for (int x = 0; x < 5; x++) {
                        BlockPos block = helper.absolutePos(new BlockPos(x, 2, 2));
                        double dx = Math.max(0, Math.max(block.getX() - at.x, at.x - block.getX() - 1));
                        double dy = Math.max(0, Math.max(block.getY() - at.y, at.y - block.getY() - 1));
                        double dz = Math.max(0, Math.max(block.getZ() - at.z, at.z - block.getZ() - 1));
                        nearest = Math.min(nearest, Math.sqrt(dx * dx + dy * dy + dz * dz));
                    }
                    helper.assertTrue(nearest >= want - 1e-3, "Layer " + layer + " vertex " + at + " is " + nearest + " from the blocks, nearer than " + want);
                    helper.assertTrue(nearest <= want + .6, "Layer " + layer + " vertex " + at + " is " + nearest + " from the blocks, far beyond " + want);
                }
            }
            helper.assertTrue(shield.seatCount() == 8, "Five blocks seat eight drones, got " + shield.seatCount());
            helper.succeed();
        });
    }

    /** A generator on the ground shields the build on it, not the land under it. */
    @GameTest(template = TEMPLATE)
    public static void naturalTerrainIsNotPartOfTheBuild(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int y = 1; y < 4; y++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        // A hill of stone and dirt with a tree, and a cobblestone hut on it with the generator.
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 1, z), (x + z) % 2 == 0 ? Blocks.STONE : Blocks.GRASS_BLOCK);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(4, 2, 0), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(0, 2, 0), ShipBlocks.GENERATORS.get(ShipFamily.MANA).get());
        helper.setBlock(new BlockPos(1, 2, 0), Blocks.COBBLESTONE);
        helper.setBlock(new BlockPos(2, 2, 0), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(2, 3, 0), Blocks.CHEST);
        ShipDeviceBlockEntity generator = device(helper, new BlockPos(0, 2, 0));
        generator.scan(helper.getLevel(), helper.getLevel().getGameTime());
        helper.assertTrue(generator.structure() != null && generator.structure().size() == 4 && !generator.structure().truncated(),
                "The hut is four blocks (generator, cobblestone, planks, chest), got " + (generator.structure() == null ? "none" : generator.structure().size()));
        helper.assertTrue(generator.stores().size() == 1, "The chest on the hut is a store");
        helper.succeed();
    }

    /** Eight drones for 64 blocks (five blocks: eight seats), but never more than the docks hold; drones out cannot be taken by hoppers. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void dronesSeatOnTheShellNoMoreThanTheDocksHold(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = shieldedRow(helper, ShipFamily.RF, 3);
        ShipDeviceBlockEntity dock = device(helper, new BlockPos(1, 2, 2));
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 3, () -> {
            helper.assertTrue(shield.seatCount() == 8 && generator.state().dronesWanted() == 8, "Five blocks seat eight drones, got " + shield.seatCount());
            helper.assertTrue(shield.drones().size() == 3, "Only the three docked drones are out, got " + shield.drones().size());
            helper.assertTrue(dock.dronesOut() == 3 && dock.spareDrones() == 0, "The dock counts its three drones as out: " + dock.dronesOut() + "/" + dock.spareDrones());
            var handler = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, helper.absolutePos(new BlockPos(1, 2, 2)), null);
            helper.assertTrue(handler != null && handler.extractItem(0, 1, true).isEmpty(), "Hoppers cannot take drones that are out on the shell");
            for (EmitterDrone drone : shield.drones()) {
                helper.assertTrue(drone.holding(), "Drone " + drone.seat + " holds its seat");
                double distance = shield.outer().field().distance(drone.at());
                helper.assertTrue(Math.abs(distance - shield.outer().offset()) < .6, "Drone " + drone.seat + " sits on the shell, " + distance + " from the blocks");
            }
            ShellPatches patches = shield.patches();
            int cells = 0;
            for (int seat = 0; seat < 8; seat++) cells += patches.cells(seat);
            helper.assertTrue(cells == shield.outer().quadCount(), "The three drones share the whole shell between them: " + cells + " of " + shield.outer().quadCount());
            helper.assertTrue(patches.cells(3) == 0 && patches.cells(0) > 0, "Empty seats hold no cells");
            // Five more drones (the dock holds eight) take the empty seats.
            helper.assertTrue(dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(ShipFamily.RF).get(), 10)) == 5, "The dock takes five more of ten");
            when(helper, () -> shield.heldSeats() == 8, () -> {
                helper.assertTrue(dock.dronesOut() == 8 && generator.state().dronesOut() == 8, "All eight are out: " + dock.dronesOut() + ", console " + generator.state().dronesOut());
                helper.assertTrue(generator.state().notice().isEmpty(), "No notice once the seats are full: " + generator.state().notice());
                helper.succeed();
            });
        }));
    }

    /** A drone on a critical charge leaves its seat to its neighbours, flies home, charges and comes back on its own. */
    @GameTest(template = TEMPLATE, timeoutTicks = 800)
    public static void droneOnCriticalChargeFliesHomeAndComesBackCharged(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = shieldedRow(helper, ShipFamily.MANA, 8);
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 8, () -> {
            EmitterDrone drone = shield.drone(2);
            helper.assertTrue(drone != null && drone.holding(), "Seat 2 is held");
            int cellsBefore = shield.patches().cells(2);
            drone.setCharge(EmitterDrone.CRITICAL_CHARGE - .01F);
            when(helper, () -> drone.state() == EmitterDrone.State.CHARGING, () -> {
                helper.assertTrue(shield.heldSeats() == 7, "Seven seats are held while it charges");
                helper.assertTrue(shield.patches().cells(2) == 0 && cellsBefore > 0, "Its patch went to its neighbours");
                int total = 0;
                for (int seat = 0; seat < 8; seat++) total += shield.patches().cells(seat);
                helper.assertTrue(total == shield.outer().quadCount(), "The shell is still whole between seven drones");
                helper.assertTrue(drone.at().distanceTo(Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 2)))) < 1.5, "It is at the dock: " + drone.at());
                when(helper, () -> drone.holding(), () -> {
                    helper.assertTrue(drone.charge() >= EmitterDrone.READY_CHARGE, "It came back charged: " + drone.charge());
                    helper.assertTrue(shield.heldSeats() == 8 && shield.patches().cells(2) > 0, "It holds its seat again");
                    helper.succeed();
                });
            });
        }));
    }

    /** A blow smaller than the patch is held by it; a larger one spills to the neighbouring patches of the same layer. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void blowsAreHeldByThePatchAndSpillToItsNeighbours(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = shieldedRow(helper, ShipFamily.RF, 8);
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 8, () -> {
            long now = helper.getLevel().getGameTime();
            ShieldLayers integrity = shield.integrity();
            int max = integrity.max();
            helper.assertTrue(max == 40 && integrity.layers() == 1 && integrity.total() == 8 * 40, "A level 0 RF shield: one layer, 40 a patch, got " + max + " x " + integrity.layers());
            Vec3 seat = shield.seat(0);
            ShieldLayers.Strike small = shield.hit(seat, 10, now);
            helper.assertTrue(small.held() && small.absorbed() == 10 && integrity.integrity(0, 0) == 30, "Ten of forty held by the patch: " + small);
            helper.assertTrue(integrity.total() == 8 * 40 - 10, "Nothing else touched");
            int[] neighbours = shield.patches().neighbours(0);
            helper.assertTrue(neighbours.length >= 2, "The patch has neighbours: " + neighbours.length);
            ShieldLayers.Strike spill = shield.hit(seat, 50, now);
            helper.assertTrue(spill.held() && spill.absorbed() == 50 && integrity.integrity(0, 0) == 0, "Fifty: the patch's thirty and twenty from its neighbours: " + spill);
            int fromNeighbours = 0;
            for (int other : neighbours) fromNeighbours += 40 - integrity.integrity(0, other);
            helper.assertTrue(fromNeighbours == 20, "The first ring paid the twenty: " + fromNeighbours);
            // A blow on the hole goes through: one layer, nothing within.
            ShieldLayers.Strike hole = shield.hit(seat, 5, now);
            helper.assertTrue(hole.absorbed() == 0 && hole.passed() == 5 && !hole.overloaded(), "A drained patch is a hole: " + hole);
            helper.succeed();
        }));
    }

    /** A blow beyond a whole layer strips it round the seat and goes on inward; one beyond every layer overloads the shield. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void blowsStripLayersInwardAndOverloadTheShield(GameTestHelper helper) {
        row(helper, ShipBlocks.GENERATORS.get(ShipFamily.MANA).get(), ShipBlocks.DOCKS.get(ShipFamily.MANA).get(), Blocks.IRON_BLOCK, Blocks.IRON_BLOCK, Blocks.IRON_BLOCK);
        ShipDeviceBlockEntity generator = device(helper, new BlockPos(0, 2, 2)), dock = device(helper, new BlockPos(1, 2, 2));
        generator.device().set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, 8, 0, 0));
        dock.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(ShipFamily.MANA).get(), 8));
        helper.assertTrue(generator.setEnabled(null, true), "The generator comes on");
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 8, () -> {
            long now = helper.getLevel().getGameTime();
            ShieldLayers integrity = shield.integrity();
            helper.assertTrue(shield.layerCount() == 3 && integrity.layers() == 3 && integrity.max() == 60 + 48, "Level 8 Mana: three layers of 108, got " + integrity.layers() + " x " + integrity.max());
            int max = integrity.max();
            // Rings of two round the seat on this small shell reach every other seat: the whole outer layer, then 24 more.
            int outer = 8 * max;
            ShieldLayers.Strike strip = shield.hit(shield.seat(0), outer + 24, now);
            helper.assertTrue(strip.held() && strip.layersStripped() == 1, "The outer layer is stripped and the rest held: " + strip);
            for (int seat = 0; seat < 8; seat++) helper.assertTrue(integrity.integrity(2, seat) == 0, "Outer patch " + seat + " is gone");
            helper.assertTrue(integrity.integrity(1, 0) == max - 24, "The middle layer's patch took the rest: " + integrity.integrity(1, 0));
            helper.assertTrue(integrity.integrity(0, 0) == max, "The inner layer is whole");
            ShieldLayers.Strike overload = shield.hit(shield.seat(0), 100_000, now);
            helper.assertTrue(overload.overloaded() && overload.passed() > 0 && overload.absorbed() == 2 * outer - 24, "Everything left is taken and the rest passes: " + overload);
            helper.assertTrue(integrity.total() == 0 && shield.overloaded(now) && !shield.up(now), "The shield is down");
            helper.assertTrue(generator.shield().notice(now).equals(ShipShield.NOTICE_OVERLOADED), "It says so");
            when(helper, () -> shield.heldSeats() == 0, () -> {
                for (EmitterDrone drone : shield.drones()) helper.assertTrue(!drone.holding(), "Drone " + drone.seat + " left the shell");
                helper.succeed();
            });
        }));
    }

    /** An arrow from outside is stopped on the shell and paid for by the patch it met; a mob under the shell is pushed out. */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void arrowsAreStoppedOnTheShellAndMobsPushedOut(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = arenaRow(helper, ShipFamily.TWINS, 8, Blocks.IRON_BLOCK);
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 8, () -> {
            int before = shield.integrity().total();
            Vec3 from = helper.absoluteVec(new Vec3(6.5, 6.5, 6.5));
            var arrow = new net.minecraft.world.entity.projectile.Arrow(helper.getLevel(), from.x, from.y, from.z, new ItemStack(net.minecraft.world.item.Items.ARROW), null);
            arrow.setNoGravity(true);
            arrow.setDeltaMovement(0, -1.5, 0);
            arrow.setBaseDamage(2);
            helper.getLevel().addFreshEntity(arrow);
            var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
            Vec3 under = helper.absoluteVec(new Vec3(6.5, 3.2, 6.5));
            zombie.moveTo(under.x, under.y, under.z, 0, 0);
            zombie.setNoGravity(true);
            zombie.setNoAi(true);
            helper.getLevel().addFreshEntity(zombie);
            helper.assertTrue(shield.inside(zombie.getBoundingBox().getCenter()), "The zombie starts under the shell");
            helper.runAfterDelay(15, () -> {
                helper.assertTrue(arrow.isRemoved(), "The arrow was stopped");
                helper.assertTrue(shield.integrity().total() == before - 3, "Its three points were paid: " + (before - shield.integrity().total()));
                helper.assertTrue(!shield.inside(zombie.getBoundingBox().getCenter()), "The zombie was pushed out to " + zombie.position());
                helper.succeed();
            });
        }));
    }

    /** An explosion outside the shell spares the blocks under it while the shield holds its cost. */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void explosionsOutsideSpareTheBlocksUnderTheShell(GameTestHelper helper) {
        // Glass, which a TNT blast just outside the shell would shatter.
        ShipDeviceBlockEntity generator = arenaRow(helper, ShipFamily.RF, 8, Blocks.GLASS);
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 8, () -> {
            int before = shield.integrity().total();
            Vec3 at = helper.absoluteVec(new Vec3(7.5, 5.2, 6.5));
            helper.assertTrue(!shield.inside(at), "The blast is outside the shell");
            helper.getLevel().explode(null, at.x, at.y, at.z, 4, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
            for (int x = 6; x < 9; x++) helper.assertBlockPresent(Blocks.GLASS, new BlockPos(x, 2, 6));
            helper.assertTrue(shield.integrity().total() == before - 48, "A radius-4 blast costs 48: " + (before - shield.integrity().total()));
            helper.succeed();
        }));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 600, batch = "ship_single_drone")
    public static void loneDroneMustRechargeInsteadOfBeingReplaced(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = shieldedRow(helper, ShipFamily.MANA, 1);
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 1, () -> {
            EmitterDrone original = shield.drone(0);
            original.setCharge(.1F);
            when(helper, () -> original.state() == EmitterDrone.State.CHARGING, () -> {
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(shield.drones().size() == 1 && shield.drone(0) == original, "A charging drone must not be replaced by a fresh inventory claim");
                    helper.assertTrue(shield.heldSeats() == 0 && original.charge() < .5F, "The lone emitter spends time recharging");
                    when(helper, original::holding, () -> {
                        helper.assertTrue(original.ready(), "The same emitter returns fully serviced");
                        generator.setEnabled(null, false);
                        helper.succeed();
                    });
                });
            });
        }));
    }

    @GameTest(template = TEMPLATE)
    public static void restoredReturningDroneStillCompletesItsDockClaim(GameTestHelper helper) {
        EmitterDrone drone = new EmitterDrone(0, BlockPos.ZERO, new Vec3(5, 0, 0));
        drone.setCharge(.1F);
        drone.fly(Vec3.ZERO, true);
        var saved = new net.minecraft.nbt.CompoundTag();
        drone.save(saved);
        EmitterDrone restored = EmitterDrone.load(saved, new Vec3(5, 0, 0), Vec3.ZERO);
        helper.assertTrue(restored.state() == EmitterDrone.State.RETURNING && !restored.away(), "Restoring a return keeps its outstanding claim");
        int arrivals = 0;
        for (int tick = 0; tick < 10; tick++) if (restored.tickFlight()) arrivals++;
        helper.assertTrue(arrivals == 1 && restored.away() && restored.charge() == .1F, "Arrival releases exactly one claim without refilling charge");
        helper.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void passingExplosionDoesNotSpendShieldOrFilterItsTargets(GameTestHelper helper) {
        ShipDeviceBlockEntity generator = arenaRow(helper, ShipFamily.RF, 8, Blocks.GLASS);
        whenTraced(helper, generator, shield -> when(helper, () -> shield.heldSeats() == 8, () -> {
            var previous = dev.hurtify.relicsaddon.AddonConfig.SHIELD_PASSING_DAMAGE_TYPES.get();
            try {
                dev.hurtify.relicsaddon.AddonConfig.SHIELD_PASSING_DAMAGE_TYPES.set(java.util.List.of("#minecraft:is_explosion"));
                Vec3 at = helper.absoluteVec(new Vec3(7.5, 5.2, 6.5));
                var explosion = new net.minecraft.world.level.Explosion(helper.getLevel(), null, at.x, at.y, at.z, 4, false, net.minecraft.world.level.Explosion.BlockInteraction.DESTROY);
                BlockPos glass = helper.absolutePos(new BlockPos(7, 2, 6));
                explosion.getToBlow().add(glass);
                var entities = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
                int before = shield.integrity().total();
                dev.hurtify.relicsaddon.shipshield.ShipShieldFields.onExplosion(new net.neoforged.neoforge.event.level.ExplosionEvent.Detonate(helper.getLevel(), explosion, entities));
                helper.assertTrue(shield.integrity().total() == before && explosion.getToBlow().contains(glass), "A passing blast keeps both its damage and affected blocks");
            } finally {
                dev.hurtify.relicsaddon.AddonConfig.SHIELD_PASSING_DAMAGE_TYPES.set(previous);
                generator.setEnabled(null, false);
            }
            helper.succeed();
        }));
    }

    private ShipShieldGameTests() {
    }
}
