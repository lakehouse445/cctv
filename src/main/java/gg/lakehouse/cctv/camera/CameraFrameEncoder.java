package gg.lakehouse.cctv.camera;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Turns a full-RGB pixel frame into terminal cells: a median-cut 16-color
 * palette chosen per frame, ordered (Bayer) dithering fixed in screen space,
 * and 2x3 subpixels per cell through the teletext drawing characters
 * (0x80-0x9F). Each cell can show two palette colors; subpixels that landed on
 * other colors snap to the nearer of the two.
 */
final class CameraFrameEncoder {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** 4x4 Bayer matrix, row-major, values 0-15. */
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final int DITHER_STRENGTH = 32;
    private static final int MAX_SAMPLES = 16384;

    /** text/fg/bg are blit-ready rows; palette maps color index 0-15 to 0xRRGGBB. */
    record EncodedFrame(int width, int height, String[] text, String[] fg, String[] bg, int[] palette) {
    }

    private CameraFrameEncoder() {
    }

    static EncodedFrame encode(CameraRaycaster.PixelFrame frame, int cellsWide, int cellsTall) {
        var palette = buildPalette(frame.pixels());
        var indexed = ditherAndIndex(frame, palette);
        int pixelWidth = frame.width();

        var text = new String[cellsTall];
        var fg = new String[cellsTall];
        var bg = new String[cellsTall];
        var subpixels = new int[6];
        for (int cy = 0; cy < cellsTall; cy++) {
            var textRow = new char[cellsWide];
            var fgRow = new char[cellsWide];
            var bgRow = new char[cellsWide];
            for (int cx = 0; cx < cellsWide; cx++) {
                var counts = new int[16];
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        int index = indexed[(cy * 3 + sy) * pixelWidth + cx * 2 + sx];
                        subpixels[sy * 2 + sx] = index;
                        counts[index]++;
                    }
                }
                int primary = 0;
                int secondary = -1;
                for (int i = 1; i < 16; i++) {
                    if (counts[i] > counts[primary]) primary = i;
                }
                for (int i = 0; i < 16; i++) {
                    if (i == primary || counts[i] == 0) continue;
                    if (secondary < 0 || counts[i] > counts[secondary]) secondary = i;
                }
                if (secondary < 0) {
                    textRow[cx] = ' ';
                    fgRow[cx] = HEX[primary];
                    bgRow[cx] = HEX[primary];
                    continue;
                }

                int bits = 0;
                for (int k = 0; k < 6; k++) {
                    int index = subpixels[k];
                    boolean useSecondary = index == secondary
                        || (index != primary && distance(palette[index], palette[secondary])
                            < distance(palette[index], palette[primary]));
                    if (useSecondary) bits |= 1 << k;
                }
                if ((bits & 32) != 0) {
                    textRow[cx] = (char) (128 + (~bits & 31));
                    fgRow[cx] = HEX[primary];
                    bgRow[cx] = HEX[secondary];
                } else {
                    textRow[cx] = (char) (128 + bits);
                    fgRow[cx] = HEX[secondary];
                    bgRow[cx] = HEX[primary];
                }
            }
            text[cy] = new String(textRow);
            fg[cy] = new String(fgRow);
            bg[cy] = new String(bgRow);
        }
        return new EncodedFrame(cellsWide, cellsTall, text, fg, bg, palette);
    }

    // === Palette (median cut) ===

    private record Box(int from, int to) {
    }

    private static int[] buildPalette(int[] pixels) {
        int stride = Math.max(1, pixels.length / MAX_SAMPLES);
        var samples = new int[(pixels.length + stride - 1) / stride];
        for (int i = 0, j = 0; i < pixels.length; i += stride) samples[j++] = pixels[i];

        var boxes = new ArrayList<Box>();
        boxes.add(new Box(0, samples.length));
        while (boxes.size() < 16) {
            int widest = -1;
            int widestRange = 0;
            int widestShift = 0;
            for (int i = 0; i < boxes.size(); i++) {
                var box = boxes.get(i);
                if (box.to - box.from < 2) continue;
                for (int shift = 16; shift >= 0; shift -= 8) {
                    int min = 255;
                    int max = 0;
                    for (int s = box.from; s < box.to; s++) {
                        int channel = (samples[s] >> shift) & 0xFF;
                        min = Math.min(min, channel);
                        max = Math.max(max, channel);
                    }
                    if (max - min > widestRange) {
                        widestRange = max - min;
                        widest = i;
                        widestShift = shift;
                    }
                }
            }
            if (widest < 0) break;
            var box = boxes.get(widest);
            sortByChannel(samples, box.from, box.to, widestShift);
            int mid = (box.from + box.to) / 2;
            boxes.set(widest, new Box(box.from, mid));
            boxes.add(new Box(mid, box.to));
        }

        var palette = new int[16];
        for (int i = 0; i < 16; i++) {
            var box = boxes.get(Math.min(i, boxes.size() - 1));
            long r = 0;
            long g = 0;
            long b = 0;
            for (int s = box.from; s < box.to; s++) {
                r += (samples[s] >> 16) & 0xFF;
                g += (samples[s] >> 8) & 0xFF;
                b += samples[s] & 0xFF;
            }
            int count = Math.max(1, box.to - box.from);
            palette[i] = (int) ((r / count) << 16 | (g / count) << 8 | (b / count));
        }
        return palette;
    }

    /** Counting sort of samples[from..to) by one 8-bit channel; stable and allocation-light. */
    private static void sortByChannel(int[] samples, int from, int to, int shift) {
        var counts = new int[257];
        for (int i = from; i < to; i++) counts[((samples[i] >> shift) & 0xFF) + 1]++;
        for (int i = 1; i < 257; i++) counts[i] += counts[i - 1];
        var sorted = new int[to - from];
        for (int i = from; i < to; i++) sorted[counts[(samples[i] >> shift) & 0xFF]++] = samples[i];
        System.arraycopy(sorted, 0, samples, from, sorted.length);
    }

    // === Dithering ===

    private static byte[] ditherAndIndex(CameraRaycaster.PixelFrame frame, int[] palette) {
        var indexed = new byte[frame.pixels().length];
        var cache = new HashMap<Integer, Byte>();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int i = y * frame.width() + x;
                int rgb = frame.pixels()[i];
                int bayer = BAYER[(y & 3) * 4 + (x & 3)];
                int key = (rgb << 4) | bayer;
                var cached = cache.get(key);
                if (cached != null) {
                    indexed[i] = cached;
                    continue;
                }
                int offset = ((bayer * 2 - 15) * DITHER_STRENGTH) / 32;
                int r = Mth.clamp(((rgb >> 16) & 0xFF) + offset, 0, 255);
                int g = Mth.clamp(((rgb >> 8) & 0xFF) + offset, 0, 255);
                int b = Mth.clamp((rgb & 0xFF) + offset, 0, 255);
                byte best = 0;
                int bestDistance = Integer.MAX_VALUE;
                for (int p = 0; p < 16; p++) {
                    int distance = distance(palette[p], (r << 16) | (g << 8) | b);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = (byte) p;
                    }
                }
                cache.put(key, best);
                indexed[i] = best;
            }
        }
        return indexed;
    }

    private static int distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }
}
