package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.camera.CameraBlockEntity;
import gg.lakehouse.cctv.network.ClientboundCameraFramePacket;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.network.ServerboundCameraAdjustPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Hand-aiming scope: shows the camera's own live picture (the same frames a
 * monitor gets) behind the camera-body overlay while dragging pans and tilts
 * the head. The overlay's side panel carries three segment-display readouts
 * (yaw, pitch, zoom), drawn with the VCR's LED font. The server clamps and
 * applies each aim change, so bystanders see the head move as you drag.
 */
public class CameraScreen extends Screen {
    private static final ResourceLocation OVERLAY =
        new ResourceLocation(CCTV.MOD_ID, "textures/gui/camera_overlay.png");
    private static final int TEX_W = 400;
    private static final int TEX_H = 256;
    /** The lens is the texture's left 256x256 square; its center anchors the overlay. */
    private static final int LENS_SIZE = 256;

    /** A rectangle in texture pixels. */
    private record TexRect(int x, int y, int w, int h) {
    }

    /** The see-through glass inside the lens frame; the picture draws only here. */
    private static final TexRect GLASS = new TexRect(24, 24, 208, 208);
    private static final TexRect YAW_LED = new TexRect(280, 56, 80, 24);
    private static final TexRect PITCH_LED = new TexRect(280, 120, 80, 24);
    private static final TexRect ZOOM_LED = new TexRect(280, 200, 64, 24);
    /** Lens height as a share of the window; the rest is breathing room. */
    private static final float LENS_FILL = 0.75F;

    private static final int REFRESH_TICKS = 2;
    private static final float DRAG_SENSITIVITY = 0.3F;

    private final BlockPos pos;
    private float yaw;
    private float pitch;
    private float zoom;
    @Nullable
    private DynamicTexture frameTexture;
    @Nullable
    private ResourceLocation frameTextureId;
    private int refreshCounter;
    private boolean aimDirty = true;

    public CameraScreen(BlockPos pos, float yaw, float pitch, float zoom) {
        super(Component.literal("Camera"));
        this.pos = pos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.zoom = zoom;
    }

    public BlockPos pos() {
        return pos;
    }

