package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.CCTV;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class PacketHandler {
    /**
     * Bump on ANY packet change: added, removed, reordered, or a changed
     * field list. Two builds that differ but share a version connect and
     * then mis-decode each other's packets.
     * History: 1 = 0.9.x pre-VCR-screen; 2 = 0.9.15.4 (VCR screen packets,
     * capture REFRESH).
     */
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(CCTV.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private PacketHandler() {
    }

    private static final java.util.Optional<net.minecraftforge.network.NetworkDirection> TO_CLIENT =
        java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT);
    private static final java.util.Optional<net.minecraftforge.network.NetworkDirection> TO_SERVER =
        java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER);

    public static void register() {
        // Every registration pins its direction: without it Forge skips
        // direction validation and a client could feed the server
        // clientbound packets (screen opens, exports) as if it were a peer.
        int id = 0;
        CHANNEL.registerMessage(id++, ServerboundCaptureActionPacket.class,
            ServerboundCaptureActionPacket::encode, ServerboundCaptureActionPacket::decode, ServerboundCaptureActionPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(id++, ClientboundOpenCaptureScreenPacket.class,
            ClientboundOpenCaptureScreenPacket::encode, ClientboundOpenCaptureScreenPacket::decode, ClientboundOpenCaptureScreenPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ClientboundCaptureStatusPacket.class,
            ClientboundCaptureStatusPacket::encode, ClientboundCaptureStatusPacket::decode, ClientboundCaptureStatusPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ClientboundExportRecordingPacket.class,
            ClientboundExportRecordingPacket::encode, ClientboundExportRecordingPacket::decode, ClientboundExportRecordingPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ServerboundPlaybackActionPacket.class,
            ServerboundPlaybackActionPacket::encode, ServerboundPlaybackActionPacket::decode, ServerboundPlaybackActionPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(id++, ClientboundOpenPlaybackScreenPacket.class,
            ClientboundOpenPlaybackScreenPacket::encode, ClientboundOpenPlaybackScreenPacket::decode, ClientboundOpenPlaybackScreenPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ClientboundPlaybackStatusPacket.class,
            ClientboundPlaybackStatusPacket::encode, ClientboundPlaybackStatusPacket::decode, ClientboundPlaybackStatusPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ClientboundOpenCameraScreenPacket.class,
            ClientboundOpenCameraScreenPacket::encode, ClientboundOpenCameraScreenPacket::decode, ClientboundOpenCameraScreenPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ServerboundCameraAdjustPacket.class,
            ServerboundCameraAdjustPacket::encode, ServerboundCameraAdjustPacket::decode, ServerboundCameraAdjustPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(id++, ClientboundCameraFramePacket.class,
            ClientboundCameraFramePacket::encode, ClientboundCameraFramePacket::decode, ClientboundCameraFramePacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ClientboundCameraLinksPacket.class,
            ClientboundCameraLinksPacket::encode, ClientboundCameraLinksPacket::decode, ClientboundCameraLinksPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ClientboundOpenVcrScreenPacket.class,
            ClientboundOpenVcrScreenPacket::encode, ClientboundOpenVcrScreenPacket::decode, ClientboundOpenVcrScreenPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(id++, ServerboundVcrDisplayPacket.class,
            ServerboundVcrDisplayPacket::encode, ServerboundVcrDisplayPacket::decode, ServerboundVcrDisplayPacket::handle, TO_SERVER);
    }

    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /**
     * readEnum without the ArrayIndexOutOfBoundsException a hostile ordinal
     * causes; DecoderException is the channel's own reject path.
     */
    static <E extends Enum<E>> E readEnum(net.minecraft.network.FriendlyByteBuf buf, Class<E> type) {
        int ordinal = buf.readVarInt();
        var values = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new io.netty.handler.codec.DecoderException(
                "Bad " + type.getSimpleName() + " ordinal " + ordinal);
        }
        return values[ordinal];
    }

    /**
     * Shared gate for serverbound handlers: a real, non-spectator sender
     * within vanilla container reach of the block. Null means drop the packet.
     */
    @javax.annotation.Nullable
    static ServerPlayer validSender(net.minecraftforge.network.NetworkEvent.Context ctx,
                                    net.minecraft.core.BlockPos pos) {
        var player = ctx.getSender();
        if (player == null || player.isSpectator()) return null;
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64) return null;
        return player;
    }
}
