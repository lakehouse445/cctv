package gg.lakehouse.cctv.media;

import gg.lakehouse.cctv.camera.CameraFrameEncoder;

import java.util.Arrays;

/**
 * Resamples terminal frames in teletext subpixel space (2x3 pixels per cell
 * through the drawing characters). Recordings store at one standard size no
 * matter which monitor they were captured from, and playback scales the
 * stored picture to whatever terminal it lands on. Cells holding real text
 * (anything that is not a drawing character or a space) survive by
 * nearest-cell copy, so captions stay readable characters instead of being
 * melted into pixels.
 */
public final class FrameScaler {
    /** Standard stored size: the camera's largest frame; a full monitor wall at 0.5 scale. */
    public static final int RECORD_WIDTH = 162;
    public static final int RECORD_HEIGHT = 81;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FrameScaler() {
    }

    public static TermFrame toRecordingSize(TermFrame frame) {
        return scale(frame, RECORD_WIDTH, RECORD_HEIGHT);
    }

    public static TermFrame scale(TermFrame frame, int width, int height) {
        if (width < 1 || height < 1 || (frame.width() == width && frame.height() == height)) return frame;

        int sourceWidth = frame.width();
        int sourceHeight = frame.height();
        int subWidth = sourceWidth * 2;
        int subHeight = sourceHeight * 3;
        var indices = new byte[subHeight * subWidth];
        var isText = new boolean[sourceHeight * sourceWidth];
        for (int cy = 0; cy < sourceHeight; cy++) {
            var text = frame.text()[cy];
            var fg = frame.fg()[cy];
            var bg = frame.bg()[cy];
            for (int cx = 0; cx < sourceWidth; cx++) {
                char glyph = charAt(text, cx, ' ');
                byte fgIndex = hexIndex(fg, cx);
                byte bgIndex = hexIndex(bg, cx);
                int bits = glyph >= 128 && glyph < 160 ? glyph - 128 : -1;
                if (bits < 0 && glyph != ' ') isText[cy * sourceWidth + cx] = true;
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        int bit = sy * 2 + sx;
                        // Bit 5 (bottom-right) is always background in the teletext glyphs.
                        boolean foreground = bits >= 0 && bit < 5 && (bits >> bit & 1) != 0;
                        indices[(cy * 3 + sy) * subWidth + cx * 2 + sx] = foreground ? fgIndex : bgIndex;
                    }
                }
            }
        }

        int targetSubWidth = width * 2;
        int targetSubHeight = height * 3;
        var outText = new String[height];
        var outFg = new String[height];
        var outBg = new String[height];
        var boxCounts = new int[16];
        var subpixels = new int[6];
        // Captured palettes store CC's internal BLACK(0)..WHITE(15) array
        // order — the reverse of the hex digits the cells use — so the true
        // color of digit d is palette[15 - d].
        var trueColours = new int[16];
        for (int i = 0; i < 16; i++) trueColours[i] = frame.palette()[15 - i];
        var distances = CameraFrameEncoder.distanceTable(trueColours);
        var pair = new int[2];
        for (int cy = 0; cy < height; cy++) {
            var textRow = new char[width];
            var fgRow = new char[width];
            var bgRow = new char[width];
            for (int cx = 0; cx < width; cx++) {
                int nearestX = cx * sourceWidth / width;
                int nearestY = cy * sourceHeight / height;
                if (isText[nearestY * sourceWidth + nearestX]) {
                    textRow[cx] = charAt(frame.text()[nearestY], nearestX, ' ');
                    fgRow[cx] = charAt(frame.fg()[nearestY], nearestX, '0');
                    bgRow[cx] = charAt(frame.bg()[nearestY], nearestX, 'f');
                    continue;
                }

                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        int px = cx * 2 + sx;
                        int py = cy * 3 + sy;
                        int x0 = px * subWidth / targetSubWidth;
                        int x1 = Math.max(x0 + 1, (px + 1) * subWidth / targetSubWidth);
                        int y0 = py * subHeight / targetSubHeight;
                        int y1 = Math.max(y0 + 1, (py + 1) * subHeight / targetSubHeight);
                        // Majority vote over the covered source box; a box of one
                        // (upscaling) is plain nearest-neighbour.
                        Arrays.fill(boxCounts, 0);
                        int best = 0;
                        for (int y = y0; y < y1; y++) {
                            for (int x = x0; x < x1; x++) {
                                int index = indices[y * subWidth + x] & 0xF;
                                if (++boxCounts[index] > boxCounts[best]) best = index;
                            }
                        }
                        subpixels[sy * 2 + sx] = best;
                    }
                }

                if (!CameraFrameEncoder.bestPair(subpixels, distances, pair)) {
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
            outText[cy] = new String(textRow);
            outFg[cy] = new String(fgRow);
            outBg[cy] = new String(bgRow);
        }
        return new TermFrame(width, height, frame.palette(), outText, outFg, outBg);
    }

    private static char charAt(String line, int index, char fallback) {
        return index < line.length() ? line.charAt(index) : fallback;
    }

    private static byte hexIndex(String line, int index) {
        if (index >= line.length()) return 0;
        int value = Character.digit(line.charAt(index), 16);
        return value < 0 ? 0 : (byte) value;
    }
}
