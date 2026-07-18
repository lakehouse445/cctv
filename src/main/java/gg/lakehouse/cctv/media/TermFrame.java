package gg.lakehouse.cctv.media;

import dan200.computercraft.core.terminal.Palette;
import dan200.computercraft.core.terminal.Terminal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A full snapshot of a terminal: dimensions, the 16-colour palette, and per-line
 * text/foreground/background data in CC's usual hex-digit colour encoding.
 * Full frames only for now — the delta format from the spec arrives with tapes.
 */
public record TermFrame(int width, int height, int[] palette, String[] text, String[] fg, String[] bg) {
    private static final int FORMAT_VERSION = 1;

    public static TermFrame capture(Terminal terminal) {
        int width = terminal.getWidth();
        int height = terminal.getHeight();
        var palette = new int[Palette.PALETTE_SIZE];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = Palette.encodeRGB8(terminal.getPalette().getColour(i));
        }
        var text = new String[height];
        var fg = new String[height];
        var bg = new String[height];
        for (int y = 0; y < height; y++) {
            text[y] = terminal.getLine(y).toString();
            fg[y] = terminal.getTextColourLine(y).toString();
            bg[y] = terminal.getBackgroundColourLine(y).toString();
        }
        return new TermFrame(width, height, palette, text, fg, bg);
    }

    public void write(DataOutput out) throws IOException {
        out.writeShort(width);
        out.writeShort(height);
        for (int colour : palette) out.writeInt(colour);
        for (int y = 0; y < height; y++) {
            out.writeUTF(text[y]);
            out.writeUTF(fg[y]);
            out.writeUTF(bg[y]);
        }
    }

    public static TermFrame read(DataInput in) throws IOException {
        int width = in.readUnsignedShort();
        int height = in.readUnsignedShort();
        var palette = new int[16];
        for (int i = 0; i < palette.length; i++) palette[i] = in.readInt();
        var text = new String[height];
        var fg = new String[height];
        var bg = new String[height];
        for (int y = 0; y < height; y++) {
            text[y] = in.readUTF();
            fg[y] = in.readUTF();
            bg[y] = in.readUTF();
        }
        return new TermFrame(width, height, palette, text, fg, bg);
    }

    public record Recording(int fps, List<TermFrame> frames) {
    }

    public record Header(int fps, int frames) {
    }

    /** Reads just the header of a serialised recording without decoding frames. */
    public static Header readHeader(java.io.InputStream in) throws IOException {
        var data = new DataInputStream(new GZIPInputStream(in));
        int version = data.readUnsignedByte();
        if (version != FORMAT_VERSION) throw new IOException("Unknown recording format " + version);
        return new Header(data.readInt(), data.readInt());
    }

    public static byte[] writeAll(int fps, List<TermFrame> frames) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(fps);
            out.writeInt(frames.size());
            for (var frame : frames) frame.write(out);
        }
        return bytes.toByteArray();
    }

    public static Recording readAll(byte[] data) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) throw new IOException("Unknown recording format " + version);
            int fps = in.readInt();
            int count = in.readInt();
            var frames = new ArrayList<TermFrame>(count);
            for (int i = 0; i < count; i++) frames.add(read(in));
            return new Recording(fps, frames);
        }
    }
}
