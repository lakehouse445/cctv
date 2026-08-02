package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.camera.CameraBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Aim update from the hand-adjust scope; the server clamps, applies (unless
 * the camera is locked) and answers with a fresh frame at the requested size.
 */
public record ServerboundCameraAdjustPacket(BlockPos pos, float yaw, float pitch, float zoom, int width, int height) {
    /**
     * One frame render per player per tick. A render is up to 13k raycasts
     * on the server thread; the stock scope sends at most one packet per
     * tick, so only a modified client ever hits this.
     */
    private static final java.util.Map<java.util.UUID, Long> LAST_RENDER_TICK =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static void clearThrottle() {
        LAST_RENDER_TICK.clear();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeFloat(zoom);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
    }

    public static ServerboundCameraAdjustPacket decode(FriendlyByteBuf buf) {
        return new ServerboundCameraAdjustPacket(buf.readBlockPos(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = PacketHandler.validSender(ctx.get(), pos);
            if (player == null) return;
            if (!(player.level().getBlockEntity(pos) instanceof CameraBlockEntity camera)) return;
            // NaN slides through every clamp and would sync to all viewers.
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(zoom)) return;
            long gameTime = player.serverLevel().getGameTime();
            var lastTick = LAST_RENDER_TICK.put(player.getUUID(), gameTime);
            if (lastTick != null && lastTick >= gameTime) return;

            if (!camera.isLocked()) {
                if (camera.getYaw() != yaw) camera.setYaw(yaw);
                if (camera.getPitch() != pitch) camera.setPitch(pitch);
                if (camera.getZoom() != zoom) camera.setZoom(zoom);
            }
            int w = Mth.clamp(width, 1, CameraBlockEntity.MAX_WIDTH);
            int h = Mth.clamp(height, 1, CameraBlockEntity.MAX_HEIGHT);
            var frame = camera.renderFrame(w, h);
            if (frame == null) return;
            PacketHandler.sendTo(player, new ClientboundCameraFramePacket(pos, frame.width(), frame.height(),
                frame.text(), frame.fg(), frame.bg(), frame.palette()));
        });
        ctx.get().setPacketHandled(true);
    }
}
