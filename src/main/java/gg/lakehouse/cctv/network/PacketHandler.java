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

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ServerboundCaptureActionPacket.class,
            ServerboundCaptureActionPacket::encode, ServerboundCaptureActionPacket::decode, ServerboundCaptureActionPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundOpenCaptureScreenPacket.class,
            ClientboundOpenCaptureScreenPacket::encode, ClientboundOpenCaptureScreenPacket::decode, ClientboundOpenCaptureScreenPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundCaptureStatusPacket.class,
            ClientboundCaptureStatusPacket::encode, ClientboundCaptureStatusPacket::decode, ClientboundCaptureStatusPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundExportRecordingPacket.class,
            ClientboundExportRecordingPacket::encode, ClientboundExportRecordingPacket::decode, ClientboundExportRecordingPacket::handle);
        CHANNEL.registerMessage(id++, ServerboundPlaybackActionPacket.class,
            ServerboundPlaybackActionPacket::encode, ServerboundPlaybackActionPacket::decode, ServerboundPlaybackActionPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundOpenPlaybackScreenPacket.class,
            ClientboundOpenPlaybackScreenPacket::encode, ClientboundOpenPlaybackScreenPacket::decode, ClientboundOpenPlaybackScreenPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundPlaybackStatusPacket.class,
            ClientboundPlaybackStatusPacket::encode, ClientboundPlaybackStatusPacket::decode, ClientboundPlaybackStatusPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundOpenCameraScreenPacket.class,
            ClientboundOpenCameraScreenPacket::encode, ClientboundOpenCameraScreenPacket::decode, ClientboundOpenCameraScreenPacket::handle);
        CHANNEL.registerMessage(id++, ServerboundCameraAdjustPacket.class,
            ServerboundCameraAdjustPacket::encode, ServerboundCameraAdjustPacket::decode, ServerboundCameraAdjustPacket::handle);
        CHANNEL.registerMessage(id++, ClientboundCameraFramePacket.class,
            ClientboundCameraFramePacket::encode, ClientboundCameraFramePacket::decode, ClientboundCameraFramePacket::handle);
        CHANNEL.registerMessage(id++, ClientboundCameraLinksPacket.class,
            ClientboundCameraLinksPacket::encode, ClientboundCameraLinksPacket::decode, ClientboundCameraLinksPacket::handle);
    }

    public static void sendTo(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
