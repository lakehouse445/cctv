package gg.lakehouse.cctv.camera;

import gg.lakehouse.cctv.microphone.DesktopMicrophoneBlock;
import gg.lakehouse.cctv.microphone.MicrophoneBlock;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.playback.PlaybackDeckBlock;
import gg.lakehouse.cctv.playback.PlaybackDeckBlockEntity;
import gg.lakehouse.cctv.tape.DyeableCassette;
import gg.lakehouse.cctv.vcr.VcrBlock;
import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import gg.lakehouse.cctv.vcr.VcrFill;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Camera geometry for this mod's own renderer-drawn content, so the things
 * players see are the things cameras see: the cassette sitting inside a
 * playback deck, the VCR's red dot-matrix readout, the intercom's little
 * screen, and the desktop mic's mute button (depressed while muted). Text
 * quads sample the segmented font atlas exactly like the display renderers.
 */
public final class CctvAppearances {
    /** Atlas glyph order; mirrors the display renderers' charset. */
    private static final String CHARSET =
        "!\"%',./0123456789:;?ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz";
    private static final String FONT_TEXTURE = "cctv:font/segmented";
    private static final int GLYPH_W = 6;
    private static final int GLYPH_H = 9;
    private static final int LIT_COLOR = 0xFF2E14;

    // Playback deck chamber, mirroring PlaybackDeckRenderer's constants.
    private static final float SLOT_X = 0.5f;
    private static final float SLOT_Y = 10.5f / 16;
    private static final float SLOT_Z = 2.4f / 16;
    private static final float SLOT_SCALE = 0.72f;
    private static final float MODEL_CENTER_X = 0.5f;
    private static final float MODEL_CENTER_Y = 4.0f / 16;
    private static final float MODEL_CENTER_Z = 0.5f;
    private static final float REEL_LEFT_X = 4.0f / 16;
    private static final float REEL_RIGHT_X = 12.0f / 16;
    private static final float REEL_Y = 4.0f / 16;

    private CctvAppearances() {
    }

    public static boolean handles(BlockState state) {
        var block = state.getBlock();
        return block instanceof VcrBlock || block instanceof PlaybackDeckBlock
            || block instanceof MicrophoneBlock;
    }

