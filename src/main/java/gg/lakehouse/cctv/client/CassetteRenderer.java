package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;

/**
 * Draws the 3D cassette model plus the tape's written label: an anvil rename
 * renders in black on the white label sticker, sized to fit.
 */
public final class CassetteRenderer {
    public static final ResourceLocation CASSETTE_MODEL = new ResourceLocation(CCTV.MOD_ID, "item/cassette_tape");
    public static final ResourceLocation CASSETTE_GLASS_MODEL = new ResourceLocation(CCTV.MOD_ID, "item/cassette_tape_glass");
    public static final ResourceLocation CASSETTE_REEL_MODEL = new ResourceLocation(CCTV.MOD_ID, "item/cassette_tape_reel");

    // Reel centres in 0-1 model space (left at 4,4 and right at 12,4 in model units).
    private static final float REEL_LEFT_X = 4.0F / 16.0F;
    private static final float REEL_RIGHT_X = 12.0F / 16.0F;
    private static final float REEL_Y = 4.0F / 16.0F;

    // Label sticker geometry in model units (see models/item/cassette_tape.json).
    private static final float LABEL_CENTER_X = 8.0F;
    private static final float LABEL_TEXT_CENTER_Y = 4.6F; // upper part; the red stripe owns the bottom
    private static final float LABEL_TEXT_Z = 6.9F;        // a hair in front of the sticker at z=6.95
    private static final float LABEL_USABLE_WIDTH = 5.4F;
    private static final float LABEL_TEXT_HEIGHT = 1.9F;

    private CassetteRenderer() {
    }

    public static void render(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
                              MultiBufferSource buffers, int light, int overlay) {
        render(stack, context, leftHand, poseStack, buffers, light, overlay, 0);
    }

    /**
     * @param reelAngle spin of the reels in degrees; positive reads clockwise
     *                  (playing) to someone looking at the label, negative
     *                  counterclockwise (rewinding).
     */
    public static void render(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack poseStack,
                              MultiBufferSource buffers, int light, int overlay, float reelAngle) {
        var minecraft = Minecraft.getInstance();
        var itemRenderer = minecraft.getItemRenderer();
        var solid = minecraft.getModelManager().getModel(CASSETTE_MODEL);
        var glass = minecraft.getModelManager().getModel(CASSETTE_GLASS_MODEL);
        var reel = minecraft.getModelManager().getModel(CASSETTE_REEL_MODEL);
        poseStack.pushPose();
        ForgeHooksClient.handleCameraTransforms(poseStack, solid, context, leftHand);
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        // Opaque shell (with its own back faces) on the culled cutout sheet;
        // the front window on the translucent-cull sheet. Both are fixed
        // buffers in RenderBuffers, flushed cutout-then-translucent, so the
        // interior always has depth before the glass blends over it. (Plain
        // entityTranslucent is NOT a fixed buffer and flushes early, which
        // made everything behind the window vanish.)
        var cutout = ItemRenderer.getFoilBufferDirect(buffers,
            Sheets.cutoutBlockSheet(), true, stack.hasFoil());
        itemRenderer.renderModelLists(solid, stack, light, overlay, poseStack, cutout);
        renderReel(itemRenderer, reel, stack, light, overlay, poseStack, cutout, REEL_LEFT_X, reelAngle);
        renderReel(itemRenderer, reel, stack, light, overlay, poseStack, cutout, REEL_RIGHT_X, reelAngle);
        var translucent = ItemRenderer.getFoilBufferDirect(buffers,
            Sheets.translucentCullBlockSheet(), true, stack.hasFoil());
        itemRenderer.renderModelLists(glass, stack, light, overlay, poseStack, translucent);
        renderLabel(stack, poseStack, buffers, light);
        poseStack.popPose();
    }

    private static void renderReel(ItemRenderer itemRenderer, BakedModel model,
                                   ItemStack stack, int light, int overlay, PoseStack poseStack,
                                   VertexConsumer buffer, float centerX, float angle) {
        poseStack.pushPose();
        poseStack.translate(centerX, REEL_Y, 0);
        poseStack.mulPose(Axis.ZP.rotationDegrees(angle));
        poseStack.translate(-0.5F, -0.5F, 0);
        itemRenderer.renderModelLists(model, stack, light, overlay, poseStack, buffer);
        poseStack.popPose();
    }

    private static void renderLabel(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (!stack.hasCustomHoverName()) return;
        var text = stack.getHoverName().getString();
        if (text.isBlank()) return;
        if (text.length() > gg.lakehouse.cctv.tape.TapeItem.MAX_LABEL_CHARS) {
            text = text.substring(0, gg.lakehouse.cctv.tape.TapeItem.MAX_LABEL_CHARS);
        }

        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text);
        if (textWidth == 0) return;

        // Fit the text inside the sticker: cap by height, shrink further for long names.
        float scale = Math.min(LABEL_TEXT_HEIGHT / 9.0F, LABEL_USABLE_WIDTH / textWidth) / 16.0F;

        poseStack.pushPose();
        poseStack.translate(LABEL_CENTER_X / 16.0F, LABEL_TEXT_CENTER_Y / 16.0F, LABEL_TEXT_Z / 16.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F)); // sticker faces -z
        poseStack.scale(scale, -scale, scale);
        font.drawInBatch(text, -textWidth / 2.0F, -4.5F, 0xFF101010, false,
            poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        poseStack.popPose();
    }
}
