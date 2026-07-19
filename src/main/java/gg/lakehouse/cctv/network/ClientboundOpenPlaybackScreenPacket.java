package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundOpenPlaybackScreenPacket(PlaybackStatus status) {
    public void encode(FriendlyByteBuf buf) {
        status.write(buf);
    }

    public static ClientboundOpenPlaybackScreenPacket decode(FriendlyByteBuf buf) {
        return new ClientboundOpenPlaybackScreenPacket(PlaybackStatus.read(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.openPlaybackScreen(this)));
        ctx.get().setPacketHandled(true);
    }
}
