package gg.lakehouse.cctv.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record PlaybackStatus(BlockPos pos, String state, boolean hasMonitor, boolean hasTape,
                             String tapeLabel, String recordingName, double position, double length,
                             int fps, List<Entry> recordings) {
    public record Entry(String name, int frames, int fps) {
        public double seconds() {
            return frames / (double) Math.max(1, fps);
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(state);
        buf.writeBoolean(hasMonitor);
        buf.writeBoolean(hasTape);
        buf.writeUtf(tapeLabel);
        buf.writeUtf(recordingName);
        buf.writeDouble(position);
        buf.writeDouble(length);
        buf.writeVarInt(fps);
        buf.writeVarInt(recordings.size());
        for (var entry : recordings) {
            buf.writeUtf(entry.name());
            buf.writeVarInt(entry.frames());
            buf.writeVarInt(entry.fps());
        }
    }

    public static PlaybackStatus read(FriendlyByteBuf buf) {
        var pos = buf.readBlockPos();
        var state = buf.readUtf();
        boolean hasMonitor = buf.readBoolean();
        boolean hasTape = buf.readBoolean();
        var tapeLabel = buf.readUtf();
        var recordingName = buf.readUtf();
        double position = buf.readDouble();
        double length = buf.readDouble();
        int fps = buf.readVarInt();
        int count = buf.readVarInt();
        // Each entry needs at least 3 wire bytes; larger counts are hostile.
        if (count < 0 || count > buf.readableBytes() / 3) {
            throw new io.netty.handler.codec.DecoderException("Recording list size out of range");
        }
        var recordings = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            recordings.add(new Entry(buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
        }
        return new PlaybackStatus(pos, state, hasMonitor, hasTape, tapeLabel, recordingName,
            position, length, fps, recordings);
    }
}
