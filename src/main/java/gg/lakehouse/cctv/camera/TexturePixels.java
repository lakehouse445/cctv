package gg.lakehouse.cctv.camera;

import net.minecraft.util.Mth;

import java.util.ArrayList;

/**
 * CPU-side ARGB pixels for one texture, sampled by the camera raycaster.
 * Animated textures (vertical frame strips) sample their first frame.
 *
 * Mip levels are built lazily: when a surface is far enough that one screen
 * pixel covers several texels, sampling switches to a pre-averaged half-size
 * level. That is the camera's texture anti-aliasing — without it, distant
 * grass and leaves point-sample arbitrary texels and turn to static.
 */
public final class TexturePixels {
    private static final int MAX_MIPS = 4;

    private final int[] argb;
    private final int width;
    private final int height;
    /** Lazy mip chain; level i is the texture halved i+1 times. Built once, then read-only. */
    private volatile int[][] mips;

    public TexturePixels(int[] argb, int width, int height) {
        this.argb = argb;
        this.width = width;
        this.height = height;
    }

    public static TexturePixels solid(int rgb) {
        return new TexturePixels(new int[]{0xFF000000 | rgb}, 1, 1);
    }

    public int[] argb() {
        return argb;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int sample(double u, double v) {
        int frameHeight = frameHeight();
        int x = Mth.clamp((int) (u * width), 0, width - 1);
        int y = Mth.clamp((int) (v * frameHeight), 0, frameHeight - 1);
        return argb[y * width + x];
    }

    /**
     * Distance-aware sample: texelsPerPixel is how many texels this screen
     * pixel's footprint spans. Two or more walks down the mip chain (each
     * level halves resolution), so far surfaces read a local average.
     */
    public int sample(double u, double v, double texelsPerPixel) {
        if (texelsPerPixel < 2) return sample(u, v);
        var levels = mips;
        if (levels == null) {
            synchronized (this) {
                if (mips == null) mips = buildMips();
                levels = mips;
            }
        }
        if (levels.length == 0) return sample(u, v);
        int level = Math.min(levels.length, 31 - Integer.numberOfLeadingZeros((int) texelsPerPixel));
        int w = Math.max(1, width >> level);
        int h = Math.max(1, frameHeight() >> level);
        var pixels = levels[level - 1];
        int x = Mth.clamp((int) (u * w), 0, w - 1);
        int y = Mth.clamp((int) (v * h), 0, h - 1);
        return pixels[y * w + x];
    }

    private int frameHeight() {
        return height > width ? width : height;
    }

    private int[][] buildMips() {
        var levels = new ArrayList<int[]>(MAX_MIPS);
        int[] src = argb;
        int srcW = width;
        int srcH = frameHeight();
        while (levels.size() < MAX_MIPS && (srcW > 1 || srcH > 1)) {
            int w = Math.max(1, srcW / 2);
            int h = Math.max(1, srcH / 2);
            var out = new int[w * h];
            for (int y = 0; y < h; y++) {
                int y0 = Math.min(srcH - 1, y * 2);
                int y1 = Math.min(srcH - 1, y * 2 + 1);
                for (int x = 0; x < w; x++) {
                    int x0 = Math.min(srcW - 1, x * 2);
                    int x1 = Math.min(srcW - 1, x * 2 + 1);
                    out[y * w + x] = average(src[y0 * srcW + x0], src[y0 * srcW + x1],
                        src[y1 * srcW + x0], src[y1 * srcW + x1]);
                }
            }
            levels.add(out);
            src = out;
            srcW = w;
            srcH = h;
        }
        return levels.toArray(new int[0][]);
    }

    /** Alpha-weighted 2x2 average, so transparent texels don't bleed black into edges. */
    private static int average(int p0, int p1, int p2, int p3) {
        int a0 = p0 >>> 24;
        int a1 = p1 >>> 24;
        int a2 = p2 >>> 24;
        int a3 = p3 >>> 24;
        int alphaSum = a0 + a1 + a2 + a3;
        if (alphaSum == 0) return 0;
        int r = ((p0 >> 16 & 0xFF) * a0 + (p1 >> 16 & 0xFF) * a1 + (p2 >> 16 & 0xFF) * a2 + (p3 >> 16 & 0xFF) * a3) / alphaSum;
        int g = ((p0 >> 8 & 0xFF) * a0 + (p1 >> 8 & 0xFF) * a1 + (p2 >> 8 & 0xFF) * a2 + (p3 >> 8 & 0xFF) * a3) / alphaSum;
        int b = ((p0 & 0xFF) * a0 + (p1 & 0xFF) * a1 + (p2 & 0xFF) * a2 + (p3 & 0xFF) * a3) / alphaSum;
        return (alphaSum / 4) << 24 | r << 16 | g << 8 | b;
    }
}