    /**
     * Bakes the teletext frame into a texture, once per packet. The scope
     * used to repaint ~21000 fill quads every render frame for a picture
     * that only changes ten times a second; now the change is a small
     * texture upload here and render() draws a single blit.
     */
    public void setFrame(ClientboundCameraFramePacket frame) {
        int pixelsWide = frame.width() * 2;
        int pixelsTall = frame.height() * 3;
        var pixels = frameTexture == null ? null : frameTexture.getPixels();
        if (pixels == null || pixels.getWidth() != pixelsWide || pixels.getHeight() != pixelsTall) {
            releaseFrameTexture();
            frameTexture = new DynamicTexture(pixelsWide, pixelsTall, false);
            frameTextureId = Minecraft.getInstance().getTextureManager().register("cctv_scope", frameTexture);
            pixels = frameTexture.getPixels();
            if (pixels == null) return;
        }
        for (int cy = 0; cy < frame.height(); cy++) {
            var text = frame.text()[cy];
            var fg = frame.fg()[cy];
            var bg = frame.bg()[cy];
            for (int cx = 0; cx < frame.width(); cx++) {
                char glyph = text.charAt(cx);
                int bits = glyph >= 128 && glyph < 160 ? glyph - 128 : 0;
                int fgColor = frame.palette()[Math.max(0, Character.digit(fg.charAt(cx), 16))];
                int bgColor = frame.palette()[Math.max(0, Character.digit(bg.charAt(cx), 16))];
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        int bit = sy * 2 + sx;
                        // Bit 5 (bottom-right) is always background in the teletext glyphs.
                        int color = bit < 5 && (bits >> bit & 1) != 0 ? fgColor : bgColor;
                        pixels.setPixelRGBA(cx * 2 + sx, cy * 3 + sy, toAbgr(color));
                    }
                }
            }
        }
        frameTexture.upload();
    }

    /** NativeImage packs 0xAABBGGRR; the frame palette is 0x00RRGGBB. */
    private static int toAbgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF0000) >> 16) | (rgb & 0xFF00) | ((rgb & 0xFF) << 16);
    }

    private void releaseFrameTexture() {
        if (frameTextureId != null) {
            Minecraft.getInstance().getTextureManager().release(frameTextureId);
            frameTextureId = null;
            frameTexture = null;
        }
    }

    @Override
    public void removed() {
        releaseFrameTexture();
        super.removed();
    }

    @Override
    public void tick() {
        if (++refreshCounter >= REFRESH_TICKS || aimDirty) {
            refreshCounter = 0;
            aimDirty = false;
            PacketHandler.CHANNEL.sendToServer(new ServerboundCameraAdjustPacket(pos, yaw, pitch, zoom,
                frameWidth(), frameHeight()));
        }
    }

    /** The glass is square; cells are 2x3 subpixels, so 3:2 cells read square. */
    private int frameHeight() {
        return 48;
    }

    private int frameWidth() {
        return frameHeight() * 3 / 2;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        zoom = (float) Mth.clamp(zoom * Math.pow(1.15, delta), 1, CameraBlockEntity.MAX_ZOOM);
        aimDirty = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            yaw = Mth.clamp(yaw + (float) dragX * DRAG_SENSITIVITY,
                -CameraBlockEntity.MAX_YAW, CameraBlockEntity.MAX_YAW);
            pitch = Mth.clamp(pitch - (float) dragY * DRAG_SENSITIVITY,
                -CameraBlockEntity.MAX_PITCH, CameraBlockEntity.MAX_PITCH);
            aimDirty = true;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 95% navy (the lens frame's own color): the world ghosts through
        // like keeping the off eye open.
        graphics.fill(0, 0, width, height, 0xF21B1E2A);
        // Lens centered, panel on its right; keep the panel on screen.
        float scale = Math.min(height * LENS_FILL / LENS_SIZE, (width / 2.0F - 8) / (TEX_W - LENS_SIZE / 2.0F));
        // Whole texture pixels only: fractional scales resample the pixel
        // art and cut stray lines through glyphs.
        scale = Math.max(1, (int) scale);
        int side = Math.round(LENS_SIZE * scale);
        int x = (width - side) / 2;
        int y = (height - side) / 2;
        var pixels = frameTexture == null ? null : frameTexture.getPixels();
        if (frameTextureId != null && pixels != null) {
            graphics.blit(frameTextureId,
                x + Math.round(GLASS.x() * scale), y + Math.round(GLASS.y() * scale),
                Math.round(GLASS.w() * scale), Math.round(GLASS.h() * scale),
                0.0F, 0.0F, pixels.getWidth(), pixels.getHeight(), pixels.getWidth(), pixels.getHeight());
        }
        renderScope(graphics, x, y, side, scale);

        var hint = Component.literal("Drag to aim · Scroll to zoom").withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(font, hint, width / 2, height - 12, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The overlay anchors on the lens: lens square dead center, side panel
     * off its right. The picture already sits inside the glass rect, so the
     * texture's transparent corners land on the black backdrop.
     */
    private void renderScope(GuiGraphics graphics, int x, int y, int side, float scale) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(OVERLAY, x, y, Math.round(TEX_W * scale), side, 0.0F, 0.0F, TEX_W, TEX_H, TEX_W, TEX_H);
        RenderSystem.disableBlend();

        drawLed(graphics, YAW_LED, x, y, scale, axisText(yaw));
        drawLed(graphics, PITCH_LED, x, y, scale, axisText(pitch));
        drawLed(graphics, ZOOM_LED, x, y, scale, String.format(Locale.ROOT, "%.1fx", zoom));
        drawLabel(graphics, YAW_LED, x, y, scale, 2.0F, 1.0F, "YAW:");
        drawLabel(graphics, PITCH_LED, x, y, scale, 2.0F, 1.0F, "PITCH:");
        drawLabel(graphics, ZOOM_LED, x, y, scale, 2.5F, 0.5F, "ZOOM:");
    }

    /** Printed label on the panel, centered just above its LED window. */
    private static final int LABEL_COLOR = 0xFF3E4459;
    /**
     * 25% navy pre-blended onto the panel beige, drawn opaque. A true alpha
     * shadow deforms: bold's double-draw blends its own overlap twice and
     * the body comes out darker than the fringes.
     */
    private static final int LABEL_SHADOW = 0xFFAFA499;

    /**
     * A recessed rim wraps each screen: 8 texture pixels of it sit above the
     * dark glass. Clearing the rim by this much keeps the shadow off it.
     */
    private static final float LABEL_BASE_GAP = 10;

    /**
     * size is texture pixels per font pixel; lift nudges the label up in
     * texture pixels. Both the glyph size and the position round to whole
     * screen pixels - fractional sampling cuts lines through glyphs.
     */
    private void drawLabel(GuiGraphics graphics, TexRect led, int x0, int y0, float scale,
                           float size, float lift, String text) {
        var label = Component.literal(text).withStyle(ChatFormatting.BOLD);
        var pose = graphics.pose();
        pose.pushPose();
        float s = Math.max(1, Math.round(size * scale));
        float leftTex = led.x() + led.w() / 2.0F - font.width(label) * s / scale / 2.0F;
        pose.translate(x0 + Math.round(leftTex * scale),
            y0 + Math.round((led.y() - LABEL_BASE_GAP - lift) * scale - 8 * s), 0);
        pose.scale(s, s, 1);
        graphics.drawString(font, label, 1, 1, LABEL_SHADOW, false);
        graphics.drawString(font, label, 0, 0, LABEL_COLOR, false);
        pose.popPose();
    }

    /** Signed readout; near-zero snaps to plain "0.0" so it never shows "-0.0". */
    private static String axisText(float value) {
        return String.format(Locale.ROOT, "%.1f", Math.abs(value) < 0.05F ? 0.0F : value);
    }

    /**
     * One right-aligned segment readout inside an LED window, the full ghost
     * grid underneath, exactly like the VCR's front panel.
     */
    private void drawLed(GuiGraphics graphics, TexRect led, int x0, int y0, float scale, String text) {
        var font = Minecraft.getInstance().font;
        float cellAdvance = VcrRenderer.advance(font, VcrRenderer.ALL_SEGMENTS);
        if (cellAdvance <= 0) return;
        float pitch = cellAdvance + VcrRenderer.KERN;
        // Glyphs are 9 dot-rows tall; keep 3px of bezel above and below.
        float glyphScale = (led.h() - 6) / VcrRenderer.GLYPH_VISUAL_HEIGHT;
        int cells = (int) ((led.w() - 2) / (pitch * glyphScale));
        if (cells <= 0) return;
        var padded = text.length() > cells ? text.substring(0, cells)
            : " ".repeat(cells - text.length()) + text;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x0 + (led.x() + led.w() / 2.0F) * scale, y0 + (led.y() + led.h() / 2.0F) * scale, 0);
        float s = glyphScale * scale;
        pose.scale(s, s, 1);
        Matrix4f matrix = pose.last().pose();
        float left = -cells * pitch / 2.0F;
        for (int i = 0; i < cells; i++) {
            float pen = left + i * pitch;
            var ghost = Component.literal(VcrRenderer.ALL_SEGMENTS).withStyle(VcrRenderer.SEGMENT_STYLE);
            font.drawInBatch(ghost, pen, VcrRenderer.TEXT_TOP, VcrRenderer.GHOST_COLOR, false, matrix,
                graphics.bufferSource(), Font.DisplayMode.NORMAL, 0, VcrRenderer.FULL_BRIGHT);
            char c = padded.charAt(i);
            if (c == ' ' || VcrRenderer.CHARSET.indexOf(c) < 0) continue;
            var lit = Component.literal(String.valueOf(c)).withStyle(VcrRenderer.SEGMENT_STYLE);
            font.drawInBatch(lit, pen, VcrRenderer.TEXT_TOP, VcrRenderer.LIT_COLOR, false, matrix,
                graphics.bufferSource(), Font.DisplayMode.POLYGON_OFFSET, 0, VcrRenderer.FULL_BRIGHT);
        }
        pose.popPose();
        graphics.flush();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
