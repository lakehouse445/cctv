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

    /**
     * Renders one frame as the recorded monitor's face: sized from its
     * blocks at 256 pixels per block, the terminal seated with CC's margin
     * and cell sizes for its text scale, edge backgrounds extended into the
     * margin exactly as monitors draw in-world. Falls back to the plain
     * terminal render when the recording predates monitor metadata.
     */
    public static BufferedImage render(TermFrame frame, @javax.annotation.Nullable TermFrame.MonitorInfo monitor)
        throws IOException {
        if (monitor == null) return render(frame);
        var mask = fontMask();
        // 256 px per block; CC: glass = blocks - 2*(2/16), margin 0.5/16,
        // one font pixel = textScaleHalf/128 blocks -> 2*half px, exactly.
        int fontPx = 2 * Math.max(1, monitor.textScaleHalf());
        int cellW = GLYPH_W * fontPx;
        int cellH = GLYPH_H * fontPx;
        int margin = 8;
        int outW = monitor.blocksWide() * 256 - 64;
        int outH = monitor.blocksTall() * 256 - 64;
        var image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < outH; py++) {
            int cy = Math.floorDiv(py - margin, cellH);
            int clampedY = Math.min(frame.height() - 1, Math.max(0, cy));
            String text = frame.text()[clampedY];
            String fgLine = frame.fg()[clampedY];
            String bgLine = frame.bg()[clampedY];
            for (int px = 0; px < outW; px++) {
                int cx = Math.floorDiv(px - margin, cellW);
                int clampedX = Math.min(frame.width() - 1, Math.max(0, cx));
                int bg = frame.palette()[15 - colourIndex(bgLine, clampedX)] & 0xFFFFFF;
                int color = bg;
                if (cx == clampedX && cy == clampedY && px >= margin && py >= margin) {
                    int glyph = clampedX < text.length() ? text.charAt(clampedX) & 0xFF : ' ';
                    int gx = (px - margin - cx * cellW) / fontPx;
                    int gy = (py - margin - cy * cellH) / fontPx;
                    if (mask[(glyph * GLYPH_H + gy) * GLYPH_W + gx]) {
                        color = frame.palette()[15 - colourIndex(fgLine, clampedX)] & 0xFFFFFF;
                    }
                }
                image.setRGB(px, py, color);
            }
        }
        return image;
    }

    /**
     * Renders one frame at full color fidelity — each cell uses its frame's
     * exact palette entries. Captured palettes are stored in CC's internal
     * array order, which runs BLACK(0)..WHITE(15) — the reverse of blit's
     * hex digits where '0' is white — so a digit d reads palette[15 - d].
     */
    public static BufferedImage render(TermFrame frame) throws IOException {
        var mask = fontMask();
        var image = new BufferedImage(frame.width() * GLYPH_W, frame.height() * GLYPH_H, BufferedImage.TYPE_INT_RGB);
        for (int cellY = 0; cellY < frame.height(); cellY++) {
            String text = frame.text()[cellY];
            String fgLine = frame.fg()[cellY];
            String bgLine = frame.bg()[cellY];
            for (int cellX = 0; cellX < frame.width(); cellX++) {
                int glyph = cellX < text.length() ? text.charAt(cellX) & 0xFF : ' ';
                int fg = frame.palette()[15 - colourIndex(fgLine, cellX)] & 0xFFFFFF;
                int bg = frame.palette()[15 - colourIndex(bgLine, cellX)] & 0xFFFFFF;
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
