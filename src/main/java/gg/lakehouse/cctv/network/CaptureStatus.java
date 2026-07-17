package gg.lakehouse.cctv.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

public record CaptureStatus(BlockPos pos, boolean recording, int frames, int fps, boolean hasMonitor) {
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(recording);
        buf.writeVarInt(frames);
        buf.writeVarInt(fps);
        buf.writeBoolean(hasMonitor);
    }

    public static CaptureStatus read(FriendlyByteBuf buf) {
        return new CaptureStatus(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }
}
