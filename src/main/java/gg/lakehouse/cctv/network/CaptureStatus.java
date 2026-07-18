package gg.lakehouse.cctv.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record CaptureStatus(BlockPos pos, boolean recording, int frames, int fps, boolean hasMonitor,
                            boolean hasTape, String tapeLabel, long usedBytes, long capacityBytes, int recordings) {
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(recording);
        buf.writeVarInt(frames);
        buf.writeVarInt(fps);
        buf.writeBoolean(hasMonitor);
        buf.writeBoolean(hasTape);
        buf.writeUtf(tapeLabel);
        buf.writeVarLong(usedBytes);
        buf.writeVarLong(capacityBytes);
        buf.writeVarInt(recordings);
    }

    public static CaptureStatus read(FriendlyByteBuf buf) {
        return new CaptureStatus(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
            buf.readBoolean(), buf.readBoolean(), buf.readUtf(), buf.readVarLong(), buf.readVarLong(), buf.readVarInt());
    }
}
