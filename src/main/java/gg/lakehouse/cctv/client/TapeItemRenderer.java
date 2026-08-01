package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.lakehouse.cctv.CCTV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Renders the tape as the 3D cassette model (with its written label and
 * dyed strip) everywhere, GUI included - the model's own gui display
 * transform frames it as the inventory icon.
 */
public class TapeItemRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ResourceLocation CASSETTE_MODEL = CassetteRenderer.CASSETTE_MODEL;

    public TapeItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        poseStack.pushPose();
        // Cancel the -0.5 origin shift ItemRenderer applied before handing off to us.
        poseStack.translate(0.5F, 0.5F, 0.5F);
        // The display transforms are authored for the right hand; the
        // left hand needs vanilla's mirroring or the cassette sits askew.
        boolean leftHand = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        CassetteRenderer.render(stack, context, leftHand, poseStack, buffers, light, overlay);
        poseStack.popPose();
    }
}
