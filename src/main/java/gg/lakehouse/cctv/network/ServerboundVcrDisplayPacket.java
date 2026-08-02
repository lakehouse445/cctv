package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Sets a deck's front-panel text; an empty string restores the automatic readout. */
public record ServerboundVcrDisplayPacket(BlockPos pos, String text) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(text, VcrBlockEntity.DISPLAY_CELLS);
    }

    public static ServerboundVcrDisplayPacket decode(FriendlyByteBuf buf) {
        return new ServerboundVcrDisplayPacket(buf.readBlockPos(), buf.readUtf(VcrBlockEntity.DISPLAY_CELLS));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = PacketHandler.validSender(ctx.get(), pos);
            if (player == null) return;
            if (!(player.level().getBlockEntity(pos) instanceof VcrBlockEntity vcr)) return;
            vcr.setDisplayText(text.isEmpty() ? null : text);
        });
        ctx.get().setPacketHandled(true);
    }
}
