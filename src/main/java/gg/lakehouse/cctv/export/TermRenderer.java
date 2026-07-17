package gg.lakehouse.cctv.export;

import com.mojang.blaze3d.platform.NativeImage;
import gg.lakehouse.cctv.media.TermFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Rasterises terminal frames using CC:Tweaked's own term_font atlas:
 * 6x9 glyphs on an 8x11 grid with a 1px border, in the top-left of the texture.
 */
public final class TermRenderer {
    public static final int GLYPH_W = 6;
    public static final int GLYPH_H = 9;
    private static final int CELL_W = 8;
    private static final int CELL_H = 11;
    private static final int PAD = 1;
    private static final ResourceLocation FONT = new ResourceLocation("computercraft", "textures/gui/term_font.png");

    private static volatile boolean[] fontMask;

    private TermRenderer() {
    }

    private static boolean[] fontMask() throws IOException {
        var mask = fontMask;
        if (mask != null) return mask;
        synchronized (TermRenderer.class) {
            if (fontMask == null) {
                try (var in = Minecraft.getInstance().getResourceManager().open(FONT);
                     var image = NativeImage.read(in)) {
                    int scale = Math.max(1, image.getWidth() / 256);
                    var loaded = new boolean[256 * GLYPH_W * GLYPH_H];
                    for (int ch = 0; ch < 256; ch++) {
                        int baseX = (PAD + (ch % 16) * CELL_W) * scale;
                        int baseY = (PAD + (ch / 16) * CELL_H) * scale;
                        for (int y = 0; y < GLYPH_H; y++) {
                            for (int x = 0; x < GLYPH_W; x++) {
                                int pixel = image.getPixelRGBA(baseX + x * scale, baseY + y * scale);
                                loaded[(ch * GLYPH_H + y) * GLYPH_W + x] = ((pixel >> 24) & 0xFF) > 127;
                            }
                        }
                    }
                    fontMask = loaded;
                }
            }
        }
        return fontMask;
    }

    public static BufferedImage render(TermFrame frame) throws IOException {
        var mask = fontMask();
        var image = new BufferedImage(frame.width() * GLYPH_W, frame.height() * GLYPH_H, BufferedImage.TYPE_INT_RGB);
        for (int cellY = 0; cellY < frame.height(); cellY++) {
            String text = frame.text()[cellY];
            String fgLine = frame.fg()[cellY];
            String bgLine = frame.bg()[cellY];
            for (int cellX = 0; cellX < frame.width(); cellX++) {
                int glyph = cellX < text.length() ? text.charAt(cellX) & 0xFF : ' ';
                int fg = frame.palette()[colourIndex(fgLine, cellX)];
                int bg = frame.palette()[colourIndex(bgLine, cellX)];
                for (int y = 0; y < GLYPH_H; y++) {
                    for (int x = 0; x < GLYPH_W; x++) {
                        boolean on = mask[(glyph * GLYPH_H + y) * GLYPH_W + x];
                        image.setRGB(cellX * GLYPH_W + x, cellY * GLYPH_H + y, on ? fg : bg);
                    }
                }
            }
        }
        return image;
    }

    private static int colourIndex(String line, int x) {
        if (x >= line.length()) return 0;
        int digit = Character.digit(line.charAt(x), 16);
        return digit < 0 ? 0 : digit;
    }
}
