package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * One chunk of gzipped {@link gg.lakehouse.cctv.media.TermFrame} recording
 * data; the client reassembles the chunks and encodes the video. Chunked
 * because a recording easily outgrows vanilla's 1 MiB payload cap.
 */
public record ClientboundExportRecordingPacket(int exportId, int index, int total, byte[] data) {
    /** Comfortably under vanilla's 1 MiB custom payload cap. */
    public static final int CHUNK_BYTES = 512 * 1024;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(exportId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static ClientboundExportRecordingPacket decode(FriendlyByteBuf buf) {
        return new ClientboundExportRecordingPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.export(this)));
        ctx.get().setPacketHandled(true);
    }
}
