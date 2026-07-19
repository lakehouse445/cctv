package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.export.RecordingExporter;
import gg.lakehouse.cctv.network.ClientboundCaptureStatusPacket;
import gg.lakehouse.cctv.network.ClientboundExportRecordingPacket;
import gg.lakehouse.cctv.network.ClientboundOpenCaptureScreenPacket;
import gg.lakehouse.cctv.network.ClientboundOpenPlaybackScreenPacket;
import gg.lakehouse.cctv.network.ClientboundPlaybackStatusPacket;
import net.minecraft.client.Minecraft;

/** Client-only packet handling; only ever classloaded on the client dist. */
public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void openScreen(ClientboundOpenCaptureScreenPacket packet) {
        Minecraft.getInstance().setScreen(new CaptureCardScreen(packet.status()));
    }

    public static void status(ClientboundCaptureStatusPacket packet) {
        if (Minecraft.getInstance().screen instanceof CaptureCardScreen screen
            && screen.pos().equals(packet.status().pos())) {
            screen.setStatus(packet.status(), packet.error());
        }
    }

    public static void export(ClientboundExportRecordingPacket packet) {
        RecordingExporter.export(packet.data());
    }

    public static void openPlaybackScreen(ClientboundOpenPlaybackScreenPacket packet) {
        Minecraft.getInstance().setScreen(new PlaybackDeckScreen(packet.status()));
    }

    public static void playbackStatus(ClientboundPlaybackStatusPacket packet) {
        if (Minecraft.getInstance().screen instanceof PlaybackDeckScreen screen
            && screen.pos().equals(packet.status().pos())) {
            screen.setStatus(packet.status(), packet.error());
        }
    }
}
