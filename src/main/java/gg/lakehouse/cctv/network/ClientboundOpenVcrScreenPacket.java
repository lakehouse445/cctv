package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Opens the deck's display editor; text is the current custom readout, empty for automatic. */
public record ClientboundOpenVcrScreenPacket(BlockPos pos, String text) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(text, VcrBlockEntity.DISPLAY_CELLS);
    }

    public static ClientboundOpenVcrScreenPacket decode(FriendlyByteBuf buf) {
        return new ClientboundOpenVcrScreenPacket(buf.readBlockPos(), buf.readUtf(VcrBlockEntity.DISPLAY_CELLS));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.openVcrScreen(this)));
        ctx.get().setPacketHandled(true);
    }
}
