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
 * Draws the deck's front-panel readout: a red 12-cell dot-matrix display on
 * the screen strip at the top of the face. The font ("vcr segmented") is a
 * 6x9 dot grid per character - the last column is mostly the baked seam
 * between cells - shipped as a bitmap provider whose atlas we bake from the
 * SVG-OT source (see the font's PNG next to segment_display.json): character
 * glyphs carry only their lit dots (grayscale = dot intensity, tinted red at
 * draw time), and the full background grid, reassembled from every glyph's
 * unlit dots, sits at U+FFFD as the ghost template. The layout is pure
 * arithmetic: cell i starts at i * (advance + kern) and lit dots land
 * exactly on the ghost grid. Lua text (setDisplay) takes the panel over; otherwise recording
 * shows REC and a counter with a blinking dot, playback shows PLAY and a
 * counter, a tape at rest reads 00:00 and an empty deck blinks 12:00 like
 * every unset VCR ever. Ghost dots take the world's light; lit dots render
 * fullbright so the display reads in the dark.
 */
public class VcrRenderer implements BlockEntityRenderer<VcrBlockEntity> {
    public static final ResourceLocation FONT = new ResourceLocation(CCTV.MOD_ID, "segment_display");
    static final Style SEGMENT_STYLE = Style.EMPTY.withFont(FONT);
    /** The atlas maps the all-dots-on background grid to the replacement character. */
    static final String ALL_SEGMENTS = "\uFFFD";
    /**
     * Characters with art in the atlas. Lua can send anything; a character
     * outside this set draws as a blank cell instead of the missing-glyph box.
     */
    static final String CHARSET =
        "!\"%',./0123456789:;?ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz-";

    private static final int CELLS = VcrBlockEntity.DISPLAY_CELLS;
    // Screen element of the model: x 2-14, y 10-14, face at z -0.25/16.
    private static final float SCREEN_CENTER_X = 0.5F;
    private static final float SCREEN_CENTER_Y = 12.0F / 16.0F;
    private static final float TEXT_Z = -0.021F;
    /** Usable strip inside the screen frame. */
    private static final float STRIP_WIDTH = 11.0F / 16.0F;
    private static final float MAX_TEXT_HEIGHT = 3.2F / 16.0F;
    /** The dot grid is 9 rows tall (6 above the baseline, 3 below). */
    static final float GLYPH_VISUAL_HEIGHT = 9.0F;
    /**
     * Bitmap glyphs render with their top at y + (7 - ascent); ascent is 6,
     * so the 9-row box spans [y + 1, y + 10] and y = -5.5 centers it on 0.
     */
    static final float TEXT_TOP = -5.5F;
    /**
     * Space between cells, in font pixels. The ghost glyph's advance is 7
     * (6 dot columns plus the 1px gap Minecraft always adds to bitmap
     * glyphs); -1 cancels Minecraft's gap so cells tile at the grid pitch
     * and the font's own seam column is the only space between characters.
     */
    static final float KERN = -1.0F;

    static final int GHOST_COLOR = 0xFF2B0B08;
    static final int LIT_COLOR = 0xFFFF2E14;
    static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

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
        // The ghost glyph is always full-width; lit-dot glyphs may be narrower.
        float cellAdvance = advance(font, ALL_SEGMENTS);
        if (cellAdvance <= 0) return;
        float pitch = cellAdvance + KERN;
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
            // Ink starts at the slot edge; the glyph's own trailing gap px
            // (cancelled by the negative kern) needs no re-centering.
            float pen = left + i * pitch;
            var ghost = Component.literal(ALL_SEGMENTS).withStyle(SEGMENT_STYLE);
            font.drawInBatch(ghost, pen, TEXT_TOP, GHOST_COLOR, false, pose,
                buffers, Font.DisplayMode.NORMAL, 0, worldLight);
            char c = i < cells.length() ? cells.charAt(i) : ' ';
            if (c == ' ' || CHARSET.indexOf(c) < 0) continue;
            // The lit dots sit on the ghost: polygon offset keeps the coplanar
            // quads from dithering. Dots are opaque axis-aligned pixels, so a
            // single draw covers the ghost cleanly.
            var lit = Component.literal(String.valueOf(c)).withStyle(SEGMENT_STYLE);
            font.drawInBatch(lit, pen, TEXT_TOP, LIT_COLOR, false, pose,
                buffers, Font.DisplayMode.POLYGON_OFFSET, 0, FULL_BRIGHT);
        }
        poseStack.popPose();
    }

    /** Float glyph advance; Font.width ceils to an int, so measure 16 repeats and divide. */
    static float advance(Font font, String s) {
        return font.width(Component.literal(s.repeat(16)).withStyle(SEGMENT_STYLE)) / 16.0F;
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
