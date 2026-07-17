package gg.lakehouse.cctv.export;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.media.TermFrame;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Decodes recording data and writes the GIF to <gameDir>/cctv-exports, like screenshots. */
public final class RecordingExporter {
    private RecordingExporter() {
    }

    public static void export(byte[] data) {
        Util.ioPool().execute(() -> {
            try {
                var recording = TermFrame.readAll(data);
                var directory = new File(Minecraft.getInstance().gameDirectory, "cctv-exports");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create " + directory);
                }
                var name = "rec_" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".gif";
                var file = new File(directory, name);
                try (var output = ImageIO.createImageOutputStream(file);
                     var gif = new GifSequenceWriter(output, 100 / Math.max(1, recording.fps()))) {
                    for (var frame : recording.frames()) {
                        gif.writeFrame(TermRenderer.render(frame));
                    }
                }
                sendChat(Component.literal("Recording exported: ")
                    .append(Component.literal(name).withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath())))));
            } catch (Exception e) {
                CCTV.LOGGER.error("Failed to export recording", e);
                sendChat(Component.literal("Export failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
        });
    }

    private static void sendChat(Component message) {
        var minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.getChat().addMessage(message));
    }
}
