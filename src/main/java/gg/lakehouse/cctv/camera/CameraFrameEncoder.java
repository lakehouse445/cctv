package gg.lakehouse.cctv.camera;

import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Turns a full-RGB pixel frame into terminal cells: a median-cut 16-color
 * palette chosen per frame, ordered (Bayer) dithering fixed in screen space,
 * and 2x3 subpixels per cell through the teletext drawing characters
 * (0x80-0x9F). Each cell can show two palette colors; subpixels that landed on
 * other colors snap to the nearer of the two.
 */
public final class CameraFrameEncoder {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** 4x4 Bayer matrix, row-major, values 0-15. */
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final int DITHER_STRENGTH = 32;
    private static final int MAX_SAMPLES = 16384;

    /** text/fg/bg are blit-ready rows; palette maps color index 0-15 to 0xRRGGBB. */
    public record EncodedFrame(int width, int height, String[] text, String[] fg, String[] bg, int[] palette) {
    }

    /**
     * Per-camera temporal smoothing for the tone pipeline, like a real AGC.
     * Exposure anchors drift over seconds (small deadband so a static scene
     * freezes exactly; a huge jump snaps like a fast-attack AGC), and the
     * per-frame palette is luma-matched slot by slot against the previous one
     * so flat surfaces keep their exact shade between frames. Without this,
     * every frame re-tunes from scratch and the whole picture pumps with any
     * small scene change.
     */
    public static final class Exposure {
        /** Anchor drift per frame — settles over roughly a second and a half at 10 fps. */
        private static final float RATE = 0.08f;
        /** Percentile wobble this small is ignored entirely. */
        private static final float DEADBAND = 2f;
        /** An anchor jump this big is a scene cut: snap instead of drifting. */
        private static final float SNAP = 96f;
        /** Average per-slot luma change that counts as a palette scene cut. */
        private static final int PALETTE_SNAP = 48;
        /** Per-channel palette wobble this small keeps the previous value. */
        private static final int PALETTE_DEADBAND = 3;
        private static final float PALETTE_RATE = 0.2f;

        private float low = Float.NaN;
        private float high = Float.NaN;
        private int[] palette;

        int smoothLow(int value) {
            low = smooth(low, value);
            return Math.round(low);
        }

        int smoothHigh(int value) {
            high = smooth(high, value);
            return Math.round(high);
        }

        private static float smooth(float anchor, int value) {
            if (Float.isNaN(anchor) || Math.abs(value - anchor) > SNAP) return value;
            if (Math.abs(value - anchor) <= DEADBAND) return anchor;
            return anchor + (value - anchor) * RATE;
        }

        /** Expects palettes sorted by luma so slots correspond frame to frame. */
        int[] smoothPalette(int[] fresh) {
            if (palette == null || palette.length != fresh.length) {
                palette = fresh.clone();
                return fresh;
            }
            long lumaDelta = 0;
            for (int i = 0; i < fresh.length; i++) {
                lumaDelta += Math.abs(ColorMode.luma(fresh[i]) - ColorMode.luma(palette[i]));
            }
            if (lumaDelta / fresh.length > PALETTE_SNAP) {
                palette = fresh.clone();
                return fresh;
            }
            for (int i = 0; i < fresh.length; i++) {
                int r = smoothChannel((palette[i] >> 16) & 0xFF, (fresh[i] >> 16) & 0xFF);
                int g = smoothChannel((palette[i] >> 8) & 0xFF, (fresh[i] >> 8) & 0xFF);
                int b = smoothChannel(palette[i] & 0xFF, fresh[i] & 0xFF);
                palette[i] = (r << 16) | (g << 8) | b;
            }
            return palette.clone();
        }

        private static int smoothChannel(int previous, int fresh) {
            if (Math.abs(fresh - previous) <= PALETTE_DEADBAND) return previous;
            return Math.round(previous + (fresh - previous) * PALETTE_RATE);
        }
    }

    private CameraFrameEncoder() {
    }

