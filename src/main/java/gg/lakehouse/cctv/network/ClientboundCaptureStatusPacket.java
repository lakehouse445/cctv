package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundCaptureStatusPacket(CaptureStatus status, String error) {
    public void encode(FriendlyByteBuf buf) {
        status.write(buf);
        buf.writeUtf(error);
    }

    public static ClientboundCaptureStatusPacket decode(FriendlyByteBuf buf) {
        return new ClientboundCaptureStatusPacket(CaptureStatus.read(buf), buf.readUtf());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.status(this)));
        ctx.get().setPacketHandled(true);
    }
}
