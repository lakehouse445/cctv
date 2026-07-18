package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
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
        var minecraft = Minecraft.getInstance();
        var itemRenderer = minecraft.getItemRenderer();
        var solid = minecraft.getModelManager().getModel(CASSETTE_MODEL);
        var glass = minecraft.getModelManager().getModel(CASSETTE_GLASS_MODEL);
        poseStack.pushPose();
        solid = ForgeHooksClient.handleCameraTransforms(poseStack, solid, context, leftHand);
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        // Solid geometry first on the cutout pass: correct depth from every
        // angle, double-sided. The windowed front face lives in its own model
        // and renders translucent afterwards, so the glass keeps its tint while
        // blending over an already-complete interior.
        var cutout = buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
        itemRenderer.renderModelLists(solid, stack, light, overlay, poseStack, cutout);
        var translucent = ItemRenderer.getFoilBufferDirect(buffers,
            RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS), true, stack.hasFoil());
        itemRenderer.renderModelLists(glass, stack, light, overlay, poseStack, translucent);
        renderLabel(stack, poseStack, buffers, light);
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
