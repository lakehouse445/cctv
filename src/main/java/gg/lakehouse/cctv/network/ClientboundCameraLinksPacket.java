package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.link.CameraLinks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Full camera-link state for the player's dimension, for path rendering. */
public record ClientboundCameraLinksPacket(List<Entry> entries) {
    public record Entry(long camera, long modem, long[] path) {
    }

    public static ClientboundCameraLinksPacket of(List<CameraLinks.Link> links) {
        var entries = new ArrayList<Entry>(links.size());
        for (var link : links) {
            entries.add(new Entry(link.camera.asLong(), link.modem.asLong(),
                link.path.stream().mapToLong(net.minecraft.core.BlockPos::asLong).toArray()));
        }
        return new ClientboundCameraLinksPacket(entries);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (var entry : entries) {
            buf.writeLong(entry.camera());
            buf.writeLong(entry.modem());
            buf.writeLongArray(entry.path());
        }
    }

    public static ClientboundCameraLinksPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        // A real entry needs at least 17 bytes on the wire; a count beyond
        // that bound is a hostile allocation claim.
        if (count < 0 || count > buf.readableBytes() / 17) {
            throw new io.netty.handler.codec.DecoderException("Link list size out of range");
        }
        var entries = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            long camera = buf.readLong();
            long modem = buf.readLong();
            int pathLength = buf.readVarInt();
            if (pathLength < 0 || pathLength > buf.readableBytes() / 8) {
                throw new io.netty.handler.codec.DecoderException("Link path length out of range");
            }
            var path = new long[pathLength];
            for (int p = 0; p < pathLength; p++) path[p] = buf.readLong();
            entries.add(new Entry(camera, modem, path));
        }
        return new ClientboundCameraLinksPacket(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> gg.lakehouse.cctv.client.CameraLinkRenderer.accept(this)));
        ctx.get().setPacketHandled(true);
    }
}
