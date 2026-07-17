package gg.lakehouse.cctv.export;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;

/** Minimal animated GIF writer over javax.imageio — no external libraries. */
public final class GifSequenceWriter implements Closeable {
    private final ImageWriter writer;
    private final ImageWriteParam params;
    private final IIOMetadata metadata;

    public GifSequenceWriter(ImageOutputStream output, int delayCentiseconds) throws IOException {
        var writers = ImageIO.getImageWritersBySuffix("gif");
        if (!writers.hasNext()) throw new IOException("No GIF writer available");
        writer = writers.next();
        params = writer.getDefaultWriteParam();

        var imageType = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB);
        metadata = writer.getDefaultImageMetadata(imageType, params);
        var formatName = metadata.getNativeMetadataFormatName();
        var root = (IIOMetadataNode) metadata.getAsTree(formatName);

        var graphicControl = childNode(root, "GraphicControlExtension");
        graphicControl.setAttribute("disposalMethod", "none");
        graphicControl.setAttribute("userInputFlag", "FALSE");
        graphicControl.setAttribute("transparentColorFlag", "FALSE");
        graphicControl.setAttribute("delayTime", Integer.toString(Math.max(2, delayCentiseconds)));
        graphicControl.setAttribute("transparentColorIndex", "0");

        var applicationExtensions = childNode(root, "ApplicationExtensions");
        var netscape = new IIOMetadataNode("ApplicationExtension");
        netscape.setAttribute("applicationID", "NETSCAPE");
        netscape.setAttribute("authenticationCode", "2.0");
        netscape.setUserObject(new byte[]{1, 0, 0}); // loop forever
        applicationExtensions.appendChild(netscape);

        metadata.setFromTree(formatName, root);
        writer.setOutput(output);
        writer.prepareWriteSequence(null);
    }

    private static IIOMetadataNode childNode(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        var node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    public void writeFrame(BufferedImage image) throws IOException {
        writer.writeToSequence(new IIOImage(image, null, metadata), params);
    }

    @Override
    public void close() throws IOException {
        writer.endWriteSequence();
        writer.dispose();
    }
}
