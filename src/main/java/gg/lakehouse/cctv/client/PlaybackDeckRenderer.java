package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.playback.DeckState;
import gg.lakehouse.cctv.playback.PlaybackDeckBlock;
import gg.lakehouse.cctv.playback.PlaybackDeckBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * Draws the deck's animated pieces: the inserted cassette in the chamber
 * (reels spinning with the transport) and the four front-panel buttons, the
 * latched one sunk into the panel until eject or another press. Buttons are
 * the deck model's own elements, split out so they can move.
 */
public class PlaybackDeckRenderer implements BlockEntityRenderer<PlaybackDeckBlockEntity> {
    /** Indexed by DeckButton ordinal: PLAY, PAUSE, STOP, REWIND, FAST_FORWARD. */
    public static final ResourceLocation[] BUTTON_MODELS = {
        new ResourceLocation(CCTV.MOD_ID, "block/playback_deck_button_play"),
        new ResourceLocation(CCTV.MOD_ID, "block/playback_deck_button_pause"),
        new ResourceLocation(CCTV.MOD_ID, "block/playback_deck_button_stop"),
        new ResourceLocation(CCTV.MOD_ID, "block/playback_deck_button_rewind"),
        new ResourceLocation(CCTV.MOD_ID, "block/playback_deck_button_ff")
    };
    /** Buttons protrude 2/16; press them most of the way in. */
    private static final float PRESS_DEPTH = 1.25F / 16.0F;

    // Chamber cavity in the deck model (facing north): x 2-14, y 7-14, z 0-4,
    // spindles at z 3-4. Cassette centre goes mid-chamber, front behind the
    // window frame. All in 0-1 block space.
    private static final float SLOT_X = 0.5F;
    private static final float SLOT_Y = 10.5F / 16.0F;
    private static final float SLOT_Z = 2.4F / 16.0F;
    /** 16-wide cassette into a 12-wide chamber, with a little clearance. */
    private static final float SLOT_SCALE = 0.72F;
    // Cassette model centre (spans x 0-16, y 0-8, z 7-9 in model units).
    private static final float MODEL_CENTER_X = 0.5F;
    private static final float MODEL_CENTER_Y = 4.0F / 16.0F;
    private static final float MODEL_CENTER_Z = 0.5F;

    public PlaybackDeckRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(PlaybackDeckBlockEntity deck, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        var state = deck.getBlockState();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING)) return;

        var facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int yaw = switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        // Inside the deck the local light is 0 and everything renders black;
        // light it like the face the window looks out of.
        int light = packedLight;
        float reelAngle = 0;
        int pressed = deck.pressedButton() >= 0 && deck.pressedButton() < BUTTON_MODELS.length
            ? deck.pressedButton() : -1;
        if (deck.getLevel() != null) {
            light = LevelRenderer.getLightColor(deck.getLevel(), deck.getBlockPos().relative(facing));
            long now = deck.getLevel().getGameTime();
            // Playing spins right (clockwise from the front), rewinding left
            // and faster. Period chosen so the modulo wraps at exactly 360.
            var deckState = state.hasProperty(PlaybackDeckBlock.STATE)
                ? state.getValue(PlaybackDeckBlock.STATE) : DeckState.EMPTY;
            if (deckState == DeckState.PLAYING) {
                reelAngle = (now % 60 + partialTick) * 6.0F;
            } else if (deckState == DeckState.REWINDING) {
                reelAngle = -(now % 18 + partialTick) * 20.0F;
            }
        }

        poseStack.pushPose();
        // Match the blockstate y rotation (clockwise from above) about the block centre.
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.translate(-0.5, 0, -0.5);

        renderButtons(poseStack, buffers, light, pressed);
        if (deck.hasTape()) {
            poseStack.pushPose();
            // Cassette centre into the chamber, then centre the model on it.
            // CassetteRenderer shifts by (-0.5,-0.5,-0.5) internally, so the
            // compensation is (0.5 - centre) on each axis.
            poseStack.translate(SLOT_X, SLOT_Y, SLOT_Z);
            poseStack.scale(SLOT_SCALE, SLOT_SCALE, SLOT_SCALE);
            poseStack.translate(0.5F - MODEL_CENTER_X, 0.5F - MODEL_CENTER_Y, 0.5F - MODEL_CENTER_Z);
            // NONE context: no camera transform is applied, geometry stays in 0-1 space.
            CassetteRenderer.render(deck.tape(), ItemDisplayContext.NONE, false,
                poseStack, buffers, light, OverlayTexture.NO_OVERLAY, reelAngle);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private static void renderButtons(PoseStack poseStack, MultiBufferSource buffers, int light, int pressed) {
        var minecraft = Minecraft.getInstance();
        var itemRenderer = minecraft.getItemRenderer();
        var buffer = buffers.getBuffer(Sheets.cutoutBlockSheet());
        for (int i = 0; i < BUTTON_MODELS.length; i++) {
            var model = minecraft.getModelManager().getModel(BUTTON_MODELS[i]);
            poseStack.pushPose();
            if (i == pressed) poseStack.translate(0, 0, PRESS_DEPTH);
            itemRenderer.renderModelLists(model, ItemStack.EMPTY, light, OverlayTexture.NO_OVERLAY,
                poseStack, buffer);
            poseStack.popPose();
        }
    }
}
