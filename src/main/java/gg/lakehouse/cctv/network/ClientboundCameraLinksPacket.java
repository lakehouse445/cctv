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
        var entries = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readLong(), buf.readLong(), buf.readLongArray()));
        }
        return new ClientboundCameraLinksPacket(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> gg.lakehouse.cctv.client.CameraLinkRenderer.accept(this)));
        ctx.get().setPacketHandled(true);
    }
}
