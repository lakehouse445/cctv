package gg.lakehouse.cctv.camera;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Vanilla's bitmap font, sampled from ascii.png: the 95 printable ASCII
 * glyphs direct-index into the sheet's 16x16 grid, with vanilla's advance
 * rule — rightmost inked column plus one spacing column; a space is 4 wide.
 * Glyphs resample to 8x8 bitmasks whatever resolution the sheet has, so a
 * font resource pack on the client still reads correctly.
 */
public final class FontSheet {
    public static final int HEIGHT = 8;
    private static volatile FontSheet instance;

    private final long[] bits = new long[95];
    private final int[] advances = new int[95];

    /** The shared instance, built from {@code textures} on first success. */
    @Nullable
    public static FontSheet get(Function<String, TexturePixels> textures) {
        var local = instance;
        if (local != null) return local;
        var sheet = textures.apply("minecraft:font/ascii");
        if (sheet == null || sheet.width() < 16 || sheet.height() < 16) return null;
        local = new FontSheet(sheet);
        instance = local;
        return local;
    }

    /** The shared instance if any texture source has built it yet. */
    @Nullable
    public static FontSheet get() {
        return instance;
    }

    private FontSheet(TexturePixels sheet) {
        int cell = sheet.width() / 16;
        for (int c = 32; c <= 126; c++) {
            int index = c - 32;
            if (c == ' ') {
                advances[index] = 4;
                continue;
            }
            int gx = (c & 15) * cell;
            int gy = (c >> 4) * cell;
            int last = -1;
            for (int x = 0; x < cell; x++) {
                for (int y = 0; y < cell; y++) {
                    if (inked(sheet, gx + x, gy + y)) {
                        last = x;
                        break;
                    }
                }
            }
            if (last < 0) continue;
            int width = Math.min(8, (int) Math.ceil((last + 1) * 8.0 / cell));
            advances[index] = width + 1;
            long mask = 0;
            for (int py = 0; py < HEIGHT; py++) {
                int sy = gy + (py * 2 + 1) * cell / 16;
                for (int px = 0; px < width; px++) {
                    int sx = gx + (px * 2 + 1) * cell / 16;
                    if (inked(sheet, sx, sy)) mask |= 1L << (py * 8 + px);
                }
            }
            bits[index] = mask;
        }
    }

    private static boolean inked(TexturePixels sheet, int x, int y) {
        if (x >= sheet.width() || y >= sheet.height()) return false;
        return (sheet.argb()[y * sheet.width() + x] >>> 24) > 16;
    }

    /** Advance in font pixels, including the spacing column; 0 = not drawable. */
    public int advance(char c) {
        return c < 32 || c > 126 ? 0 : advances[c - 32];
    }

    /** Width of a whole line in font pixels, without the trailing spacing. */
    public int lineWidth(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += advance(text.charAt(i));
        return Math.max(0, total - 1);
    }

    public boolean isSet(char c, int px, int py) {
        if (c < 32 || c > 126 || px < 0 || px >= 8 || py < 0 || py >= HEIGHT) return false;
        return (bits[c - 32] >>> (py * 8 + px) & 1L) != 0;
    }
}
