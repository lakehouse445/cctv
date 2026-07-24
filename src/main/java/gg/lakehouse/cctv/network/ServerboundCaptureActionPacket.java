package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.capture.CaptureCardBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerboundCaptureActionPacket(BlockPos pos, Action action, int fps) {
    private static final java.util.concurrent.atomic.AtomicInteger EXPORT_IDS = new java.util.concurrent.atomic.AtomicInteger();

    public enum Action {
        RECORD, STOP, EXPORT
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(action);
        buf.writeVarInt(fps);
    }

    public static ServerboundCaptureActionPacket decode(FriendlyByteBuf buf) {
        return new ServerboundCaptureActionPacket(buf.readBlockPos(), buf.readEnum(Action.class), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 64) return;
            if (!(player.level().getBlockEntity(pos) instanceof CaptureCardBlockEntity captureCard)) return;

            String error = switch (action) {
                case RECORD -> captureCard.startRecording(fps);
                case STOP -> captureCard.stopRecording();
                case EXPORT -> export(captureCard, player);
            };
            PacketHandler.sendTo(player, new ClientboundCaptureStatusPacket(captureCard.status(), error == null ? "" : error));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String export(CaptureCardBlockEntity captureCard, ServerPlayer player) {
        if (captureCard.isRecording()) return "Stop recording first";
        if (!captureCard.hasTape()) return "No tape in the capture card";
        var data = captureCard.exportLatest();
        if (data == null) return "Nothing on this tape";
        int chunkBytes = ClientboundExportRecordingPacket.CHUNK_BYTES;
        int total = (data.length + chunkBytes - 1) / chunkBytes;
        int exportId = EXPORT_IDS.incrementAndGet();
        for (int index = 0; index < total; index++) {
            int from = index * chunkBytes;
            var chunk = java.util.Arrays.copyOfRange(data, from, Math.min(data.length, from + chunkBytes));
            PacketHandler.sendTo(player, new ClientboundExportRecordingPacket(exportId, index, total, chunk));
        }
        return null;
    }
}
