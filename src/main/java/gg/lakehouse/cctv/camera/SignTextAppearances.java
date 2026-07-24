package gg.lakehouse.cctv.camera;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign text on camera: the sign's front lines rasterize into a transparent
 * texture with the nametag pixel font, hung as a thin quad on the board.
 * Placement is by sign kind (standing, wall, hanging) in block space, so it
 * matches the geometry-pack boards the block pipeline emits.
 */
public final class SignTextAppearances {
    private static final Map<String, TexturePixels> TEXT_CACHE = new ConcurrentHashMap<>();
    /** Texture width in font pixels: one block of board at sign scale. */
    private static final int TEXT_WIDTH = 96;
    /** Vanilla's sign line height in font pixels. */
    private static final int LINE_PITCH = 10;
    /** Texture rows: four lines plus a 1-pixel top margin. */
    private static final int TEXT_HEIGHT = 4 * LINE_PITCH + 1;

    private SignTextAppearances() {
    }

    /** Text quads for a sign block, or an empty list. {@code boardQuads} is the sign's static geometry. */
    public static List<TexturedQuad> build(BlockState state, ServerLevel level, BlockPos pos,
                                           java.util.function.Function<String, TexturePixels> textures,
                                           List<TexturedQuad> boardQuads) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return List.of();
        var font = FontSheet.get(textures);
        if (font == null) return List.of();
        var lines = new String[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            lines[i] = sign.getFrontText().getMessage(i, false).getString();
            any = any || !lines[i].isBlank();
        }
        if (!any) return List.of();
        int textColor = sign.getFrontText().getColor().getTextColor() & 0xFFFFFF;
        int color = textColor == 0 ? 0x1F1F1F : textColor;

        // The text quad must lie flat on the board's face and match its
        // area exactly, or viewing at an angle parallaxes the text off its
        // line. Vertical layout copies vanilla: sign text draws in
        // 1/96-block pixels with a 10-pixel line pitch (9 on hanging
        // signs), and the first line starts 1/24 of a block below the
        // board's top edge. The board top is measured from the emitted
        // geometry, so the text stays seated even if the board's seat moves.
        var block = state.getBlock();
        float yaw;
        float centerY;
        float radius;
        float pitch;
        int maxLineWidth;
        if (block instanceof StandingSignBlock || block instanceof WallSignBlock) {
            boolean standing = block instanceof StandingSignBlock;
            if (standing) {
                yaw = state.getValue(BlockStateProperties.ROTATION_16) * 22.5f;
                radius = 0.044f;
            } else {
                yaw = state.getValue(WallSignBlock.FACING).toYRot();
                // The board hugs the wall behind: its face sits toward the back.
                radius = -0.398f;
            }
            pitch = 10f / 96;
            maxLineWidth = 90;
            float quadHeight = pitch * TEXT_HEIGHT / LINE_PITCH;
            float boardTop = maxY(boardQuads, standing ? 1.0833f : 0.78f);
            float glyphTop = boardTop - 1f / 24;
            centerY = glyphTop + quadHeight / TEXT_HEIGHT - quadHeight / 2;
        } else if (block instanceof CeilingHangingSignBlock) {
            yaw = state.getValue(BlockStateProperties.ROTATION_16) * 22.5f;
            centerY = 0.32f;
            radius = 0.045f;
            pitch = 9f / 96;
            maxLineWidth = 60;
        } else if (block instanceof WallHangingSignBlock) {
            yaw = state.getValue(WallHangingSignBlock.FACING).toYRot();
            centerY = 0.32f;
            radius = 0.045f;
            pitch = 9f / 96;
            maxLineWidth = 60;
        } else {
            return List.of();
        }
        // Square font pixels: world size follows the line pitch.
        float pixel = pitch / LINE_PITCH;
        float height = pixel * TEXT_HEIGHT;
        float width = pixel * TEXT_WIDTH;

        int lineCap = maxLineWidth;
        var texture = TEXT_CACHE.computeIfAbsent(
            String.join("\n", lines) + "#" + color + "#" + lineCap,
            key -> rasterize(lines, color, lineCap, font));

        var out = new ArrayList<TexturedQuad>(1);
        var matrix = new Matrix4f()
            .translate(0.5f, centerY, 0.5f)
            .rotateY((float) Math.toRadians(-yaw));
        // Facing +z in local space (yaw 0 = south). A viewer at +z looking
        // back at the plane has -x on their left, so u runs -x to +x.
        var corners = new float[][]{
            {-width / 2, height / 2, radius, 0, 0},
            {width / 2, height / 2, radius, 1, 0},
            {width / 2, -height / 2, radius, 1, 1},
            {-width / 2, -height / 2, radius, 0, 1}
        };
        var xs = new float[4];
        var ys = new float[4];
        var zs = new float[4];
        var us = new float[4];
        var vs = new float[4];
        var position = new Vector3f();
        for (int i = 0; i < 4; i++) {
            position.set(corners[i][0], corners[i][1], corners[i][2]);
            matrix.transformPosition(position);
            xs[i] = position.x;
            ys[i] = position.y;
            zs[i] = position.z;
            us[i] = corners[i][3];
            vs[i] = corners[i][4];
        }
        out.add(TexturedQuad.of(xs, ys, zs, us, vs, texture, TexturedQuad.TINT_NONE, -1));
        return out;
    }

    /** The highest vertex of the sign's emitted geometry: the board's top edge. */
    private static float maxY(List<TexturedQuad> quads, float fallback) {
        float max = Float.NEGATIVE_INFINITY;
        for (var quad : quads) {
            for (float y : quad.ys()) max = Math.max(max, y);
        }
        return max == Float.NEGATIVE_INFINITY ? fallback : max;
    }

    /** 4 centered lines, clipped to the sign's line width, in the real font. */
    private static TexturePixels rasterize(String[] lines, int color, int maxLineWidth, FontSheet font) {
        var argb = new int[TEXT_WIDTH * TEXT_HEIGHT];
        int paint = 0xFF000000 | color;
        for (int line = 0; line < 4; line++) {
            var text = lines[line] == null ? "" : lines[line];
            while (!text.isEmpty() && font.lineWidth(text) > maxLineWidth) {
                text = text.substring(0, text.length() - 1);
            }
            int pen = (TEXT_WIDTH - font.lineWidth(text)) / 2;
            int startY = 1 + line * LINE_PITCH;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int advance = font.advance(c);
                if (advance == 0) continue;
                for (int gy = 0; gy < FontSheet.HEIGHT; gy++) {
                    for (int gx = 0; gx < advance - 1; gx++) {
                        if (!font.isSet(c, gx, gy)) continue;
                        int px = pen + gx;
                        int py = startY + gy;
                        if (px >= 0 && px < TEXT_WIDTH && py < TEXT_HEIGHT) {
                            argb[py * TEXT_WIDTH + px] = paint;
                        }
                    }
                }
                pen += advance;
            }
        }
        return new TexturePixels(argb, TEXT_WIDTH, TEXT_HEIGHT);
    }
}
