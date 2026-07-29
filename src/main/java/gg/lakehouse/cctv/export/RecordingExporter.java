package gg.lakehouse.cctv.export;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.media.TermFrame;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rational;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Decodes recording data and writes an MP4 to <gameDir>/cctv-exports, like
 * screenshots. H.264 carries every frame's exact colors — no palette
 * quantization — and identical frames cost almost nothing.
 */
public final class RecordingExporter {
    private RecordingExporter() {
    }

    public static void export(byte[] data) {
        Util.ioPool().execute(() -> {
            try {
                var recording = TermFrame.readAll(data);
                if (recording.frames().isEmpty()) throw new IOException("Recording has no frames");
                var directory = new File(Minecraft.getInstance().gameDirectory, "cctv-exports");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create " + directory);
                }
                var name = "rec_" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".mp4";
                var file = new File(directory, name);
                var channel = NIOUtils.writableChannel(file);
                try {
                    var encoder = SequenceEncoder.createWithFps(channel, Rational.R(Math.max(1, recording.fps()), 1));
                    for (var frame : recording.frames()) {
                        encoder.encodeNativeFrame(toPicture(padToEven(
                            TermRenderer.render(frame, recording.monitor()))));
                    }
                    encoder.finish();
                } finally {
                    NIOUtils.closeQuietly(channel);
                }
                sendChat(Component.literal("Recording exported: ")
                    .append(Component.literal(name).withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath())))));
            } catch (Throwable e) {
                CCTV.LOGGER.error("Failed to export recording", e);
                sendChat(Component.literal("Export failed: " + e).withStyle(ChatFormatting.RED));
            }
        });
    }

    /** JCodec's native RGB frame: interleaved r,g,b bytes biased by -128. */
    private static Picture toPicture(BufferedImage image) {
        var picture = Picture.create(image.getWidth(), image.getHeight(), ColorSpace.RGB);
        var data = picture.getPlaneData(0);
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                data[offset++] = (byte) (((rgb >> 16) & 0xFF) - 128);
                data[offset++] = (byte) (((rgb >> 8) & 0xFF) - 128);
                data[offset++] = (byte) ((rgb & 0xFF) - 128);
            }
        }
        return picture;
    }

    /** H.264 needs even dimensions; pads by duplicating the last row/column. */
    private static BufferedImage padToEven(BufferedImage image) {
        int width = image.getWidth() + (image.getWidth() & 1);
        int height = image.getHeight() + (image.getHeight() & 1);
        if (width == image.getWidth() && height == image.getHeight()) return image;
        var padded = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = padded.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        for (int x = image.getWidth(); x < width; x++) {
            for (int y = 0; y < image.getHeight(); y++) padded.setRGB(x, y, image.getRGB(image.getWidth() - 1, y));
        }
        for (int y = image.getHeight(); y < height; y++) {
            for (int x = 0; x < width; x++) padded.setRGB(x, y, padded.getRGB(x, image.getHeight() - 1));
        }
        return padded;
    }

    private static void sendChat(Component message) {
        var minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.getChat().addMessage(message));
    }
}
