package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.client.ClientPacketHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** One encoded camera frame for the aiming scope: blit rows plus the frame's palette. */
public record ClientboundCameraFramePacket(BlockPos pos, int width, int height,
                                           String[] text, String[] fg, String[] bg, int[] palette) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
        for (int row = 0; row < height; row++) {
            buf.writeUtf(text[row]);
            buf.writeUtf(fg[row]);
            buf.writeUtf(bg[row]);
        }
        for (int i = 0; i < 16; i++) buf.writeInt(palette[i]);
    }

    public static ClientboundCameraFramePacket decode(FriendlyByteBuf buf) {
        var pos = buf.readBlockPos();
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        var text = new String[height];
        var fg = new String[height];
        var bg = new String[height];
        for (int row = 0; row < height; row++) {
            text[row] = buf.readUtf();
            fg[row] = buf.readUtf();
            bg[row] = buf.readUtf();
        }
        var palette = new int[16];
        for (int i = 0; i < 16; i++) palette[i] = buf.readInt();
        return new ClientboundCameraFramePacket(pos, width, height, text, fg, bg, palette);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.cameraFrame(this)));
        ctx.get().setPacketHandled(true);
    }
}
