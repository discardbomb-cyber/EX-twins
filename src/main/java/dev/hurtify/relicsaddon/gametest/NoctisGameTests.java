package dev.hurtify.relicsaddon.gametest;

import static dev.hurtify.relicsaddon.gametest.DeviceTestSupport.ARENA;

import dev.hurtify.relicsaddon.AddonConfig;
import dev.hurtify.relicsaddon.drone.HiveSettings;
import dev.hurtify.relicsaddon.registry.ModDataComponents;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.EclipseScytheItem;
import dev.hurtify.relicsaddon.relic.NoctisCore;
import dev.hurtify.relicsaddon.relic.RelicRole;
import dev.hurtify.relicsaddon.relic.TwinsSpearEntity;
import dev.hurtify.relicsaddon.relic.TwinsSpearItem;
import dev.hurtify.relicsaddon.server.NoctisBeam;
import dev.hurtify.relicsaddon.server.NoctisCombat;
import dev.hurtify.relicsaddon.server.NoctisWormhole;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Noctis weapons against real husks: the spear's reflection pins one and drags another in, its halo rains
 * on them, its beam strikes along its line and breaks no block on a safe server, the light cut reaches all round;
 * the scythe folded does nothing, open takes in its arc, and planted throws every foe through its wormhole.
 */
@GameTestHolder("relics_addon")
@PrefixGameTestTemplate(false)
public final class NoctisGameTests {
    private record Field(ServerPlayer player, ItemStack weapon, ItemStack hive) { }

    /** A player at the arena's middle wearing a full Twins hive of healers, {@code weapon} in hand with a full core. */
    private static Field field(GameTestHelper helper, boolean scythe) {
        // Open floor round the middle: the arena's fixtures would catch a thrown spear.
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 5; y++) for (int z = 0; z <= 11; z++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        ServerPlayer player = DeviceTestSupport.player(helper, new Vec3(6.5, 1, 6.5));
        ItemStack hive = DeviceTestSupport.equip(helper, player, RelicRole.TWINS_HIVE, 0);
        hive.set(ModDataComponents.HIVE_SETTINGS.get(), new HiveSettings(500, 0, 0, 0));
        ItemStack weapon = new ItemStack(scythe ? ModItems.ECLIPSE_SCYTHE.get() : ModItems.TWINS_SPEAR.get());
        weapon.set(ModDataComponents.NOCTIS_CORE.get(), NoctisCore.MAX);
        if (scythe) EclipseScytheItem.setOpen(weapon, true, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, weapon);
        return new Field(player, weapon, hive);
    }

