package gg.lakehouse.cctv.media;

import dan200.computercraft.core.terminal.Palette;
import dan200.computercraft.core.terminal.Terminal;

/** Writes recorded frames onto live CC terminals. */
public final class Terminals {
    private Terminals() {
    }

    public static void applyFrame(Terminal terminal, TermFrame frame) {
        for (int i = 0; i < frame.palette().length && i < Palette.PALETTE_SIZE; i++) {
            var rgb = Palette.decodeRGB8(frame.palette()[i]);
            terminal.getPalette().setColour(i, rgb[0], rgb[1], rgb[2]);
        }
        int width = terminal.getWidth();
        int height = terminal.getHeight();
        for (int y = 0; y < height; y++) {
            if (y < frame.height()) {
                terminal.setLine(y, fit(frame.text()[y], width, ' '), fit(frame.fg()[y], width, '0'), fit(frame.bg()[y], width, 'f'));
            } else {
                terminal.setLine(y, " ".repeat(width), "0".repeat(width), "f".repeat(width));
            }
        }
        terminal.setChanged();
    }

    private static String fit(String line, int width, char pad) {
        if (line.length() == width) return line;
        if (line.length() > width) return line.substring(0, width);
        return line + String.valueOf(pad).repeat(width - line.length());
    }
}
