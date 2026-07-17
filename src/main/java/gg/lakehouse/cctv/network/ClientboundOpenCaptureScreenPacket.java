package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundOpenCaptureScreenPacket(CaptureStatus status) {
    public void encode(FriendlyByteBuf buf) {
        status.write(buf);
    }

    public static ClientboundOpenCaptureScreenPacket decode(FriendlyByteBuf buf) {
        return new ClientboundOpenCaptureScreenPacket(CaptureStatus.read(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.openScreen(this)));
        ctx.get().setPacketHandled(true);
    }
}