    /** A full swing: the test player is not ticked, so its swing never charges by itself. */
    private static void charged(ServerPlayer player) {
        try {
            var ticker = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
            ticker.setAccessible(true);
            ticker.setInt(player, 100);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Husk husk(GameTestHelper helper, Vec3 at) {
        Husk husk = EntityType.HUSK.create(helper.getLevel());
        Vec3 absolute = helper.absoluteVec(at);
        husk.moveTo(absolute.x, absolute.y, absolute.z, 0, 0);
        husk.setNoAi(true);
        husk.setPersistenceRequired();
        helper.getLevel().addFreshEntity(husk);
        return husk;
    }

    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void reflectionPinsItsMarkAndDragsTheRestIn(GameTestHelper helper) {
        Field field = field(helper, false);
        Husk mark = husk(helper, new Vec3(6.5, 1, 2.5)), other = husk(helper, new Vec3(10.5, 1, 2.5));
        field.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, mark.position().add(0, 1, 0));
        TwinsSpearEntity spear = TwinsSpearItem.throwFrom(helper.getLevel(), field.player, field.weapon);
        helper.assertTrue(spear != null, "The reflection is thrown");
        // A creature with no mind of its own does not move at all, pushed or pulled: this one has its wits.
        other.setNoAi(false);
        Vec3 before = other.position();
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(spear.state() == TwinsSpearEntity.ANCHORED && spear.target() == mark, "The reflection pins the husk it struck (state " + spear.state() + ", at " + helper.relativeVec(spear.position()) + ")");
            helper.assertTrue(mark.getHealth() < mark.getMaxHealth(), "The husk is struck, and the halo's blades strike it");
            helper.assertTrue(other.position().distanceTo(mark.position()) < before.distanceTo(mark.position()) - 1, "The other husk is dragged in");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 160)
    public static void blackHaloRainsOnEveryFoeBelow(GameTestHelper helper) {
        Field field = field(helper, false);
        Husk near = husk(helper, new Vec3(6.5, 1, 3.5)), far = husk(helper, new Vec3(3.5, 1, 9.5));
        field.player.setXRot(-80);
        TwinsSpearEntity spear = TwinsSpearItem.throwFrom(helper.getLevel(), field.player, field.weapon);
        helper.assertTrue(spear != null && spear.state() == TwinsSpearEntity.HALO, "Thrown steeply up, the reflection opens the halo");
        helper.assertTrue(NoctisCore.charge(field.weapon) == NoctisCore.MAX - TwinsSpearEntity.HALO_COST, "The halo costs its share of the core");
        helper.runAfterDelay(TwinsSpearEntity.RISE + TwinsSpearEntity.RAIN, () -> {
            helper.assertTrue(near.getHealth() < near.getMaxHealth() && far.getHealth() < far.getMaxHealth(), "Light spears fall on both");
            helper.assertTrue(near.getLastDamageSource() != null && near.getLastDamageSource().is(NoctisCombat.LIGHT), "They fall as light");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 60)
    public static void quantumBeamStrikesAlongItsLineAndSparesTheLandWhenSafe(GameTestHelper helper) {
        Field field = field(helper, false);
        boolean wasSafe = AddonConfig.ARMAGEDDON_SAFE.get();
        AddonConfig.ARMAGEDDON_SAFE.set(true);
        BlockPos wall = helper.absolutePos(new BlockPos(6, 2, 1));
        helper.setBlock(new BlockPos(6, 2, 1), Blocks.STONE);
        Husk onLine = husk(helper, new Vec3(6.5, 1, 3.5)), aside = husk(helper, new Vec3(10.5, 1, 3.5));
        field.player.setXRot(0);
        field.player.setYRot(180);
        field.player.setYHeadRot(180);
        helper.assertTrue(NoctisBeam.fire(helper.getLevel(), field.player, field.weapon, field.hive), "The beam fires with a full core and a hive");
        helper.assertTrue(onLine.getHealth() < onLine.getMaxHealth() && onLine.getLastDamageSource().is(NoctisBeam.DAMAGE), "The husk on the line is struck");
        helper.assertTrue(aside.getHealth() == aside.getMaxHealth(), "The husk off the line is not");
        helper.assertTrue(NoctisCore.charge(field.weapon) == NoctisCore.MAX - NoctisBeam.CORE_COST, "The beam costs its share of the core");
        helper.assertFalse(NoctisBeam.boring(), "A safe server bores no tunnel");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(wall).is(Blocks.STONE), "The stone on its line stands");
            AddonConfig.ARMAGEDDON_SAFE.set(wasSafe);
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void lightCutReachesAllRound(GameTestHelper helper) {
        Field field = field(helper, false);
        Husk behind = husk(helper, new Vec3(6.5, 1, 9.5)), beside = husk(helper, new Vec3(3.5, 1, 6.5)), far = husk(helper, new Vec3(6.5, 1, 0.5));
        helper.assertTrue(NoctisCombat.lightCut(helper.getLevel(), field.player, field.weapon), "The cut is made");
        helper.assertTrue(behind.getHealth() < behind.getMaxHealth() && beside.getHealth() < beside.getMaxHealth(), "It cuts behind and beside");
        helper.assertTrue(far.getHealth() == far.getMaxHealth(), "It stops at its reach");
        helper.assertTrue(NoctisCore.charge(field.weapon) == NoctisCore.MAX - NoctisCombat.CUT_COST, "The cut costs its share of the core");
        helper.succeed();
    }

    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void scytheFoldedIsAHiltAndOpenTakesInItsArc(GameTestHelper helper) {
        Field field = field(helper, true);
        EclipseScytheItem.setOpen(field.weapon, false, false);
        var damage = net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE;
        helper.assertTrue(field.weapon.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS) == null, "Folded, it is no weapon");
        EclipseScytheItem.setOpen(field.weapon, true, false);
        // The test player is not ticked, so the blade's weight is put on by hand.
        field.player.getAttributes().addTransientAttributeModifiers(com.google.common.collect.ImmutableMultimap.of(damage,
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(net.minecraft.resources.ResourceLocation.withDefaultNamespace("base_attack_damage"), EclipseScytheItem.DAMAGE, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE)));
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(field.player.getAttributeValue(damage) > 10, "Open, it is a heavy blade");
            Husk struck = husk(helper, new Vec3(6.5, 1, 4.5)), inArc = husk(helper, new Vec3(8, 1, 4.5)), behind = husk(helper, new Vec3(6.5, 1, 9));
            field.player.setYRot(180);
            field.player.setYHeadRot(180);
            charged(field.player);
            field.player.attack(struck);
            helper.assertTrue(struck.getHealth() < struck.getMaxHealth(), "The husk struck is hurt");
            helper.assertTrue(inArc.getHealth() < inArc.getMaxHealth(), "The husk in the arc is hurt too");
            helper.assertTrue(behind.getHealth() == behind.getMaxHealth(), "The husk behind is not");
            helper.assertTrue(NoctisCore.charge(field.weapon) == NoctisCore.MAX, "A full core stays full");
            helper.succeed();
        });
    }

    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void wormholeThrowsEveryFoeRoundItAway(GameTestHelper helper) {
        Field field = field(helper, true);
        Husk near = husk(helper, new Vec3(3.5, 1, 6.5)), far = husk(helper, new Vec3(6.5, 1, 1.5));
        Vec3 at = field.player.position();
        helper.assertTrue(NoctisWormhole.plant(helper.getLevel(), field.player, field.weapon, field.hive), "The scythe is planted");
        helper.assertTrue(NoctisCore.charge(field.weapon) == 0, "The wormhole takes the whole core");
        helper.assertFalse(NoctisWormhole.plant(helper.getLevel(), field.player, field.weapon, field.hive), "An empty core plants nothing");
        helper.runAfterDelay(NoctisWormhole.SCAN + 2, () -> {
            for (Husk husk : new Husk[]{near, far}) {
                helper.assertTrue(!husk.isAlive() || husk.position().distanceTo(at) >= NoctisWormhole.THROW_NEAR - 1, "The husk is thrown far off");
                helper.assertTrue(husk.getLastDamageSource() != null && husk.getLastDamageSource().is(NoctisWormhole.DAMAGE), "It is struck on its way");
            }
            helper.succeed();
        });
    }
}
