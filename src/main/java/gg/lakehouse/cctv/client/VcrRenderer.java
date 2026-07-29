package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.vcr.VcrBlock;
import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import gg.lakehouse.cctv.vcr.VcrFill;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import org.joml.Matrix4f;

/**
 * Draws the deck's front-panel readout: a red 12-cell segment display on the
 * screen strip at the top of the face. The font ("38-Segment Display" by
 * PhuWorks, CC BY 3.0; our copy re-centers each glyph's ink in its cell) is
 * strictly monospaced - every glyph has advance 609/2000em - so the layout is
 * pure arithmetic:
 * cell i starts at i * (advance + kern) and lit glyphs land exactly on the
 * ghost template, which is the font's all-38-segments glyph at U+FFFD. Lua
 * text (setDisplay) takes the panel over; otherwise recording shows REC and
 * a counter with a blinking dot, playback shows PLAY and a counter, a tape
 * at rest reads 00:00 and an empty deck blinks 12:00 like every unset VCR
 * ever. Ghost segments take the world's light; lit segments render
 * fullbright so the display reads in the dark.
 */
public class VcrRenderer implements BlockEntityRenderer<VcrBlockEntity> {
    public static final ResourceLocation FONT = new ResourceLocation(CCTV.MOD_ID, "segment_display");
    private static final Style SEGMENT_STYLE = Style.EMPTY.withFont(FONT);
    /** The font maps its every-segment-on glyph to the replacement character. */
    private static final String ALL_SEGMENTS = "\uFFFD";

    private static final int CELLS = VcrBlockEntity.DISPLAY_CELLS;
    // Screen element of the model: x 1-15, y 12-15, face at z -0.25/16.
    private static final float SCREEN_CENTER_X = 0.5F;
    private static final float SCREEN_CENTER_Y = 13.5F / 16.0F;
    private static final float TEXT_Z = -0.021F;
    /** Usable strip inside the screen frame. */
    private static final float STRIP_WIDTH = 13.0F / 16.0F;
    private static final float MAX_TEXT_HEIGHT = 2.4F / 16.0F;
    /**
     * Glyph cell height at provider size 32: the font's full ascent.
     * Minecraft's TTF loader anchors glyphs so a full-ascent glyph spans
     * exactly [y, y + size], so half the size below centers the line on y=0.
     */
    private static final float GLYPH_VISUAL_HEIGHT = 32.0F;
    private static final float TEXT_TOP = -16.0F;
    /** Space between cells, in font pixels; the glyph advance is 19.5 of them. */
    private static final float KERN = 2.8F;

    private static final int GHOST_COLOR = 0xFF2B0B08;
    private static final int LIT_COLOR = 0xFFFF2E14;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public VcrRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VcrBlockEntity vcr, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        var state = vcr.getBlockState();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING)) return;
        var facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int yaw = switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };

        long time = 0;
        int worldLight = packedLight;
        if (vcr.getLevel() != null) {
            time = vcr.getLevel().getGameTime();
            // The readout sits on a full cube; light it like the space it faces.
            worldLight = LevelRenderer.getLightColor(vcr.getLevel(), vcr.getBlockPos().relative(facing));
        }
        boolean blinkOn = (time / 10) % 2 == 0;
        boolean hasTape = state.hasProperty(VcrBlock.FILL) && state.getValue(VcrBlock.FILL) != VcrFill.EMPTY;
        String cells = composeCells(vcr, hasTape, blinkOn, time);

        var font = Minecraft.getInstance().font;
        float digitAdvance = advance(font, '8');
        if (digitAdvance <= 0) return;
        float pitch = digitAdvance + KERN;
        float scale = Math.min(MAX_TEXT_HEIGHT / GLYPH_VISUAL_HEIGHT, STRIP_WIDTH / (CELLS * pitch));

        poseStack.pushPose();
        // Into model-local space (front facing north), like the blockstate rotation.
        poseStack.translate(0.5, 0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.translate(-0.5, 0, -0.5);
        poseStack.translate(SCREEN_CENTER_X, SCREEN_CENTER_Y, TEXT_Z);
        // Same trick as sign text: flip to face out of the front, y grows down.
        poseStack.mulPose(Axis.YP.rotationDegrees(180));
        poseStack.scale(scale, -scale, scale);
        Matrix4f pose = poseStack.last().pose();

        float left = -CELLS * pitch / 2.0F;
        for (int i = 0; i < CELLS; i++) {
            float pen = left + i * pitch + KERN / 2.0F;
            var ghost = Component.literal(ALL_SEGMENTS).withStyle(SEGMENT_STYLE);
            font.drawInBatch(ghost, pen, TEXT_TOP, GHOST_COLOR, false, pose,
                buffers, Font.DisplayMode.NORMAL, 0, worldLight);
            char c = i < cells.length() ? cells.charAt(i) : ' ';
            if (c == ' ') continue;
            // The lit glyph sits on the ghost: polygon offset keeps the coplanar
            // quads from dithering, and the double draw compounds the few
            // antialiased pixels (diagonal segment edges) to near-opaque so the
            // ghost cannot bleed through them. Axis-aligned edges are already
            // pixel-exact because the font is fitted to the 32px raster grid.
            var lit = Component.literal(String.valueOf(c)).withStyle(SEGMENT_STYLE);
            font.drawInBatch(lit, pen, TEXT_TOP, LIT_COLOR, false, pose,
                buffers, Font.DisplayMode.POLYGON_OFFSET, 0, FULL_BRIGHT);
            font.drawInBatch(lit, pen, TEXT_TOP, LIT_COLOR, false, pose,
                buffers, Font.DisplayMode.POLYGON_OFFSET, 0, FULL_BRIGHT);
        }
        poseStack.popPose();
    }

    /** Float glyph advance; Font.width ceils to an int, so measure 16 repeats and divide. */
    private static float advance(Font font, char c) {
        return font.width(Component.literal(String.valueOf(c).repeat(16)).withStyle(SEGMENT_STYLE)) / 16.0F;
    }

    /** The 12 characters on the panel right now. */
    private static String composeCells(VcrBlockEntity vcr, boolean hasTape, boolean blinkOn, long time) {
        var custom = vcr.displayText();
        if (custom != null) return custom;
        int mode = vcr.displayMode();
        if (mode == VcrBlockEntity.DISPLAY_RECORDING || mode == VcrBlockEntity.DISPLAY_PLAYING) {
            long seconds = Math.min(Math.max(0, (time - vcr.displayStart()) / 20), 99 * 60 + 59);
            var counter = String.format("%02d:%02d", seconds / 60, seconds % 60);
            return mode == VcrBlockEntity.DISPLAY_RECORDING
                ? "REC" + (blinkOn ? "." : " ") + "   " + counter
                : "PLAY   " + counter;
        }
        if (!hasTape) return blinkOn ? "   12:00" : "";
        return "   00:00";
    }
}
