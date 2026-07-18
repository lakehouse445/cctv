package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeLabelState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CCTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientForgeEvents {
    private ClientForgeEvents() {
    }

    @SubscribeEvent
    public static void onAnvilScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AnvilScreen screen)) return;
        if (!TapeLabelState.anvilNameTooLong) return;
        if (!(screen.getMenu().getSlot(0).getItem().getItem() instanceof TapeItem)) return;
        // Vanilla only draws its cost text when the output exists; a too-long
        // name always clears the output, so this also prevents overlap.
        if (screen.getMenu().getSlot(2).hasItem()) return;

        var font = Minecraft.getInstance().font;
        var text = Component.translatable("gui.cctv.label_too_long");
        int x = screen.getGuiLeft() + screen.getXSize() - 8 - font.width(text);
        int y = screen.getGuiTop() + 69;
        event.getGuiGraphics().drawString(font, text, x, y, 0xFF6060);
    }

    /**
     * First-person tape rendering: the cassette is posed by the model's own
     * firstperson display transform - independent of the third-person pose.
     */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        var stack = event.getItemStack();
        if (!(stack.getItem() instanceof TapeItem)) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;

        event.setCanceled(true);
        var arm = event.getHand() == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        renderTapeHold(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(),
            stack, arm, event.getEquipProgress(), event.getSwingProgress());
    }

    private static void renderTapeHold(PoseStack poseStack, MultiBufferSource buffers, int light,
                                       ItemStack stack, HumanoidArm arm, float equipProgress, float swingProgress) {
        float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;

        // The cassette, going through vanilla's first-person item transforms so
        // the model's firstperson display values mean exactly what they mean in
        // Blockbench's preview.
        poseStack.pushPose();
        float swingSqrt = Mth.sqrt(swingProgress);
        poseStack.translate(
            side * (-0.4F * Mth.sin(swingSqrt * (float) Math.PI)),
            0.2F * Mth.sin(swingSqrt * ((float) Math.PI * 2.0F)),
            -0.2F * Mth.sin(swingProgress * (float) Math.PI));
        poseStack.translate(side * 0.56F, -0.52F + equipProgress * -0.72F, -0.72F);

        float attackSwing = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
        poseStack.mulPose(Axis.YP.rotationDegrees(side * (45.0F + attackSwing * -20.0F)));
        float attackLift = Mth.sin(swingSqrt * (float) Math.PI);
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * attackLift * -20.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(attackLift * -80.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(side * -45.0F));

        var context = arm == HumanoidArm.RIGHT
            ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
            : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        CassetteRenderer.render(stack, context, arm == HumanoidArm.LEFT,
            poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}
