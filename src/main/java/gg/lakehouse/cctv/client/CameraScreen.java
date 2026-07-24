package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import gg.lakehouse.cctv.camera.CameraBlockEntity;
import gg.lakehouse.cctv.network.ClientboundCameraFramePacket;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.network.ServerboundCameraAdjustPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;

/**
 * Hand-aiming scope: shows the camera's own live picture (the same frames a
 * monitor gets) behind a spyglass overlay while dragging pans and tilts the
 * head. The server clamps and applies each aim change, so bystanders see the
 * head move as you drag.
 */
public class CameraScreen extends Screen {
    private static final ResourceLocation SPYGLASS_SCOPE = new ResourceLocation("textures/misc/spyglass_scope.png");
    private static final int REFRESH_TICKS = 2;
    private static final float DRAG_SENSITIVITY = 0.3F;

    private final BlockPos pos;
    private float yaw;
    private float pitch;
    private float zoom;
    @Nullable
    private ClientboundCameraFramePacket frame;
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

    public void setFrame(ClientboundCameraFramePacket frame) {
        this.frame = frame;
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

    /** Frame cell dimensions picked to roughly match the window's aspect. */
    private int frameHeight() {
        return 48;
    }

    private int frameWidth() {
        double aspect = width / (double) Math.max(1, height);
        return Mth.clamp((int) Math.round(frameHeight() * 1.5 * aspect), 8, CameraBlockEntity.MAX_WIDTH);
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
        graphics.fill(0, 0, width, height, 0xFF000000);
        if (frame != null) renderFrame(graphics);
        renderScope(graphics);

        var aim = Component.literal(String.format("Yaw %+.1f°  Pitch %+.1f°  Zoom %.1fx", yaw, pitch, zoom))
            .withStyle(ChatFormatting.WHITE);
        graphics.drawCenteredString(font, aim, width / 2, height - 24, 0xFFFFFF);
        var hint = Component.literal("Drag to aim · Scroll to zoom").withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(font, hint, width / 2, height - 12, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the teletext frame: each cell is 2x3 subpixels, scaled to fill the window. */
    private void renderFrame(GuiGraphics graphics) {
        int pixelsWide = frame.width() * 2;
        int pixelsTall = frame.height() * 3;
        for (int cy = 0; cy < frame.height(); cy++) {
            var text = frame.text()[cy];
            var fg = frame.fg()[cy];
            var bg = frame.bg()[cy];
            for (int cx = 0; cx < frame.width(); cx++) {
                char glyph = text.charAt(cx);
                int bits = glyph >= 128 && glyph < 160 ? glyph - 128 : 0;
                int fgColor = 0xFF000000 | frame.palette()[Math.max(0, Character.digit(fg.charAt(cx), 16))];
                int bgColor = 0xFF000000 | frame.palette()[Math.max(0, Character.digit(bg.charAt(cx), 16))];
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 2; sx++) {
                        int bit = sy * 2 + sx;
                        // Bit 5 (bottom-right) is always background in the teletext glyphs.
                        int color = bit < 5 && (bits >> bit & 1) != 0 ? fgColor : bgColor;
                        int px = cx * 2 + sx;
                        int py = cy * 3 + sy;
                        graphics.fill(px * width / pixelsWide, py * height / pixelsTall,
                            (px + 1) * width / pixelsWide, (py + 1) * height / pixelsTall, color);
                    }
                }
            }
        }
    }

    private void renderScope(GuiGraphics graphics) {
        int side = Math.min(width, height);
        int x = (width - side) / 2;
        int y = (height - side) / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(SPYGLASS_SCOPE, x, y, 0, 0.0F, 0.0F, side, side, side, side);
        RenderSystem.disableBlend();
        graphics.fill(0, 0, x, height, 0xFF000000);
        graphics.fill(x + side, 0, width, height, 0xFF000000);
        graphics.fill(x, 0, x + side, y, 0xFF000000);
        graphics.fill(x, y + side, x + side, height, 0xFF000000);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
