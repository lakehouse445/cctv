package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundPlaybackStatusPacket(PlaybackStatus status, String error) {
    public void encode(FriendlyByteBuf buf) {
        status.write(buf);
        buf.writeUtf(error);
    }

    public static ClientboundPlaybackStatusPacket decode(FriendlyByteBuf buf) {
        return new ClientboundPlaybackStatusPacket(PlaybackStatus.read(buf), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.playbackStatus(this)));
        ctx.get().setPacketHandled(true);
    }
}
