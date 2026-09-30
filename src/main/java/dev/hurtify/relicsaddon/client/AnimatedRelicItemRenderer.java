package dev.hurtify.relicsaddon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hurtify.relicsaddon.RelicsAddon;
import dev.hurtify.relicsaddon.domain.device.RelicRole;
import dev.hurtify.relicsaddon.relic.AutonomousRelicItem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Quaternionf;

@OnlyIn(Dist.CLIENT)
public final class AnimatedRelicItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final String BODY = "body";
    private static final String CORE = "core";

    private AnimatedRelicItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static AnimatedRelicItemRenderer getInstance() {
        return Holder.INSTANCE;
    }

    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        for (RelicRole role : RelicRole.shields()) {
            registerParts(event, role);
        }
        for (RelicRole role : RelicRole.drones()) {
            registerParts(event, role);
            event.register(ModelResourceLocation.standalone(partId(role, "swarm")));
            event.register(ModelResourceLocation.standalone(partId(role, "dense")));
        }
        for (RelicRole role : RelicRole.hives()) registerParts(event, role);
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
            renderPart(role, BODY, stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        } else {
            renderPart(role, BODY, stack, poseStack, buffers, light, overlay);
        }

        poseStack.pushPose();
        if (role == RelicRole.TWINS_HIVE) applyTwinsHiveLayer(poseStack, 1, time);
        else if (role == RelicRole.TWINS_SHIELD) applyTwinsShieldLayer(poseStack, 1, time);
        else aroundCenter(poseStack, 0.0F, 0.0F, 1.0F, RelicAnimationPose.coreDegrees(time));
        renderPart(role, CORE, stack, poseStack, buffers, light, overlay);
        poseStack.popPose();

        renderShells(role, stack, poseStack, buffers, light, overlay, time);
        if (role == RelicRole.TWINS_HIVE || role == RelicRole.TWINS_SHIELD) {
            poseStack.pushPose();
            if (role == RelicRole.TWINS_HIVE) applyTwinsHiveLayer(poseStack, 2, time);
            else applyTwinsShieldLayer(poseStack, 2, time);
            renderPart(role, "fx", stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        } else renderPart(role, "fx", stack, poseStack, buffers, light, overlay);
        poseStack.popPose();
    }

    private static void renderShells(RelicRole role, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, double time) {
        int shells = shellCount(role);
        for (int index = 0; index < shells; index++) {
            poseStack.pushPose();
            switch (role) {
                case RF_DRONE -> animateRfWing(poseStack, index, time);
                case MANA_SHIELD -> animateManaShell(poseStack, index, shells, time);
                case MANA_DRONE -> animateManaDroneShell(poseStack, index, shells, time);
                case TWINS_SHIELD -> animateTwinsShieldArc(poseStack, index, time);
                case TWINS_DRONE -> animateTwinsFacet(poseStack, index, time, false);
                case RF_HIVE, MANA_HIVE, TWINS_HIVE -> animateHiveShell(poseStack, role, index, time);
                default -> {
                }
            }
            renderPart(role, "shell_" + index, stack, poseStack, buffers, light, overlay);
            poseStack.popPose();
        }
    }

    private static void animateRfWing(PoseStack poseStack, int index, double time) {
        double angle = RelicAnimationPose.rfWingAngleRadians(index);
        float pivotX = (float) (0.5D + Math.cos(angle) * 3.15D / 16.0D);
        float pivotY = (float) (0.5D + Math.sin(angle) * 3.15D / 16.0D);
        float flex = RelicAnimationPose.rfWingFlexDegrees(time, index);
        poseStack.translate(pivotX, pivotY, 0.5F);
        poseStack.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(flex), (float) -Math.sin(angle), (float) Math.cos(angle), 0.0F));
        poseStack.translate(-pivotX, -pivotY, -0.5F);
    }

    private static void animateHiveShell(PoseStack poses, RelicRole role, int index, double time) {
        var pose = HiveShellPose.sample(shellCount(role), index, time);
        poses.translate(pose.x() * pose.offset(), pose.y() * pose.offset(), pose.z() * pose.offset());
        if (role == RelicRole.TWINS_HIVE) {
            aroundCenter(poses, (float) pose.x(), (float) pose.y(), (float) pose.z(), pose.tilt());
            aroundCenter(poses, (float) pose.x(), (float) pose.y(), (float) pose.z(), pose.spin());
        }
        else aroundCenter(poses, (float) -pose.y(), (float) pose.x(), 0, pose.tilt());
    }

    private static void applyTwinsHiveLayer(PoseStack poses, int layer, double time) {
        HiveShellPose.LayerPose pose = HiveShellPose.twinsLayer(layer, time);
        aroundCenter(poses, pose.axisX(), pose.axisY(), pose.axisZ(), pose.degrees());
    }

    private static void applyTwinsShieldLayer(PoseStack poses, int layer, double time) {
        TwinsShieldLayerPose.Layer pose = TwinsShieldLayerPose.layer(layer, time);
        aroundCenter(poses, pose.axisX(), pose.axisY(), pose.axisZ(), pose.degrees());
    }

    public void renderSwarm(RelicRole role, PoseStack poses, MultiBufferSource buffers, boolean detailed) {
        renderSwarm(role, poses, buffers, detailed, false);
    }

    public void renderSwarm(RelicRole role, PoseStack poses, MultiBufferSource buffers, boolean detailed, boolean dense) {
        if (detailed) renderRole(role, ItemStack.EMPTY, poses, buffers,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        else renderPart(role, dense ? "dense" : "swarm", ItemStack.EMPTY, poses, buffers, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }

    private static void animateManaShell(PoseStack poseStack, int index, int shells, double time) {
        double angle = RelicAnimationPose.shellAngleRadians(index, shells);
        float breath = RelicAnimationPose.manaBreath(time, index);
        poseStack.translate(Math.cos(angle) * breath, Math.sin(angle) * breath, 0.0D);
        aroundCenter(poseStack, (float) Math.cos(angle), (float) Math.sin(angle), 0.0F,
                RelicAnimationPose.manaTiltDegrees(time, index));
    }

    private static void animateTwinsFacet(PoseStack poseStack, int index, double time, boolean shield) {
        TwinsFacetPose.Pose pose = TwinsFacetPose.sample(time, index, shield);
        TwinsFacetPose.Point normal = pose.normal(), pivot = pose.pivot(), tangent = pose.tangent();
        poseStack.translate(normal.x() * pose.offset(), normal.y() * pose.offset(), normal.z() * pose.offset());
        poseStack.translate(.5D + pivot.x(), .5D + pivot.y(), .5D + pivot.z());
        poseStack.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(pose.tilt()),
                (float) tangent.x(), (float) tangent.y(), (float) tangent.z()));
        poseStack.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(pose.twist()),
                (float) normal.x(), (float) normal.y(), (float) normal.z()));
        poseStack.translate(-.5D - pivot.x(), -.5D - pivot.y(), -.5D - pivot.z());
    }

    private static void animateTwinsShieldArc(PoseStack poseStack, int index, double time) {
        animateTwinsFacet(poseStack, index, time, true);
        TwinsFacetPose.Pose pose = TwinsFacetPose.sample(time, index, true);
        TwinsFacetPose.Point normal = pose.normal(), pivot = pose.pivot();
        poseStack.translate(.5D + pivot.x(), .5D + pivot.y(), .5D + pivot.z());
        poseStack.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(TwinsShieldLayerPose.shellSpin(index, time)),
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
        poseStack.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(wrappedDegrees),
                axisX / axisLength, axisY / axisLength, axisZ / axisLength));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
    }

    private static void renderPart(RelicRole role, String part, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel model = minecraft.getModelManager().getModel(ModelResourceLocation.standalone(partId(role, part)));
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        for (BakedModel renderPass : model.getRenderPasses(stack, false)) {
            for (RenderType renderType : renderPass.getRenderTypes(stack, false)) {
                VertexConsumer consumer = ItemRenderer.getFoilBufferDirect(buffers, renderType, true, stack.hasFoil());
                itemRenderer.renderModelLists(renderPass, stack, light, overlay, poseStack, consumer);
            }
        }
    }

    private static void registerParts(ModelEvent.RegisterAdditional event, RelicRole role) {
        event.register(ModelResourceLocation.standalone(partId(role, BODY)));
        event.register(ModelResourceLocation.standalone(partId(role, CORE)));
        event.register(ModelResourceLocation.standalone(partId(role, "fx")));
        for (int index = 0; index < shellCount(role); index++) {
            event.register(ModelResourceLocation.standalone(partId(role, "shell_" + index)));
        }
    }

    private static ResourceLocation partId(RelicRole role, String part) {
        return ResourceLocation.fromNamespaceAndPath(RelicsAddon.MOD_ID, "item/animated/" + role.itemId() + "_" + part);
    }

    private static int shellCount(RelicRole role) {
        return switch (role) {
            case RF_DRONE, MANA_SHIELD -> 4;
            case RF_HIVE -> 4;
            case MANA_HIVE -> 6;
            case TWINS_HIVE -> 12;
            case MANA_DRONE -> 6;
            case TWINS_SHIELD, TWINS_DRONE -> TwinsFacetPose.COUNT;
            default -> 0;
        };
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
