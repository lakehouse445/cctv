package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Gzipped {@link gg.lakehouse.cctv.media.TermFrame} recording data, client encodes the GIF. */
public record ClientboundExportRecordingPacket(byte[] data) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByteArray(data);
    }

    public static ClientboundExportRecordingPacket decode(FriendlyByteBuf buf) {
        return new ClientboundExportRecordingPacket(buf.readByteArray());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.export(this)));
        ctx.get().setPacketHandled(true);
    }
}
