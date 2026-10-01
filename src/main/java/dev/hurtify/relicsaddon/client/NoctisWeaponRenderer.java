package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.registry.ModItems;
import dev.hurtify.relicsaddon.relic.EclipseScytheItem;
import dev.hurtify.relicsaddon.relic.NoctisCore;
import dev.hurtify.relicsaddon.relic.TwinsSpearItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws the Noctis weapons in hand with the swarms' glass and light, in the frame the item's display gives them:
 * the spear up the item's Y axis with its tip running with liquid fire, and the scythe likewise, its blade out along
 * -X. In the inventory and on the ground they show their flat icons. Whoever holds a weapon is noted as they are
 * drawn, so a swinging scythe's aura and the spear's charge find them; and a spear carried but not held is drawn
 * as a ghost across its owner's back.
 */
@OnlyIn(Dist.CLIENT)
public final class NoctisWeaponRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ModelResourceLocation SPEAR_ICON = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "item/twins_spear_icon"));
    private static final ModelResourceLocation SCYTHE_ICON = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "item/eclipse_scythe_icon"));
    private static final Matrix4f IDENTITY = new Matrix4f();
    /**
     * The weapons' own buffers, light and glass each with a builder of its own, so both stay open while a weapon is
     * drawn (one shared builder would end the first as the second was taken) and are drawn as soon as it is done.
     */
    private static final MultiBufferSource.BufferSource OWN = own();

    private static MultiBufferSource.BufferSource own() {
        var buffers = new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder>();
        buffers.put(ShieldGlow.TYPE, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 18));
        buffers.put(ShieldVisualRenderer.renderType(), new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 18));
        return MultiBufferSource.immediateWithBuffers(buffers, new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 12));
    }
    /** The living entity being drawn now, if any: the holder of whatever item is drawn meanwhile. */
    private static LivingEntity holder;

    private NoctisWeaponRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static NoctisWeaponRenderer getInstance() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final NoctisWeaponRenderer INSTANCE = new NoctisWeaponRenderer();
    }

    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(SPEAR_ICON);
        event.register(SCYTHE_ICON);
    }

    public static void beforeLiving(RenderLivingEvent.Pre<?, ?> event) {
        holder = event.getEntity();
    }

    public static void afterLiving(RenderLivingEvent.Post<?, ?> event) {
        holder = null;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean spear = stack.getItem() instanceof TwinsSpearItem;
        if (!context.firstPerson() && context != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && context != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || minecraft.level == null) {
            icon(spear ? SPEAR_ICON : SCYTHE_ICON, stack, poses, buffers, light, overlay);
            return;
        }
        LivingEntity held = context.firstPerson() ? minecraft.player : holder;
        double time = minecraft.level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        Matrix4f pose = poses.last().pose();
        VertexConsumer glow = OWN.getBuffer(ShieldGlow.TYPE), fill = OWN.getBuffer(ShieldVisualRenderer.renderType());
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double fullness = NoctisCore.fullness(stack);
        if (spear) {
            Vec3 tail = at(pose, .5, -.2, .5), tip = at(pose, .5, 1.9, .5);
            Vec3 heading = tip.subtract(tail).normalize();
            double charge = held == null ? 0 : TwinsSpearItem.beamCharge(held);
            TwinsSpearVisual.spear(glow, fill, IDENTITY, tip, heading, time, 1, fullness, charge);
            // The liquid fire runs from the tip in the world; the hand's view sits where the camera does.
            Vec3 world = tip.add(camera);
            NoctisFx.fire(world, heading, time, .6 + .4 * fullness + charge);
        } else {
            boolean open = EclipseScytheItem.open(stack);
            double opening = held instanceof Player player ? 1 - player.getCooldowns().getCooldownPercent(ModItems.ECLIPSE_SCYTHE.get(), minecraft.getTimer().getGameTimeDeltaPartialTick(false)) : 1;
            double openness = open ? opening : 1 - opening;
            boolean overdrive = EclipseScytheItem.overdriven(stack, minecraft.level.getGameTime());
            Vec3 base = at(pose, .5, -.1, .5), top = at(pose, .5, .9, .5), out = at(pose, .5, -.1, -.5);
            Vec3 up = top.subtract(base).normalize(), side = out.subtract(base).normalize();
            Vec3[] edge = EclipseScytheVisual.draw(glow, fill, IDENTITY, base, up, side, openness, overdrive ? 1 : 0, fullness, time, 1);
            if (open && held != null && held.swinging && held.getId() >= 0) {
                EclipseScytheVisual.swung(context.firstPerson() ? -1 : held.getId(), edge[0].add(camera), edge[1].add(camera), time);
            }
        }
        OWN.endBatch();
    }

    /** An item-space point as the pose places it: camera-relative, where the swarms' brushes draw. */
    private static Vec3 at(Matrix4f pose, double x, double y, double z) {
        Vector4f p = new Vector4f((float) x, (float) y, (float) z, 1).mul(pose);
        return new Vec3(p.x, p.y, p.z);
    }

    private static void icon(ModelResourceLocation id, ItemStack stack, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getModelManager().getModel(id);
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        for (BakedModel renderPass : model.getRenderPasses(stack, false)) {
            for (RenderType renderType : renderPass.getRenderTypes(stack, false)) {
                VertexConsumer consumer = ItemRenderer.getFoilBufferDirect(buffers, renderType, true, stack.hasFoil());
                itemRenderer.renderModelLists(renderPass, stack, light, overlay, poses, consumer);
            }
        }
    }

    /** A spear carried but not held is a ghost across its owner's back. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            LivingEntityRenderer renderer = event.getSkin(skin);
            if (renderer != null) renderer.addLayer(new BackLayer(renderer));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class BackLayer extends RenderLayer<AbstractClientPlayer, EntityModel<AbstractClientPlayer>> {
        BackLayer(LivingEntityRenderer renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack poses, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                float partial, float age, float yaw, float pitch) {
            if (player.isInvisible() || player.getMainHandItem().getItem() instanceof TwinsSpearItem || player.getOffhandItem().getItem() instanceof TwinsSpearItem) return;
            ItemStack spear = null;
            for (ItemStack stack : player.getInventory().items) if (stack.getItem() instanceof TwinsSpearItem) { spear = stack; break; }
            if (spear == null) return;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) return;
            double time = minecraft.level.getGameTime() + partial;
            poses.pushPose();
            // Across the back, from the left hip to over the right shoulder.
            // The model's y runs down from the neck here: the torso's middle is half a block down it, its back a hand behind.
            if (player.isCrouching()) poses.translate(0, .2, 0);
            poses.translate(0, .55, .2);
            poses.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(35));
            Matrix4f pose = poses.last().pose();
            Vec3 tail = at(pose, 0, 1.05, 0), tip = at(pose, 0, -1.05, 0);
            Vec3 heading = tip.subtract(tail).normalize();
            VertexConsumer glow = OWN.getBuffer(ShieldGlow.TYPE), fill = OWN.getBuffer(ShieldVisualRenderer.renderType());
            TwinsSpearVisual.spear(glow, fill, IDENTITY, tip, heading, time, .35, NoctisCore.fullness(spear), 0);
            // The dark haze that hides its true shape.
            for (int wisp = 0; wisp < 7; wisp++) {
                double k = wisp / 6.0, drift = Math.sin(time * .07 + wisp * 1.7) * .08;
                Vec3 at = tail.lerp(tip, k).add(drift, Math.cos(time * .05 + wisp) * .06, 0);
                GlowBrush.dot(glow, IDENTITY, at, .3 + .08 * Math.sin(time * .1 + wisp), NoctisFx.INK, 170);
            }
            OWN.endBatch();
            poses.popPose();
        }
    }
}
