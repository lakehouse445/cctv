package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.tape.TapeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;

/**
 * Renders the compact cassette as a flat sprite in GUIs (base, dyeable
 * colour strip, label - the strip layer carries tint index 1) and as the
 * 3D model everywhere else: hand, ground, item frames. An anvil rename
 * writes on the white top band of the label stickers, VHS-style; a "/"
 * in the name splits it into side A and side B ("ROAD TRIP/CHILL MIX"),
 * otherwise both sides carry the same text.
 */
public class CompactCassetteItemRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ResourceLocation SPRITE_MODEL = new ResourceLocation(CCTV.MOD_ID, "item/compact_cassette_sprite");
    public static final ResourceLocation MODEL_3D = new ResourceLocation(CCTV.MOD_ID, "item/compact_cassette_3d");
    public static final ResourceLocation GLASS_MODEL = new ResourceLocation(CCTV.MOD_ID, "item/compact_cassette_glass");

    // Label sticker geometry in model units (see models/item/compact_cassette_3d.json):
    // stickers at x 2.95-12.95, white band y 5-7, A side at z 6.95, B side at z 9.05.
    private static final float LABEL_CENTER_X = 7.95F;
    private static final float LABEL_TEXT_CENTER_Y = 6.0F;
    private static final float LABEL_A_TEXT_Z = 6.9F;   // a hair in front of the A sticker, faces -z
    private static final float LABEL_B_TEXT_Z = 9.1F;   // a hair in front of the B sticker, faces +z
    private static final float LABEL_USABLE_WIDTH = 8.0F;
    private static final float LABEL_TEXT_HEIGHT = 1.9F;

    public CompactCassetteItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        poseStack.pushPose();
        // Cancel the -0.5 origin shift ItemRenderer applied before handing off to us.
        poseStack.translate(0.5F, 0.5F, 0.5F);
        var minecraft = Minecraft.getInstance();
        if (context == ItemDisplayContext.GUI) {
            var sprite = minecraft.getModelManager().getModel(SPRITE_MODEL);
            minecraft.getItemRenderer().render(stack, context, false, poseStack, buffers, light, overlay, sprite);
        } else {
            var model = minecraft.getModelManager().getModel(MODEL_3D);
            // The left-hand flag matters: the model's display transforms are
            // authored for the right hand and rely on vanilla's left-hand
            // mirroring. Skipping it once made the unmirrored first-person
            // pose show side B on its own, and the deliberate flip below
            // silently rotated it back to side A.
            boolean leftHand = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            model = ForgeHooksClient.handleCameraTransforms(poseStack, model, context, leftHand);
            poseStack.translate(-0.5F, -0.5F, -0.5F);
            if (isOffhand(context)) {
                // The offhand holds the cassette flipped, side B out.
                poseStack.translate(0.5F, 0.0F, 0.5F);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.translate(-0.5F, 0.0F, -0.5F);
            }
            var buffer = ItemRenderer.getFoilBufferDirect(buffers,
                Sheets.cutoutBlockSheet(), true, stack.hasFoil());
            minecraft.getItemRenderer().renderModelLists(model, stack, light, overlay, poseStack, buffer);
            // Window faces blend on the translucent-cull sheet so the reels
            // show through; cutout would render their half-alpha pixels solid.
            var glass = minecraft.getModelManager().getModel(GLASS_MODEL);
            var translucent = ItemRenderer.getFoilBufferDirect(buffers,
                Sheets.translucentCullBlockSheet(), true, stack.hasFoil());
            minecraft.getItemRenderer().renderModelLists(glass, stack, light, overlay, poseStack, translucent);
            renderLabel(stack, poseStack, buffers, light);
        }
        poseStack.popPose();
    }

    /**
     * True when this render is the cassette sitting in an offhand. Every
     * render path, vanilla or modded, passes the display context through
     * unchanged, and context left/right plus the main-hand option fully
     * determines the hand - no stack comparison needed (slot probing flipped
     * content-equal cassettes in item frames, and the stale stacks vanilla
     * renders during the swap animation made hands flicker). Non-hand
     * contexts (GUI, ground, frames, shelves) always show side A. Other
     * players are assumed right-handed; the renderer never learns whose
     * hands these are.
     */
    private static boolean isOffhand(ItemDisplayContext context) {
        boolean left = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        boolean right = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if (!left && !right) return false;
        var player = Minecraft.getInstance().player;
        var mainArm = player != null ? player.getMainArm() : net.minecraft.world.entity.HumanoidArm.RIGHT;
        return mainArm == net.minecraft.world.entity.HumanoidArm.RIGHT ? left : right;
    }

    private static void renderLabel(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (!stack.hasCustomHoverName()) return;
        var name = stack.getHoverName().getString();
        if (name.isBlank()) return;

        // "A text/B text" labels each side on its own; no slash writes the
        // same text on both stickers.
        int slash = name.indexOf('/');
        var sideA = slash < 0 ? name : name.substring(0, slash).trim();
        var sideB = slash < 0 ? name : name.substring(slash + 1).trim();
        drawSide(sideA, true, poseStack, buffers, light);
        drawSide(sideB, false, poseStack, buffers, light);
    }

    private static void drawSide(String text, boolean sideA, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (text.isBlank()) return;
        if (text.length() > TapeItem.MAX_LABEL_CHARS) {
            text = text.substring(0, TapeItem.MAX_LABEL_CHARS);
        }
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text);
        if (textWidth == 0) return;

        // Fit the text inside the white band: cap by height, shrink further for long names.
        float scale = Math.min(LABEL_TEXT_HEIGHT / 9.0F, LABEL_USABLE_WIDTH / textWidth) / 16.0F;

        poseStack.pushPose();
        poseStack.translate(LABEL_CENTER_X / 16.0F, LABEL_TEXT_CENTER_Y / 16.0F,
            (sideA ? LABEL_A_TEXT_Z : LABEL_B_TEXT_Z) / 16.0F);
        if (sideA) poseStack.mulPose(Axis.YP.rotationDegrees(180.0F)); // A sticker faces -z
        poseStack.scale(scale, -scale, scale);
        font.drawInBatch(text, -textWidth / 2.0F, -4.5F, 0xFF101010, false,
            poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        poseStack.popPose();
    }
}
