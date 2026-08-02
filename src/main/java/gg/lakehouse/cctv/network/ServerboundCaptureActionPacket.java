package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.capture.CaptureCardBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerboundCaptureActionPacket(BlockPos pos, Action action, int fps) {
    private static final java.util.concurrent.atomic.AtomicInteger EXPORT_IDS = new java.util.concurrent.atomic.AtomicInteger();
    /** An export pushes a whole recording to the client; no request loops. */
    private static final java.util.Map<java.util.UUID, Long> LAST_EXPORT_TICK =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int EXPORT_COOLDOWN_TICKS = 100;

    public static void clearThrottle() {
        LAST_EXPORT_TICK.clear();
    }

    /** REFRESH is appended last: enum ordinals are the wire format. */
    public enum Action {
        RECORD, STOP, EXPORT, TOGGLE_SOURCE, REFRESH
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(action);
        buf.writeVarInt(fps);
    }

    public static ServerboundCaptureActionPacket decode(FriendlyByteBuf buf) {
        return new ServerboundCaptureActionPacket(buf.readBlockPos(),
            PacketHandler.readEnum(buf, Action.class), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = PacketHandler.validSender(ctx.get(), pos);
            if (player == null) return;
            if (!(player.level().getBlockEntity(pos) instanceof CaptureCardBlockEntity captureCard)) return;

            String error = switch (action) {
                case RECORD -> captureCard.startRecording(fps);
                case STOP -> captureCard.stopRecording();
                case EXPORT -> export(captureCard, player);
                case TOGGLE_SOURCE -> captureCard.setSource(
                    captureCard.source() == CaptureCardBlockEntity.Source.MONITOR
                        ? CaptureCardBlockEntity.Source.COMPUTER
                        : CaptureCardBlockEntity.Source.MONITOR);
                case REFRESH -> null; // status poll; the reply below is the payload
            };
            PacketHandler.sendTo(player, new ClientboundCaptureStatusPacket(captureCard.status(), error == null ? "" : error));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String export(CaptureCardBlockEntity captureCard, ServerPlayer player) {
        if (captureCard.isRecording()) return "Stop recording first";
        if (!captureCard.hasTape()) return "No tape in the capture card";
        long now = player.serverLevel().getGameTime();
        var lastTick = LAST_EXPORT_TICK.get(player.getUUID());
        if (lastTick != null && now - lastTick < EXPORT_COOLDOWN_TICKS) {
            return "Export cooling down - try again in a moment";
        }
        LAST_EXPORT_TICK.put(player.getUUID(), now);
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
