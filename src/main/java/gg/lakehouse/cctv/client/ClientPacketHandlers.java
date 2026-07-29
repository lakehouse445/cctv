package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.export.RecordingExporter;
import gg.lakehouse.cctv.network.ClientboundCameraFramePacket;
import gg.lakehouse.cctv.network.ClientboundCaptureStatusPacket;
import gg.lakehouse.cctv.network.ClientboundExportRecordingPacket;
import gg.lakehouse.cctv.network.ClientboundOpenCameraScreenPacket;
import gg.lakehouse.cctv.network.ClientboundOpenCaptureScreenPacket;
import gg.lakehouse.cctv.network.ClientboundOpenPlaybackScreenPacket;
import gg.lakehouse.cctv.network.ClientboundOpenVcrScreenPacket;
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

    private static final java.util.Map<Integer, byte[][]> PENDING_EXPORTS = new java.util.HashMap<>();

    public static void export(ClientboundExportRecordingPacket packet) {
        var chunks = PENDING_EXPORTS.computeIfAbsent(packet.exportId(), id -> new byte[packet.total()][]);
        if (packet.index() < 0 || packet.index() >= chunks.length) return;
        chunks[packet.index()] = packet.data();
        int size = 0;
        for (var chunk : chunks) {
            if (chunk == null) return;
            size += chunk.length;
        }
        PENDING_EXPORTS.remove(packet.exportId());
        var data = new byte[size];
        int offset = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, data, offset, chunk.length);
            offset += chunk.length;
        }
        RecordingExporter.export(data);
    }

    public static void openPlaybackScreen(ClientboundOpenPlaybackScreenPacket packet) {
        Minecraft.getInstance().setScreen(new PlaybackDeckScreen(packet.status()));
    }

    public static void openVcrScreen(ClientboundOpenVcrScreenPacket packet) {
        Minecraft.getInstance().setScreen(new VcrDisplayScreen(packet.pos(), packet.text()));
    }

    public static void playbackStatus(ClientboundPlaybackStatusPacket packet) {
        if (Minecraft.getInstance().screen instanceof PlaybackDeckScreen screen
            && screen.pos().equals(packet.status().pos())) {
            screen.setStatus(packet.status(), packet.error());
        }
    }

    public static void openCameraScreen(ClientboundOpenCameraScreenPacket packet) {
        Minecraft.getInstance().setScreen(new CameraScreen(packet.pos(), packet.yaw(), packet.pitch(), packet.zoom()));
    }

    public static void cameraFrame(ClientboundCameraFramePacket packet) {
        if (Minecraft.getInstance().screen instanceof CameraScreen screen
            && screen.pos().equals(packet.pos())) {
            screen.setFrame(packet);
        }
    }
}
