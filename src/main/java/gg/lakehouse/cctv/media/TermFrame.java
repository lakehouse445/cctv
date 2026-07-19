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
    private static final int FORMAT_V1 = 1;
    /** V2 adds segment metadata so recordings can span, stripe, or loop across tapes. */
    private static final int FORMAT_V2 = 2;

    /**
     * Links one file to a recording group spread over several tapes.
     * index orders segments in time, lane/lanes describe striping
     * (lane 0 of 1 = not striped), totalFrames is -1 for open loop chains.
     */
    public record SegmentInfo(java.util.UUID group, int index, int lane, int lanes, int totalFrames) {
        public String shortId() {
            return "grp_" + group.toString().substring(0, 8);
        }
    }

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

    public record Recording(int fps, List<TermFrame> frames, @javax.annotation.Nullable SegmentInfo segment) {
    }

    public record Header(int fps, int frames, @javax.annotation.Nullable SegmentInfo segment) {
    }

    /** Reads just the header of a serialised recording without decoding frames. */
    public static Header readHeader(java.io.InputStream in) throws IOException {
        var data = new DataInputStream(new GZIPInputStream(in));
        int version = data.readUnsignedByte();
        if (version != FORMAT_V1 && version != FORMAT_V2) throw new IOException("Unknown recording format " + version);
        int fps = data.readInt();
        int count = data.readInt();
        return new Header(fps, count, version == FORMAT_V2 ? readSegment(data) : null);
    }

    public static byte[] writeAll(int fps, List<TermFrame> frames) throws IOException {
        return write(fps, frames, null);
    }

    public static byte[] write(int fps, List<TermFrame> frames, @javax.annotation.Nullable SegmentInfo segment) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(segment == null ? FORMAT_V1 : FORMAT_V2);
            out.writeInt(fps);
            out.writeInt(frames.size());
            if (segment != null) {
                out.writeLong(segment.group().getMostSignificantBits());
                out.writeLong(segment.group().getLeastSignificantBits());
                out.writeInt(segment.index());
                out.writeInt(segment.lane());
                out.writeInt(segment.lanes());
                out.writeInt(segment.totalFrames());
            }
            for (var frame : frames) frame.write(out);
        }
        return bytes.toByteArray();
    }

    public static Recording readAll(byte[] data) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int version = in.readUnsignedByte();
            if (version != FORMAT_V1 && version != FORMAT_V2) throw new IOException("Unknown recording format " + version);
            int fps = in.readInt();
            int count = in.readInt();
            var segment = version == FORMAT_V2 ? readSegment(in) : null;
            var frames = new ArrayList<TermFrame>(count);
            for (int i = 0; i < count; i++) frames.add(read(in));
            return new Recording(fps, frames, segment);
        }
    }

    private static SegmentInfo readSegment(DataInput in) throws IOException {
        var group = new java.util.UUID(in.readLong(), in.readLong());
        return new SegmentInfo(group, in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    /** The card shown where a spanned recording's tape has gone missing. */
    public static TermFrame missingTapeFrame(int width, int height, int[] palette) {
        var text = new String[height];
        var fg = new String[height];
        var bg = new String[height];
        var message = "TAPE MISSING";
        for (int y = 0; y < height; y++) {
            if (y == height / 2 && width >= message.length()) {
                int pad = (width - message.length()) / 2;
                text[y] = " ".repeat(pad) + message + " ".repeat(width - message.length() - pad);
            } else {
                text[y] = " ".repeat(width);
            }
            fg[y] = "e".repeat(width);
            bg[y] = "f".repeat(width);
        }
        return new TermFrame(width, height, palette, text, fg, bg);
    }
}
