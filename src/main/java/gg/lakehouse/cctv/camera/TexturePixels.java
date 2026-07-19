package gg.lakehouse.cctv.camera;

import net.minecraft.util.Mth;

/**
 * CPU-side ARGB pixels for one texture, sampled by the camera raycaster.
 * Animated textures (vertical frame strips) sample their first frame.
 */
public record TexturePixels(int[] argb, int width, int height) {
    public static TexturePixels solid(int rgb) {
        return new TexturePixels(new int[]{0xFF000000 | rgb}, 1, 1);
    }

    public int sample(double u, double v) {
        int frameHeight = height > width ? width : height;
        int x = Mth.clamp((int) (u * width), 0, width - 1);
        int y = Mth.clamp((int) (v * frameHeight), 0, frameHeight - 1);
        return argb[y * width + x];
    }
}