    public static List<TexturedQuad> build(BlockState state, ServerLevel level, BlockPos pos,
                                           Function<String, TexturePixels> textures,
                                           Function<String, List<TexturedQuad>> modelQuads) {
        var out = new ArrayList<TexturedQuad>();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING)) return out;
        // Same yaw the display renderers use: facing=north is the authored pose.
        float yaw = switch (state.getValue(HorizontalDirectionalBlock.FACING)) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        var block = state.getBlock();

        if (block instanceof VcrBlock && level.getBlockEntity(pos) instanceof VcrBlockEntity vcr) {
            // Screen x 2-14, y 10-14, face at z -0.25/16; text a hair in front.
            emitDisplay(out, textures, composeVcrCells(vcr, state, level), 12,
                0.5f, 12.0f / 16, -0.021f, 11.0f / 16, 3.2f / 16, yaw);
        } else if (block instanceof PlaybackDeckBlock
            && level.getBlockEntity(pos) instanceof PlaybackDeckBlockEntity deck && deck.hasTape()) {
            buildDeckCassette(out, deck, modelQuads);
        } else if (block instanceof DesktopMicrophoneBlock
            && level.getBlockEntity(pos) instanceof MicrophoneBlockEntity mic) {
            float press = mic.isListening() ? 0 : 0.45f / 16;
            for (var quad : modelQuads.apply("cctv:block/desktop_microphone_button")) {
                out.add(copyShifted(quad, 0, -press, 0, quad.colorMul()));
            }
        } else if (block instanceof MicrophoneBlock
            && level.getBlockEntity(pos) instanceof MicrophoneBlockEntity mic) {
            var custom = mic.displayText();
            var cells = custom != null ? custom : mic.isListening() ? "LIVE" : "MUTED";
            int pad = (MicrophoneBlockEntity.DISPLAY_CELLS - cells.length()) / 2;
            if (pad > 0) cells = " ".repeat(pad) + cells;
            // Screen x 1-6, y 8-10, face at z 13.975/16.
            emitDisplay(out, textures, cells, MicrophoneBlockEntity.DISPLAY_CELLS,
                3.5f / 16, 9.0f / 16, 13.975f / 16 - 0.004f, 4.4f / 16, 1.6f / 16, yaw);
        }

        rotate(out, yaw);
        return out;
    }

    // === VCR readout (server-side twin of VcrRenderer.composeCells) ===

    private static String composeVcrCells(VcrBlockEntity vcr, BlockState state, ServerLevel level) {
        var custom = vcr.displayText();
        if (custom != null) return custom;
        long time = level.getGameTime();
        boolean blinkOn = (time / 10) % 2 == 0;
        int mode = vcr.displayMode();
        if (mode == VcrBlockEntity.DISPLAY_RECORDING || mode == VcrBlockEntity.DISPLAY_PLAYING) {
            long seconds = Math.min(Math.max(0, (time - vcr.displayStart()) / 20), 99 * 60 + 59);
            var counter = String.format("%02d:%02d", seconds / 60, seconds % 60);
            return mode == VcrBlockEntity.DISPLAY_RECORDING
                ? "REC" + (blinkOn ? "." : " ") + "   " + counter
                : "PLAY   " + counter;
        }
        boolean hasTape = state.hasProperty(VcrBlock.FILL) && state.getValue(VcrBlock.FILL) != VcrFill.EMPTY;
        if (!hasTape) return blinkOn ? "   12:00" : "";
        return "   00:00";
    }

    /**
     * Lit glyph quads on a display strip, authored facing north (front at -z,
     * viewer-rightward = -x, matching the display renderers' flipped pose).
     * Ghost dots are below camera resolution and are skipped.
     */
    private static void emitDisplay(List<TexturedQuad> out, Function<String, TexturePixels> textures,
                                    String cells, int cellCount, float centerX, float centerY, float z,
                                    float stripWidth, float maxHeight, float yawIgnored) {
        var font = textures.apply(FONT_TEXTURE);
        if (font == null || font.width() < GLYPH_W) return;
        int perRow = font.width() / GLYPH_W;
        float scale = Math.min(maxHeight / GLYPH_H, stripWidth / (cellCount * (float) GLYPH_W));
        float left = -cellCount * GLYPH_W * scale / 2;

        for (int i = 0; i < cellCount && i < cells.length(); i++) {
            char c = cells.charAt(i);
            int glyph = CHARSET.indexOf(c);
            if (c == ' ' || glyph < 0) continue;
            int gx = (glyph % perRow) * GLYPH_W;
            int gy = (glyph / perRow) * GLYPH_H;
            float u0 = gx / (float) font.width();
            float u1 = (gx + GLYPH_W) / (float) font.width();
            float v0 = gy / (float) font.height();
            float v1 = (gy + GLYPH_H) / (float) font.height();
            // Text advances viewer-right = world -x when facing north.
            float x0 = centerX - (left + i * GLYPH_W * scale);
            float x1 = centerX - (left + (i + 1) * GLYPH_W * scale);
            float top = centerY + GLYPH_H * scale / 2;
            float bottom = centerY - GLYPH_H * scale / 2;
            out.add(TexturedQuad.ofColored(
                new float[]{x0, x1, x1, x0},
                new float[]{top, top, bottom, bottom},
                new float[]{z, z, z, z},
                new float[]{u0, u1, u1, u0},
                new float[]{v0, v0, v1, v1},
                font, TexturedQuad.TINT_NONE, -1, LIT_COLOR));
        }
    }

    // === The cassette inside a playback deck ===

    private static void buildDeckCassette(List<TexturedQuad> out, PlaybackDeckBlockEntity deck,
                                          Function<String, List<TexturedQuad>> modelQuads) {
        int dye = deck.tape().getItem() instanceof DyeableCassette cassette
            ? cassette.getColor(deck.tape()) : 0xFFFFFF;
        var assembled = new ArrayList<TexturedQuad>();
        collectCassette(assembled, modelQuads.apply("cctv:item/cassette_tape"), 0, 0, dye);
        collectCassette(assembled, modelQuads.apply("cctv:item/cassette_tape_glass"), 0, 0, dye);
        var reel = modelQuads.apply("cctv:item/cassette_tape_reel");
        collectCassette(assembled, reel, REEL_LEFT_X - 0.5f, REEL_Y - 0.5f, dye);
        collectCassette(assembled, reel, REEL_RIGHT_X - 0.5f, REEL_Y - 0.5f, dye);
        // Cassette centre into the chamber, scaled like the deck renderer.
        for (var quad : assembled) {
            for (int i = 0; i < 4; i++) {
                quad.xs()[i] = SLOT_X + (quad.xs()[i] - MODEL_CENTER_X) * SLOT_SCALE;
                quad.ys()[i] = SLOT_Y + (quad.ys()[i] - MODEL_CENTER_Y) * SLOT_SCALE;
                quad.zs()[i] = SLOT_Z + (quad.zs()[i] - MODEL_CENTER_Z) * SLOT_SCALE;
            }
        }
        out.addAll(assembled);
    }

    /** Copies model quads, shifting reels to their centres and resolving the dye tint. */
    private static void collectCassette(List<TexturedQuad> out, List<TexturedQuad> quads,
                                        float dx, float dy, int dye) {
        for (var quad : quads) {
            int color = quad.tintIndex() == TexturedQuad.TINT_NONE ? quad.colorMul() : dye;
            out.add(copyShifted(quad, dx, dy, 0, color));
        }
    }

    private static TexturedQuad copyShifted(TexturedQuad quad, float dx, float dy, float dz, int colorMul) {
        var xs = new float[4];
        var ys = new float[4];
        var zs = new float[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = quad.xs()[i] + dx;
            ys[i] = quad.ys()[i] + dy;
            zs[i] = quad.zs()[i] + dz;
        }
        return TexturedQuad.ofColored(xs, ys, zs, quad.us().clone(), quad.vs().clone(),
            quad.texture(), TexturedQuad.TINT_NONE, quad.alphaOverride(), colorMul);
    }

    /** Blockstate y rotation about the block centre; north-authored content. */
    private static void rotate(List<TexturedQuad> quads, float yawDegrees) {
        if (yawDegrees == 0) return;
        var matrix = new Matrix4f()
            .translate(0.5f, 0.5f, 0.5f)
            .rotateY((float) Math.toRadians(-yawDegrees))
            .translate(-0.5f, -0.5f, -0.5f);
        var vec = new Vector4f();
        for (var quad : quads) {
            for (int i = 0; i < 4; i++) {
                vec.set(quad.xs()[i], quad.ys()[i], quad.zs()[i], 1);
                matrix.transform(vec);
                quad.xs()[i] = vec.x;
                quad.ys()[i] = vec.y;
                quad.zs()[i] = vec.z;
            }
        }
    }
}
