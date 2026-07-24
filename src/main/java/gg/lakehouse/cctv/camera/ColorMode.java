package gg.lakehouse.cctv.camera;

import javax.annotation.Nullable;

/**
 * Camera picture tone. BW and SEPIA reduce pixels to auto-exposed luma before
 * the palette is built, so all 16 palette slots go to shades of one hue — far
 * more legible on a monitor than 16 colors split across the scene.
 */
public enum ColorMode {
    BW("bw"),
    SEPIA("sepia"),
    COLOR("color");

    private final String name;

    ColorMode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public static ColorMode byName(String name) {
        for (var mode : values()) {
            if (mode.name.equalsIgnoreCase(name)) return mode;
        }
        return null;
    }

    /** Rec.601 luma (0-255) of one 0xRRGGBB pixel. */
    public static int luma(int rgb) {
        return (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
    }

    /** Maps exposed luma back to a pixel: gray, or the classic sepia tint. */
    public int fromLuma(int luma) {
        if (this == SEPIA) {
            int r = Math.min(255, luma * 1351 / 1000);
            int g = Math.min(255, luma * 1203 / 1000);
            int b = luma * 937 / 1000;
            return (r << 16) | (g << 8) | b;
        }
        return (luma << 16) | (luma << 8) | luma;
    }
}
