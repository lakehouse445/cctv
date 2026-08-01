package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.CCTV;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class PacketHandler {
    private static final String PROTOCOL = "1";

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
}
