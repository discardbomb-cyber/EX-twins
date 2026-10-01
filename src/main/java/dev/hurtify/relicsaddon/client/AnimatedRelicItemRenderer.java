package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import dev.hurtify.relicsaddon.relic.RelicRole;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Quaternionf;

@OnlyIn(Dist.CLIENT)
public final class AnimatedRelicItemRenderer extends BlockEntityWithoutLevelRenderer {
    /**
     * Rotation scratch: {@link PoseStack#mulPose} copies the quaternion into the matrices and keeps
     * no reference, and items are only ever rendered on the render thread.
     */
    private static final Quaternionf ROTATION = new Quaternionf();

    private AnimatedRelicItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static AnimatedRelicItemRenderer getInstance() {
        return Holder.INSTANCE;
    }

    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (RelicRole role : RelicRole.values()) {
            for (ModelResourceLocation id : RelicPartModels.all(role)) event.register(id);
        }
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        if (!(stack.getItem() instanceof AutonomousRelicItem relic)) {
            return;
        }
        renderRole(relic.role(), stack, poseStack, buffers, light, overlay);
    }

    private void renderRole(RelicRole role, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        double time = animationTime();
        poseStack.pushPose();
        poseStack.translate(0.0D, RelicAnimationPose.sharedBob(time), 0.0D);
        if (role == RelicRole.TWINS_HIVE || role == RelicRole.TWINS_SHIELD) {
            poseStack.pushPose();
            if (role == RelicRole.TWINS_HIVE) applyTwinsHiveLayer(poseStack, 0, time);
            else applyTwinsShieldLayer(poseStack, 0, time);
            renderPart(RelicPartModels.body(role), stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        } else {
            renderPart(RelicPartModels.body(role), stack, poseStack, buffers, light, overlay);
        }

        poseStack.pushPose();
        if (role == RelicRole.TWINS_HIVE) applyTwinsHiveLayer(poseStack, 1, time);
        else if (role == RelicRole.TWINS_SHIELD) applyTwinsShieldLayer(poseStack, 1, time);
        else aroundCenter(poseStack, 0.0F, 0.0F, 1.0F, RelicAnimationPose.coreDegrees(time));
        renderPart(RelicPartModels.core(role), stack, poseStack, buffers, light, overlay);
        poseStack.popPose();

        renderShells(role, stack, poseStack, buffers, light, overlay, time);
        if (role == RelicRole.TWINS_HIVE || role == RelicRole.TWINS_SHIELD) {
            poseStack.pushPose();
            if (role == RelicRole.TWINS_HIVE) applyTwinsHiveLayer(poseStack, 2, time);
            else applyTwinsShieldLayer(poseStack, 2, time);
            renderPart(RelicPartModels.fx(role), stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        } else renderPart(RelicPartModels.fx(role), stack, poseStack, buffers, light, overlay);
        poseStack.popPose();
    }

    private static void renderShells(RelicRole role, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, double time) {
        int shells = RelicPartModels.shellCount(role);
        for (int index = 0; index < shells; index++) {
            poseStack.pushPose();
            switch (role) {
                case RF_DRONE -> animateRfWing(poseStack, index, time);
                case MANA_SHIELD -> animateManaShell(poseStack, index, shells, time);
                case MANA_DRONE -> animateManaDroneShell(poseStack, index, shells, time);
                case TWINS_SHIELD -> animateTwinsFacet(poseStack, index, time, true);
                case TWINS_DRONE -> animateTwinsFacet(poseStack, index, time, false);
                case RF_HIVE, MANA_HIVE, TWINS_HIVE -> animateHiveShell(poseStack, role, shells, index, time);
                default -> {
                }
            }
            renderPart(RelicPartModels.shell(role, index), stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        }
    }

    private static void animateRfWing(PoseStack poseStack, int index, double time) {
        double angle = RelicAnimationPose.rfWingAngleRadians(index);
        float pivotX = (float) (0.5D + Math.cos(angle) * 3.15D / 16.0D);
        float pivotY = (float) (0.5D + Math.sin(angle) * 3.15D / 16.0D);
        float flex = RelicAnimationPose.rfWingFlexDegrees(time, index);
        poseStack.translate(pivotX, pivotY, 0.5F);
        poseStack.mulPose(ROTATION.rotationAxis((float) Math.toRadians(flex), (float) -Math.sin(angle), (float) Math.cos(angle), 0.0F));
        poseStack.translate(-pivotX, -pivotY, -0.5F);
    }

    private static void animateHiveShell(PoseStack poses, RelicRole role, int shells, int index, double time) {
        double[] axis = HiveShellPose.axis(shells, index);
        double x = axis[0], y = axis[1], z = axis[2], offset = HiveShellPose.opening(shells, index, time);
        float tilt = HiveShellPose.tilt(shells, index, time);
        poses.translate(x * offset, y * offset, z * offset);
        if (role == RelicRole.TWINS_HIVE) {
            aroundCenter(poses, (float) x, (float) y, (float) z, tilt);
            aroundCenter(poses, (float) x, (float) y, (float) z, HiveShellPose.spin(shells, index, time));
        }
        else aroundCenter(poses, (float) -y, (float) x, 0, tilt);
    }

    private static void applyTwinsHiveLayer(PoseStack poses, int layer, double time) {
        float[] axis = HiveShellPose.twinsLayerAxis(layer);
        aroundCenter(poses, axis[0], axis[1], axis[2], HiveShellPose.twinsLayerDegrees(layer, time));
    }

    private static void applyTwinsShieldLayer(PoseStack poses, int layer, double time) {
        float[] axis = TwinsShieldLayerPose.axis(layer);
        aroundCenter(poses, axis[0], axis[1], axis[2], TwinsShieldLayerPose.degrees(layer, time));
    }

    public void renderSwarm(RelicRole role, PoseStack poses, MultiBufferSource buffers, boolean detailed) {
        renderSwarm(role, poses, buffers, detailed, false);
    }

    public void renderSwarm(RelicRole role, PoseStack poses, MultiBufferSource buffers, boolean detailed, boolean dense) {
        if (detailed) renderRole(role, ItemStack.EMPTY, poses, buffers,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        else renderPart(RelicPartModels.swarm(role, dense), ItemStack.EMPTY, poses, buffers, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }

    private static void animateManaShell(PoseStack poseStack, int index, int shells, double time) {
        double angle = RelicAnimationPose.shellAngleRadians(index, shells);
        float breath = RelicAnimationPose.manaBreath(time, index);
        poseStack.translate(Math.cos(angle) * breath, Math.sin(angle) * breath, 0.0D);
        aroundCenter(poseStack, (float) Math.cos(angle), (float) Math.sin(angle), 0.0F,
                RelicAnimationPose.manaTiltDegrees(time, index));
    }

    /**
     * One facet of the Twins drone armour or one arc of the Twins shield: the constant basis is
     * read from the table, the clock is sampled once. The shield arc additionally spins around
     * its radial axis after the breathing twist.
     */
    private static void animateTwinsFacet(PoseStack poseStack, int index, double time, boolean shield) {
        TwinsFacetPose.Basis basis = TwinsFacetPose.basis(index);
        TwinsFacetPose.Point normal = basis.normal(), pivot = basis.pivot(shield), tangent = basis.tangent();
        double offset = TwinsFacetPose.offset(time, index, shield);
        poseStack.translate(normal.x() * offset, normal.y() * offset, normal.z() * offset);
        poseStack.translate(.5D + pivot.x(), .5D + pivot.y(), .5D + pivot.z());
        poseStack.mulPose(ROTATION.rotationAxis((float) Math.toRadians(TwinsFacetPose.tilt(time, index)),
                (float) tangent.x(), (float) tangent.y(), (float) tangent.z()));
        poseStack.mulPose(ROTATION.rotationAxis((float) Math.toRadians(TwinsFacetPose.twist(time, index, shield)),
                (float) normal.x(), (float) normal.y(), (float) normal.z()));
        poseStack.translate(-.5D - pivot.x(), -.5D - pivot.y(), -.5D - pivot.z());
        if (!shield) return;
        poseStack.translate(.5D + pivot.x(), .5D + pivot.y(), .5D + pivot.z());
        poseStack.mulPose(ROTATION.rotationAxis((float) Math.toRadians(TwinsShieldLayerPose.shellSpin(index, time)),
                (float) normal.x(), (float) normal.y(), (float) normal.z()));
        poseStack.translate(-.5D - pivot.x(), -.5D - pivot.y(), -.5D - pivot.z());
    }

    private static void animateManaDroneShell(PoseStack poseStack, int index, int shells, double time) {
        animateDroneShell(poseStack, RelicAnimationPose.shellAngleRadians(index, shells),
                RelicAnimationPose.manaDroneShellOffset(time, index), RelicAnimationPose.manaDroneShellTiltDegrees(time, index));
    }

    private static void animateDroneShell(PoseStack poseStack, double angle, float offset, float tiltDegrees) {
        poseStack.translate(Math.cos(angle) * offset, Math.sin(angle) * offset, 0.0D);
        aroundCenter(poseStack, (float) -Math.sin(angle), (float) Math.cos(angle), 0.0F, tiltDegrees);
    }

    private static void aroundCenter(PoseStack poseStack, float axisX, float axisY, float axisZ, float degrees) {
        float axisLength = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
        if (!Float.isFinite(axisLength) || axisLength < 1.0E-5F || !Float.isFinite(degrees)) return;
        float wrappedDegrees = degrees % 360.0F;
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(ROTATION.rotationAxis((float) Math.toRadians(wrappedDegrees),
                axisX / axisLength, axisY / axisLength, axisZ / axisLength));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
    }

    private static void renderPart(ModelResourceLocation id, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getModelManager().getModel(id);
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        for (BakedModel renderPass : model.getRenderPasses(stack, false)) {
            for (RenderType renderType : renderPass.getRenderTypes(stack, false)) {
                VertexConsumer consumer = ItemRenderer.getFoilBufferDirect(buffers, renderType, true, stack.hasFoil());
                itemRenderer.renderModelLists(renderPass, stack, light, overlay, poseStack, consumer);
            }
        }
    }

    private static double animationTime() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            return minecraft.level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        }
        return Util.getMillis() / 50.0D;
    }

    private static final class Holder {
        private static final AnimatedRelicItemRenderer INSTANCE = new AnimatedRelicItemRenderer();
    }
}
