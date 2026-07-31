package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.microphone.DesktopMicrophoneBlock;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * The microphones' moving parts. The intercom gets a VCR-style dot-matrix
 * readout on its little screen: the automatic LIVE/MUTED status, or whatever
 * Lua set with setDisplay. The desktop mic's mute button sits depressed
 * while the mic is muted and pops back out when it goes live, with a short
 * travel between - like the latching mute keys on real broadcast desks.
 */
public class MicrophoneRenderer implements BlockEntityRenderer<MicrophoneBlockEntity> {
    public static final ResourceLocation BUTTON_MODEL =
        new ResourceLocation(CCTV.MOD_ID, "block/desktop_microphone_button");

    // Intercom screen element: x 1-6, y 8-10, face at z 13.975 (facing north).
    private static final int CELLS = MicrophoneBlockEntity.DISPLAY_CELLS;
    private static final float SCREEN_CENTER_X = 3.5F / 16.0F;
    private static final float SCREEN_CENTER_Y = 9.0F / 16.0F;
    private static final float TEXT_Z = 13.975F / 16.0F - 0.0055F;
    private static final float STRIP_WIDTH = 4.4F / 16.0F;
    private static final float MAX_TEXT_HEIGHT = 1.6F / 16.0F;

    /** The button is 1px proud; sink it most of the way when muted. */
    private static final float PRESS_DEPTH = 0.45F / 16.0F;
    private static final float TRAVEL_MS = 120.0F;

    public MicrophoneRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(MicrophoneBlockEntity mic, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        var state = mic.getBlockState();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING)) return;
        var facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int yaw = switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };

        poseStack.pushPose();
        // Match the blockstate y rotation (clockwise from above) about the block centre.
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.translate(-0.5, 0, -0.5);
        if (state.getBlock() instanceof DesktopMicrophoneBlock) {
            renderButton(mic, poseStack, buffers, packedLight);
        } else {
            int worldLight = mic.getLevel() != null
                ? LevelRenderer.getLightColor(mic.getLevel(), mic.getBlockPos().relative(facing))
                : packedLight;
            renderDisplay(mic, poseStack, buffers, worldLight);
        }
        poseStack.popPose();
    }

    private static void renderButton(MicrophoneBlockEntity mic, PoseStack poseStack,
                                     MultiBufferSource buffers, int light) {
        // Muted = depressed. Travel is wall-clock so the speed is fps-proof.
        float target = mic.isListening() ? 0.0F : 1.0F;
        long now = Util.getMillis();
        if (mic.clientPress < 0) {
            mic.clientPress = target; // first sight: no travel
        } else {
            float step = Math.min(100, now - mic.clientPressAt) / TRAVEL_MS;
            mic.clientPress = mic.clientPress > target
                ? Math.max(target, mic.clientPress - step)
                : Math.min(target, mic.clientPress + step);
        }
        mic.clientPressAt = now;

        var minecraft = Minecraft.getInstance();
        var model = minecraft.getModelManager().getModel(BUTTON_MODEL);
        poseStack.pushPose();
        poseStack.translate(0, -PRESS_DEPTH * mic.clientPress, 0);
        minecraft.getItemRenderer().renderModelLists(model, ItemStack.EMPTY, light,
            OverlayTexture.NO_OVERLAY, poseStack, buffers.getBuffer(Sheets.cutoutBlockSheet()));
        poseStack.popPose();
    }

    private static void renderDisplay(MicrophoneBlockEntity mic, PoseStack poseStack,
                                      MultiBufferSource buffers, int worldLight) {
        var custom = mic.displayText();
        var cells = custom != null ? custom : mic.isListening() ? "LIVE" : "MUTED";
        if (cells.length() < CELLS) {
            cells = " ".repeat((CELLS - cells.length()) / 2) + cells;
        }

        var font = Minecraft.getInstance().font;
        float cellAdvance = VcrRenderer.advance(font, VcrRenderer.ALL_SEGMENTS);
        if (cellAdvance <= 0) return;
        float pitch = cellAdvance + VcrRenderer.KERN;
        float scale = Math.min(MAX_TEXT_HEIGHT / VcrRenderer.GLYPH_VISUAL_HEIGHT,
            STRIP_WIDTH / (CELLS * pitch));

        poseStack.pushPose();
        poseStack.translate(SCREEN_CENTER_X, SCREEN_CENTER_Y, TEXT_Z);
        // Same trick as sign text: flip to face out of the front, y grows down.
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(scale, -scale, scale);
        var pose = poseStack.last().pose();

        float left = -CELLS * pitch / 2.0F;
        for (int i = 0; i < CELLS; i++) {
            float pen = left + i * pitch;
            var ghost = Component.literal(VcrRenderer.ALL_SEGMENTS).withStyle(VcrRenderer.SEGMENT_STYLE);
            font.drawInBatch(ghost, pen, VcrRenderer.TEXT_TOP, VcrRenderer.GHOST_COLOR, false, pose,
                buffers, Font.DisplayMode.NORMAL, 0, worldLight);
            char c = i < cells.length() ? cells.charAt(i) : ' ';
            if (c == ' ' || VcrRenderer.CHARSET.indexOf(c) < 0) continue;
            var lit = Component.literal(String.valueOf(c)).withStyle(VcrRenderer.SEGMENT_STYLE);
            font.drawInBatch(lit, pen, VcrRenderer.TEXT_TOP, VcrRenderer.LIT_COLOR, false, pose,
                buffers, Font.DisplayMode.POLYGON_OFFSET, 0, VcrRenderer.FULL_BRIGHT);
        }
        poseStack.popPose();
    }
}
