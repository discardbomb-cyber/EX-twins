package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.registry.ShipBlocks;
import dev.hurtify.relicsaddon.shipshield.ShellMesh;
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

    private ShipShieldGameTests() {
    }
}
