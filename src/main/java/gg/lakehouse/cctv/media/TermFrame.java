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
    /** V3 adds the recorded monitor's physical size, so exports match its face. */
    private static final int FORMAT_V3 = 3;

    /** Rough heap footprint of a buffered frame, for recording byte budgets. */
    public long estimatedBytes() {
        return 6L * width * height + 256;
    }

    /**
     * The 20 Hz tick can only deliver frame rates that divide 20; a request
     * for 7 fps used to record at 5 while the header claimed 7. Snap to the
     * nearest deliverable rate so the header always tells the truth.
     */
    public static int snapFps(int requested) {
        int clamped = Math.max(1, Math.min(20, requested));
        int best = 1;
        for (int rate : new int[]{1, 2, 4, 5, 10, 20}) {
            if (Math.abs(rate - clamped) < Math.abs(best - clamped)) best = rate;
        }
        return best;
    }

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

    /**
     * The recorded monitor's physical shape: size in blocks and the text
     * scale in CC's internal half-steps (setTextScale(0.5) = 1 .. 5.0 = 10).
     */
    public record MonitorInfo(int blocksWide, int blocksTall, int textScaleHalf) {
        /**
         * Recovers the text scale by inverting CC's cell-count rounding:
         * of the ten possible scales, exactly one reproduces the observed
         * terminal dimensions for this block size.
         */
        public static MonitorInfo derive(int blocksWide, int blocksTall, int termWidth, int termHeight) {
            for (int half = 1; half <= 10; half++) {
                long w = Math.max(1, Math.round((blocksWide - 0.3125) / (half * 0.5 * 6.0 / 64)));
                long h = Math.max(1, Math.round((blocksTall - 0.3125) / (half * 0.5 * 9.0 / 64)));
                if (w == termWidth && h == termHeight) return new MonitorInfo(blocksWide, blocksTall, half);
            }
            return new MonitorInfo(blocksWide, blocksTall, 1);
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

    public record Recording(int fps, List<TermFrame> frames, @javax.annotation.Nullable SegmentInfo segment,
                            @javax.annotation.Nullable MonitorInfo monitor) {
    }

    public record Header(int fps, int frames, @javax.annotation.Nullable SegmentInfo segment) {
    }

    /** Reads just the header of a serialised recording without decoding frames. */
    public static Header readHeader(java.io.InputStream in) throws IOException {
        var data = new DataInputStream(new GZIPInputStream(in));
        int version = data.readUnsignedByte();
        if (version < FORMAT_V1 || version > FORMAT_V3) throw new IOException("Unknown recording format " + version);
        int fps = data.readInt();
        int count = data.readInt();
        SegmentInfo segment = null;
        if (version == FORMAT_V2) {
            segment = readSegment(data);
        } else if (version == FORMAT_V3) {
            if (data.readBoolean()) segment = readSegment(data);
        }
        return new Header(fps, count, segment);
    }

    public static byte[] writeAll(int fps, List<TermFrame> frames) throws IOException {
        return write(fps, frames, null, null);
    }

    public static byte[] write(int fps, List<TermFrame> frames, @javax.annotation.Nullable SegmentInfo segment) throws IOException {
        return write(fps, frames, segment, null);
    }

    public static byte[] write(int fps, List<TermFrame> frames, @javax.annotation.Nullable SegmentInfo segment,
                               @javax.annotation.Nullable MonitorInfo monitor) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            int version = monitor != null ? FORMAT_V3 : segment != null ? FORMAT_V2 : FORMAT_V1;
            out.writeByte(version);
            out.writeInt(fps);
            out.writeInt(frames.size());
            if (version == FORMAT_V3) out.writeBoolean(segment != null);
            if (segment != null) {
                out.writeLong(segment.group().getMostSignificantBits());
                out.writeLong(segment.group().getLeastSignificantBits());
                out.writeInt(segment.index());
                out.writeInt(segment.lane());
                out.writeInt(segment.lanes());
                out.writeInt(segment.totalFrames());
            }
            if (version == FORMAT_V3) {
                out.writeInt(monitor.blocksWide());
                out.writeInt(monitor.blocksTall());
                out.writeInt(monitor.textScaleHalf());
            }
            for (var frame : frames) frame.write(out);
        }
        return bytes.toByteArray();
    }

    public static Recording readAll(byte[] data) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int version = in.readUnsignedByte();
            if (version < FORMAT_V1 || version > FORMAT_V3) throw new IOException("Unknown recording format " + version);
            int fps = in.readInt();
            int count = in.readInt();
            SegmentInfo segment = null;
            MonitorInfo monitor = null;
            if (version == FORMAT_V2) {
                segment = readSegment(in);
            } else if (version == FORMAT_V3) {
                if (in.readBoolean()) segment = readSegment(in);
                monitor = new MonitorInfo(in.readInt(), in.readInt(), in.readInt());
            }
            var frames = new ArrayList<TermFrame>(count);
            for (int i = 0; i < count; i++) frames.add(read(in));
            return new Recording(fps, frames, segment, monitor);
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
