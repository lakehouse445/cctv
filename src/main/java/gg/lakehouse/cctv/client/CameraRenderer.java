package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.camera.CameraBlock;
import gg.lakehouse.cctv.camera.CameraBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Poses the camera's moving parts. The base renders from the blockstate; the
 * arm is the swivel post, panning with the camera's yaw about its own axis;
 * the head pans with it and tilts with the pitch about the authored tilt
 * joint. Pivots match the model's rig.
 */
public class CameraRenderer implements BlockEntityRenderer<CameraBlockEntity> {
    public static final ResourceLocation ARM_MODEL = new ResourceLocation(CCTV.MOD_ID, "block/camera_arm");
    public static final ResourceLocation HEAD_MODEL = new ResourceLocation(CCTV.MOD_ID, "block/camera_head");

    /** The pan post's vertical axis: the post geometry's true center. */
    private static final float PAN_X = 7.98075f / 16;
    private static final float PAN_Z = 7.81548f / 16;
    /** The tilt joint, from the model rig. */
    private static final float TILT_Y = 6.5f / 16;
    private static final float TILT_Z = 8.36548f / 16;
    /** Wall mount: moves the upright head to the tip of the horizontal post. */
    private static final float WALL_HEAD_Y = 1.8655f / 16;
    private static final float WALL_HEAD_Z = 1.1345f / 16;

    public CameraRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(CameraBlockEntity camera, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        var state = camera.getBlockState();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING) || !state.hasProperty(CameraBlock.FACE)) return;

        int yaw = switch (state.getValue(HorizontalDirectionalBlock.FACING)) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };

        var minecraft = Minecraft.getInstance();
        var itemRenderer = minecraft.getItemRenderer();
        var buffer = buffers.getBuffer(Sheets.cutoutBlockSheet());

        poseStack.pushPose();
        // Match the blockstate y rotation (clockwise from above) about the block centre.
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.translate(-0.5, 0, -0.5);

        // Arm (the pan post): reoriented per mount — up from the floor, down
        // from the ceiling, out from the wall — swiveling with yaw about its
        // own axis inside that space.
        poseStack.pushPose();
        switch (state.getValue(CameraBlock.FACE)) {
            case CEILING -> {
                poseStack.translate(0.5, 0.5, 0.5);
                poseStack.mulPose(Axis.XP.rotationDegrees(180));
                poseStack.mulPose(Axis.YP.rotationDegrees(180));
                poseStack.translate(-0.5, -0.5, -0.5);
            }
            case WALL -> {
                poseStack.translate(0.5, 0.5, 0.5);
                poseStack.mulPose(Axis.XP.rotationDegrees(-90));
                poseStack.translate(-0.5, -0.5, -0.5);
            }
            default -> {
            }
        }
        poseStack.translate(PAN_X, 0, PAN_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-camera.getYaw()));
        poseStack.translate(-PAN_X, 0, -PAN_Z);
        itemRenderer.renderModelLists(minecraft.getModelManager().getModel(ARM_MODEL),
            ItemStack.EMPTY, packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer);
        poseStack.popPose();

        // Head. Floor/wall: upright, at the post tip. Ceiling: the whole
        // unit hangs flipped like a pendant camera — the flip mirrors the
        // rig's axes, so yaw and pitch negate to keep the lens on the ray.
        boolean ceiling = state.getValue(CameraBlock.FACE)
            == net.minecraft.world.level.block.state.properties.AttachFace.CEILING;
        poseStack.pushPose();
        if (ceiling) {
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.mulPose(Axis.XP.rotationDegrees(180));
            poseStack.mulPose(Axis.YP.rotationDegrees(180));
            poseStack.translate(-0.5, -0.5, -0.5);
        } else if (state.getValue(CameraBlock.FACE)
            == net.minecraft.world.level.block.state.properties.AttachFace.WALL) {
            poseStack.translate(0, WALL_HEAD_Y, WALL_HEAD_Z);
        }
        poseStack.translate(PAN_X, 0, PAN_Z);
        poseStack.mulPose(Axis.YP.rotationDegrees(ceiling ? camera.getYaw() : -camera.getYaw()));
        poseStack.translate(-PAN_X, 0, -PAN_Z);
        poseStack.translate(PAN_X, TILT_Y, TILT_Z);
        poseStack.mulPose(Axis.XP.rotationDegrees(ceiling ? -camera.getPitch() : camera.getPitch()));
        poseStack.translate(-PAN_X, -TILT_Y, -TILT_Z);
        itemRenderer.renderModelLists(minecraft.getModelManager().getModel(HEAD_MODEL),
            ItemStack.EMPTY, packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer);
        poseStack.popPose();

        poseStack.popPose(); // facing rotation
    }
}
