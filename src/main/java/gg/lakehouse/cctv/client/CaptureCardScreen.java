package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.network.CaptureStatus;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.network.ServerboundCaptureActionPacket;
import gg.lakehouse.cctv.network.ServerboundCaptureActionPacket.Action;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Placeholder GUI: status readout plus Record / Stop / Export buttons. Real art later. */
public class CaptureCardScreen extends Screen {
    private static final int[] FPS_OPTIONS = {1, 2, 5, 10, 20};

    private CaptureStatus status;
    private String error = "";
    private int fpsIndex = 2; // 5 fps
    private Button recordButton;
    private Button stopButton;
    private Button exportButton;
    private Button fpsButton;

    public CaptureCardScreen(CaptureStatus status) {
        super(Component.literal("Capture Card"));
        this.status = status;
    }

    public BlockPos pos() {
        return status.pos();
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int row = height / 2;
        fpsButton = addRenderableWidget(Button.builder(fpsLabel(), button -> cycleFps())
            .bounds(centerX - 98, row, 60, 20).build());
        recordButton = addRenderableWidget(Button.builder(Component.literal("Record"), button -> send(Action.RECORD))
            .bounds(centerX - 32, row, 60, 20).build());
        stopButton = addRenderableWidget(Button.builder(Component.literal("Stop"), button -> send(Action.STOP))
            .bounds(centerX + 34, row, 60, 20).build());
        exportButton = addRenderableWidget(Button.builder(Component.literal("Export GIF"), button -> send(Action.EXPORT))
            .bounds(centerX - 49, row + 26, 98, 20).build());
        updateButtons();
    }

    private Component fpsLabel() {
        return Component.literal("FPS: " + FPS_OPTIONS[fpsIndex]);
    }

    private void cycleFps() {
        fpsIndex = (fpsIndex + 1) % FPS_OPTIONS.length;
        fpsButton.setMessage(fpsLabel());
    }

    private void send(Action action) {
        PacketHandler.CHANNEL.sendToServer(new ServerboundCaptureActionPacket(status.pos(), action, FPS_OPTIONS[fpsIndex]));
    }

    public void setStatus(CaptureStatus status, String error) {
        this.status = status;
        this.error = error;
        updateButtons();
    }

    private void updateButtons() {
        recordButton.active = !status.recording() && status.hasMonitor();
        stopButton.active = status.recording();
        exportButton.active = !status.recording() && status.frames() > 0;
        fpsButton.active = !status.recording();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int centerX = width / 2;
        int top = height / 2 - 50;
        graphics.drawCenteredString(font, title, centerX, top, 0xFFFFFF);

        var monitor = status.hasMonitor()
            ? Component.literal("Monitor: connected").withStyle(ChatFormatting.GREEN)
            : Component.literal("Monitor: none adjacent").withStyle(ChatFormatting.RED);
        graphics.drawCenteredString(font, monitor, centerX, top + 16, 0xFFFFFF);

        var state = status.recording()
            ? Component.literal("REC ● " + status.frames() + " frames @ " + status.fps() + " fps").withStyle(ChatFormatting.RED)
            : Component.literal(status.frames() > 0 ? status.frames() + " frames ready to export" : "Idle").withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(font, state, centerX, top + 28, 0xFFFFFF);

        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error).withStyle(ChatFormatting.RED), centerX, top + 90, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
