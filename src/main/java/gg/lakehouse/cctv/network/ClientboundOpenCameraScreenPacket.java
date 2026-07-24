package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundOpenCameraScreenPacket(BlockPos pos, float yaw, float pitch, float zoom) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeFloat(zoom);
    }

    public static ClientboundOpenCameraScreenPacket decode(FriendlyByteBuf buf) {
        return new ClientboundOpenCameraScreenPacket(buf.readBlockPos(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.openCameraScreen(this)));
        ctx.get().setPacketHandled(true);
    }
}