    static EncodedFrame encode(CameraRaycaster.PixelFrame frame, int cellsWide, int cellsTall, ColorMode mode,
                               @Nullable Exposure exposure) {
        if (mode != ColorMode.COLOR) autoExpose(frame.pixels(), mode, exposure);
        var palette = buildPalette(frame.pixels());
        if (exposure != null) palette = exposure.smoothPalette(palette);
        var indexed = ditherAndIndex(frame, palette);
        int pixelWidth = frame.width();

        var text = new String[cellsTall];
        var fg = new String[cellsTall];
        var bg = new String[cellsTall];
        var subpixels = new int[6];
        var distances = distanceTable(palette);
        var pair = new int[2];
        for (int cy = 0; cy < cellsTall; cy++) {
            var textRow = new char[cellsWide];
            var fgRow = new char[cellsWide];
            var bgRow = new char[cellsWide];
            for (int cx = 0; cx < cellsWide; cx++) {
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        subpixels[sy * 2 + sx] = indexed[(cy * 3 + sy) * pixelWidth + cx * 2 + sx];
                    }
                }
                if (!bestPair(subpixels, distances, pair)) {
                    textRow[cx] = ' ';
                    fgRow[cx] = HEX[pair[0]];
                    bgRow[cx] = HEX[pair[0]];
                    continue;
                }
                int primary = pair[0];
                int secondary = pair[1];

                int bits = 0;
                for (int k = 0; k < 6; k++) {
                    int index = subpixels[k];
                    boolean useSecondary = index == secondary
                        || (index != primary && distances[index][secondary] < distances[index][primary]);
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

    /** Pairwise squared-RGB distances between palette entries. */
    public static int[][] distanceTable(int[] palette) {
        var distances = new int[16][16];
        for (int a = 0; a < 16; a++) {
            for (int b = a + 1; b < 16; b++) {
                distances[a][b] = distances[b][a] = distance(palette[a], palette[b]);
            }
        }
        return distances;
    }

    /**
     * Picks the two palette entries that together best represent the cell's
     * subpixels (minimal total error), writing them to pair. Frequency alone
     * can't do this: a dithered cell touching six palette entries has all-tied
     * counts, and tie-breaking by slot order collapses smooth grays into
     * whichever two colors sit first in the palette — often the extremes.
     * Returns false when one color suffices (pair[0] holds it).
     */
    public static boolean bestPair(int[] subpixels, int[][] distances, int[] pair) {
        long present = 0;
        for (int index : subpixels) present |= 1L << index;
        if (Long.bitCount(present) == 1) {
            pair[0] = Long.numberOfTrailingZeros(present);
            return false;
        }
        int bestCost = Integer.MAX_VALUE;
        for (int a = 0; a < 16; a++) {
            if ((present & (1L << a)) == 0) continue;
            for (int b = a + 1; b < 16; b++) {
                if ((present & (1L << b)) == 0) continue;
                int cost = 0;
                for (int index : subpixels) {
                    cost += Math.min(distances[index][a], distances[index][b]);
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    pair[0] = a;
                    pair[1] = b;
                }
            }
        }
        return true;
    }

    /**
     * CCTV-style auto gain for the mono modes: reduces pixels to luma and
     * stretches the 2nd-98th percentile to full range, so a dark or washed-out
     * scene still spans black to white. Percentiles keep a stray bright pixel
     * (a torch, the sun) from crushing everything else.
     */
    private static void autoExpose(int[] pixels, ColorMode mode, @Nullable Exposure exposure) {
        var histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = ColorMode.luma(pixels[i]);
            histogram[pixels[i]]++;
        }
        int tail = pixels.length * 2 / 100;
        int low = 0;
        for (int count = 0; low < 255 && count + histogram[low] <= tail; low++) count += histogram[low];
        int high = 255;
        for (int count = 0; high > 0 && count + histogram[high] <= tail; high--) count += histogram[high];
        if (exposure != null) {
            low = exposure.smoothLow(low);
            high = exposure.smoothHigh(high);
        }
        // Gain caps at 4x: a genuinely flat scene stays dim instead of
        // amplifying its texture grain into full-range static.
        int range = Math.max(64, high - low);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = mode.fromLuma(Mth.clamp((pixels[i] - low) * 255 / range, 0, 255));
        }
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
        // Dark-to-bright slot order: median-cut box order is arbitrary, and
        // stable ordering is what lets temporal palette smoothing match slots
        // between frames.
        var boxed = new Integer[16];
        for (int i = 0; i < 16; i++) boxed[i] = palette[i];
        Arrays.sort(boxed, (a, b) -> ColorMode.luma(a) - ColorMode.luma(b));
        for (int i = 0; i < 16; i++) palette[i] = boxed[i];
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
        int strength = ditherStrength(palette);
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
                int offset = ((bayer * 2 - 15) * strength) / 32;
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

    /**
     * Dither amplitude matched to the palette's average luma step: enough to
     * fake in-between shades, never so much that flat surfaces oscillate
     * across several palette entries and turn to checkerboard noise.
     */
    private static int ditherStrength(int[] palette) {
        var lumas = new int[palette.length];
        for (int i = 0; i < palette.length; i++) lumas[i] = ColorMode.luma(palette[i]);
        Arrays.sort(lumas);
        int step = (lumas[lumas.length - 1] - lumas[0]) / (palette.length - 1);
        return Mth.clamp(step, 4, DITHER_STRENGTH);
    }

    private static int distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }
}
