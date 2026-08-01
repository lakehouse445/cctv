package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.network.CaptureStatus;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.network.ServerboundCaptureActionPacket;
import gg.lakehouse.cctv.network.ServerboundCaptureActionPacket.Action;
import gg.lakehouse.cctv.tape.TapeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Placeholder GUI: status readout plus Record / Stop / Export buttons. Real art later. */
public class CaptureCardScreen extends Screen {
    private static final int[] FPS_OPTIONS = {1, 2, 5, 10, 20};
    private static final int REFRESH_TICKS = 5;
    /** An action's error must outlive the next poll reply, or it flashes for a frame. */
    private static final long ERROR_HOLD_MS = 3000;

    private CaptureStatus status;
    private String error = "";
    private long errorUntil;
    private int refreshCounter;
    private int fpsIndex = 2; // 5 fps
    private Button recordButton;
    private Button stopButton;
    private Button exportButton;
    private Button fpsButton;
    private Button sourceButton;

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
        exportButton = addRenderableWidget(Button.builder(Component.literal("Export MP4"), button -> send(Action.EXPORT))
            .bounds(centerX - 98, row + 26, 96, 20).build());
        sourceButton = addRenderableWidget(Button.builder(sourceLabel(), button -> send(Action.TOGGLE_SOURCE))
            .bounds(centerX + 2, row + 26, 96, 20).build());
        updateButtons();
    }

    private Component fpsLabel() {
        return Component.literal("FPS: " + FPS_OPTIONS[fpsIndex]);
    }

    private Component sourceLabel() {
        return Component.literal("Source: " + (status.sourceComputer() ? "Computer" : "Monitor"));
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
        if (!error.isEmpty()) {
            this.error = error;
            this.errorUntil = System.currentTimeMillis() + ERROR_HOLD_MS;
        } else if (System.currentTimeMillis() >= errorUntil) {
            this.error = "";
        }
        updateButtons();
    }

    /** Live tape/screen state while the screen is open, like the playback deck. */
    @Override
    public void tick() {
        if (++refreshCounter >= REFRESH_TICKS) {
            refreshCounter = 0;
            send(Action.REFRESH);
        }
    }

    private void updateButtons() {
        recordButton.active = !status.recording() && (status.hasMonitor() || status.hasComputer()) && status.hasTape();
        stopButton.active = status.recording();
        exportButton.active = !status.recording() && status.recordings() > 0;
        fpsButton.active = !status.recording();
        // The choice only exists when both screens are adjacent.
        sourceButton.visible = status.hasMonitor() && status.hasComputer();
        sourceButton.active = !status.recording();
        sourceButton.setMessage(sourceLabel());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int centerX = width / 2;
        int top = height / 2 - 62;
        graphics.drawCenteredString(font, title, centerX, top, 0xFFFFFF);

        // Mirrors the card's fallback: the chosen source when present, else
        // whichever screen exists.
        boolean computerFeed = status.sourceComputer()
            ? status.hasComputer()
            : !status.hasMonitor() && status.hasComputer();
        var screen = status.hasMonitor() || status.hasComputer()
            ? Component.literal("Screen: " + (computerFeed ? "computer" : "monitor") + " connected")
                .withStyle(ChatFormatting.GREEN)
            : Component.literal("Screen: no monitor or computer adjacent").withStyle(ChatFormatting.RED);
        graphics.drawCenteredString(font, screen, centerX, top + 16, 0xFFFFFF);

        var tape = status.hasTape()
            ? Component.literal("Tape: " + status.tapeLabel() + " (" + TapeItem.formatBytes(status.usedBytes())
                + " / " + TapeItem.formatBytes(status.capacityBytes()) + ", " + status.recordings() + " recordings)")
                .withStyle(ChatFormatting.AQUA)
            : Component.literal("Tape: none - right-click with a tape to insert").withStyle(ChatFormatting.RED);
        graphics.drawCenteredString(font, tape, centerX, top + 28, 0xFFFFFF);

        var state = status.recording()
            ? Component.literal("REC ● " + status.frames() + " frames @ " + status.fps() + " fps").withStyle(ChatFormatting.RED)
            : Component.literal("Idle").withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(font, state, centerX, top + 40, 0xFFFFFF);

        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error).withStyle(ChatFormatting.RED), centerX, top + 102, 0xFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
