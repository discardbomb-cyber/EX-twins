package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.TEMPLATE;

import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.power.DevicePower;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.registry.ShipBlocks;
import dev.hurtify.relicsaddon.relic.DeviceProgression;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceBlockEntity;
import dev.hurtify.relicsaddon.shipshield.ShipDeviceItem;
import dev.hurtify.relicsaddon.shipshield.ShipFamily;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Ship shield blocks on a static build (no Aeronautics here): the one-generator rule, structure
 * links, Forge Energy, docks and the two recipe sets.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class ShipDeviceGameTests {
    /**
     * A floating row of blocks at y=2 in an emptied room (the template's walls and ceiling are cleared,
     * only its floor stays), so the structure scan finds the row and nothing else. The barriers the
     * GameTest framework wraps every structure in do not count as blocks of a build.
     */
    private static void row(GameTestHelper helper, net.minecraft.world.level.block.Block... blocks) {
        for (int x = 0; x < 5; x++) for (int y = 1; y < 4; y++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        for (int x = 0; x < blocks.length; x++) helper.setBlock(new BlockPos(x, 2, 2), blocks[x]);
    }

    private static ShipDeviceBlockEntity device(GameTestHelper helper, BlockPos relative) {
        helper.assertTrue(helper.getBlockEntity(relative) instanceof ShipDeviceBlockEntity, "A ship device stands at " + relative);
        return (ShipDeviceBlockEntity) helper.getBlockEntity(relative);
    }

    @GameTest(template = TEMPLATE)
    public static void secondGeneratorOnTheSameStructureStaysOff(GameTestHelper helper) {
        var rf = ShipBlocks.GENERATORS.get(ShipFamily.RF).get();
        var mana = ShipBlocks.GENERATORS.get(ShipFamily.MANA).get();
        // A waterlogged slab in the middle: still part of the build.
        row(helper, rf, Blocks.IRON_BLOCK, Blocks.IRON_BLOCK, Blocks.IRON_BLOCK, mana);
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true));
        ShipDeviceBlockEntity first = device(helper, new BlockPos(0, 2, 2)), second = device(helper, new BlockPos(4, 2, 2));
        helper.assertTrue(first.setEnabled(null, true) && first.enabled(), "The first generator comes on");
        helper.assertTrue(first.structure() != null && first.structure().size() == 5 && !first.structure().truncated(),
                "The row of five blocks is one structure, got " + (first.structure() == null ? "none" : first.structure().size()));
        helper.assertFalse(second.setEnabled(null, true), "A second generator on the same structure refuses to come on");
        helper.assertFalse(second.enabled(), "It stays off");
        helper.assertTrue(second.state().notice().equals(ShipDeviceBlockEntity.NOTICE_OTHER_GENERATOR)
                && second.state().detail().equals(helper.absolutePos(new BlockPos(0, 2, 2)).toShortString()), "It says which generator runs: " + second.state().detail());
        // A separate build has its own generator.
        helper.setBlock(new BlockPos(2, 2, 0), rf);
        helper.assertTrue(device(helper, new BlockPos(2, 2, 0)).setEnabled(null, true), "A generator on another build comes on");
        // Once the first is gone, the second may run.
        helper.setBlock(new BlockPos(0, 2, 2), Blocks.AIR);
        helper.assertTrue(second.setEnabled(null, true) && second.enabled() && second.state().notice().isEmpty(), "With the first gone the second comes on");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void generatorFindsDocksAndStoresOnItsStructure(GameTestHelper helper) {
        var generator = ShipBlocks.GENERATORS.get(ShipFamily.RF).get();
        var dock = ShipBlocks.DOCKS.get(ShipFamily.RF).get();
        var foreignDock = ShipBlocks.DOCKS.get(ShipFamily.MANA).get();
        row(helper, generator, dock, Blocks.CHEST, foreignDock, Blocks.BARREL);
        ShipDeviceBlockEntity gen = device(helper, new BlockPos(0, 2, 2)), rfDock = device(helper, new BlockPos(1, 2, 2)), manaDock = device(helper, new BlockPos(3, 2, 2));
        gen.scan(helper.getLevel(), helper.getLevel().getGameTime());
        rfDock.scan(helper.getLevel(), helper.getLevel().getGameTime());
        manaDock.scan(helper.getLevel(), helper.getLevel().getGameTime());
        helper.assertTrue(gen.docks().equals(java.util.List.of(helper.absolutePos(new BlockPos(1, 2, 2)))), "The generator links its own family's dock only, got " + gen.docks());
        helper.assertTrue(gen.stores().size() == 2, "The chest and the barrel are stores, got " + gen.stores());
        helper.assertTrue(helper.absolutePos(new BlockPos(0, 2, 2)).equals(rfDock.generator()), "The RF dock finds the RF generator, got " + rfDock.generator()
                + " on " + (rfDock.structure() == null ? "no structure" : rfDock.structure().size() + " blocks"));
        helper.assertTrue(manaDock.generator() == null, "The Mana dock finds no generator of its family");
        helper.assertTrue(gen.state().docks() == 1 && gen.state().stores() == 2 && gen.state().structureBlocks() == 5, "The console readings match");
        helper.assertTrue(gen.dronesWanted() == 8, "Five blocks need eight drones, got " + gen.dronesWanted());
        helper.succeed();
    }

    /** A block that was never placed by hand (loaded with its chunk, or set by a command) scans on its first tick, and again as docks change. */
    @GameTest(template = TEMPLATE)
    public static void freshDeviceScansOnItsFirstTickAndCountsDockedDrones(GameTestHelper helper) {
        row(helper, ShipBlocks.GENERATORS.get(ShipFamily.RF).get(), ShipBlocks.DOCKS.get(ShipFamily.RF).get(), Blocks.IRON_BLOCK, ShipBlocks.DOCKS.get(ShipFamily.RF).get());
        ShipDeviceBlockEntity gen = device(helper, new BlockPos(0, 2, 2)), near = device(helper, new BlockPos(1, 2, 2)), far = device(helper, new BlockPos(3, 2, 2));
        helper.assertTrue(gen.structure() == null, "Nothing scanned before the first tick");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(gen.structure() != null && gen.structure().size() == 4, "The first tick scans the structure");
            helper.assertTrue(gen.state().docks() == 2 && gen.state().droneCapacity() == 16 && gen.state().drones() == 0 && gen.state().dronesWanted() == 8,
                    "The generator sums its docks: " + gen.state());
            near.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(ShipFamily.RF).get(), 3));
            far.insertDrones(new ItemStack(ModItems.EMITTER_DRONES.get(ShipFamily.RF).get(), 4));
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(gen.state().drones() == 7, "Docking drones refreshes the generator's count, got " + gen.state().drones());
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE)
    public static void rfGeneratorTakesForgeEnergyAsABlock(GameTestHelper helper) {
        row(helper, ShipBlocks.GENERATORS.get(ShipFamily.RF).get(), Blocks.AIR, ShipBlocks.GENERATORS.get(ShipFamily.MANA).get());
        ShipDeviceBlockEntity rf = device(helper, new BlockPos(0, 2, 2));
        rf.device().set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.energy(rf.device()).withRf(0));
        var storage = helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(new BlockPos(0, 2, 2)), null);
        helper.assertTrue(storage != null && storage.canReceive() && !storage.canExtract(), "RF generators expose a receive-only FE storage");
        helper.assertTrue(storage.receiveEnergy(5_000, false) == 5_000 && DevicePower.energy(rf.device()).rf() == 5_000, "FE charges the block's battery");
        helper.assertTrue(storage.getMaxEnergyStored() == DevicePower.feCapacity(rf.device()), "Capacity follows the device level");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, helper.absolutePos(new BlockPos(2, 2, 2)), null) == null,
                "Mana generators have no RF battery");
        helper.assertTrue(DevicePower.energy(device(helper, new BlockPos(2, 2, 2)).device()).mana() == DevicePower.capacity(rf.device()),
                "A freshly placed Mana generator comes fully charged");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void dockTakesItsFamilyDronesUpToItsCapacity(GameTestHelper helper) {
        row(helper, ShipBlocks.DOCKS.get(ShipFamily.RF).get());
        ShipDeviceBlockEntity dock = device(helper, new BlockPos(0, 2, 2));
        helper.assertTrue(dock.droneCapacity() == 8, "A level 0 dock holds 8 drones, got " + dock.droneCapacity());
        ItemStack rfDrones = new ItemStack(ModItems.EMITTER_DRONES.get(ShipFamily.RF).get(), 10);
        ItemStack manaDrones = new ItemStack(ModItems.EMITTER_DRONES.get(ShipFamily.MANA).get(), 4);
        helper.assertTrue(dock.insertDrones(manaDrones) == 0, "An RF dock refuses Mana drones");
        helper.assertTrue(dock.insertDrones(rfDrones) == 8 && dock.droneCount() == 8, "It takes eight of ten RF drones");
        helper.assertFalse(dock.acceptsDrones(rfDrones), "A full dock takes no more");
        var handler = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(new BlockPos(0, 2, 2)), null);
        helper.assertTrue(handler != null && handler.getStackInSlot(0).getCount() == 8, "Hoppers see the docked drones");
        helper.assertTrue(handler.extractItem(0, 3, false).getCount() == 3 && dock.droneCount() == 5, "Hoppers can take drones out");
        dock.device().set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(0, 10, 0, 0));
        helper.assertTrue(dock.droneCapacity() == 128, "A level 10 dock holds 128 drones, got " + dock.droneCapacity());
        helper.assertTrue(dock.state().drones() == 5, "The console reading follows the count");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void pickedUpDeviceKeepsItsLevelAndCharge(GameTestHelper helper) {
        row(helper, ShipBlocks.GENERATORS.get(ShipFamily.TWINS).get());
        ShipDeviceBlockEntity twins = device(helper, new BlockPos(0, 2, 2));
        twins.device().set(ModDataComponents.DEVICE_PROGRESSION.get(), new DeviceProgression(5, 4, 2, 0));
        twins.device().set(ModDataComponents.DEVICE_ENERGY.get(), DevicePower.energy(twins.device()).withRf(1234).withMana(567));
        helper.assertTrue(twins.setEnabled(null, true), "The generator comes on");
        ItemStack drop = new ItemStack(ShipBlocks.item(RelicRole.TWINS_SHIP_GENERATOR));
        twins.saveToItem(drop, helper.getLevel().registryAccess());
        ShipDeviceItem.ensureState(drop);
        DeviceProgression progression = drop.getOrDefault(ModDataComponents.DEVICE_PROGRESSION.get(), DeviceProgression.DEFAULT);
        helper.assertTrue(progression.level() == 4 && progression.points() == 2 && progression.experience() == 5, "The drop keeps the level");
        helper.assertTrue(DevicePower.energy(drop).rf() == 1234 && DevicePower.energy(drop).mana() == 567, "The drop keeps the charge");
        helper.assertFalse(dev.hurtify.relicsaddon.relic.RelicRuntime.enabled(drop), "A picked-up generator is switched off");
        helper.assertTrue(drop.get(ModDataComponents.INSTANCE_ID.get()).equals(twins.device().get(ModDataComponents.INSTANCE_ID.get())), "The drop keeps its identity");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void recipesLoadWithAndWithoutCreate(GameTestHelper helper) {
        boolean create = ModList.get().isLoaded("create");
        var recipes = helper.getLevel().getRecipeManager();
        for (ShipFamily family : ShipFamily.values()) {
            for (String block : new String[]{family.generatorId(), family.dockId()}) {
                boolean withCreate = recipes.byKey(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, block)).isPresent();
                boolean basic = recipes.byKey(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, block + "_basic")).isPresent();
                helper.assertTrue(withCreate == create, block + ": the Create recipe loads only with Create (loaded=" + create + ", present=" + withCreate + ")");
                helper.assertTrue(basic != create, block + ": the basic recipe loads only without Create");
            }
            helper.assertTrue(recipes.byKey(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, family.droneId())).isPresent(), family.droneId() + " recipe loads");
        }
        helper.succeed();
    }

    private ShipDeviceGameTests() {
    }
}
