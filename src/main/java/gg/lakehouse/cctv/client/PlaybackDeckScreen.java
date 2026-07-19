package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.network.PlaybackStatus;
import gg.lakehouse.cctv.network.ServerboundPlaybackActionPacket;
import gg.lakehouse.cctv.network.ServerboundPlaybackActionPacket.Action;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Playback deck controls: transport buttons, a clickable progress bar, and the
 * tape's recordings as a selectable list. Polls the deck for live position
 * while it is open. Placeholder art; the block model is the real one.
 */
public class PlaybackDeckScreen extends Screen {
    private static final int REFRESH_TICKS = 5;
    private static final int PAGE_SIZE = 4;
    private static final int BAR_WIDTH = 200;

    private PlaybackStatus status;
    private String error = "";
    private int page;
    private String listSignature = "";
    private int refreshCounter;

    private int centerX;
    private int contentTop;
    private int barX;
    private int barY;

    private Button playButton;
    private Button pauseButton;
    private Button stopButton;
    private Button rewindButton;
    private Button fastForwardButton;
    private Button ejectButton;
    private Button prevButton;
    private Button nextButton;
    private final List<Button> recordingButtons = new ArrayList<>();

    public PlaybackDeckScreen(PlaybackStatus status) {
        super(Component.literal("Playback Deck"));
        this.status = status;
    }

    public BlockPos pos() {
        return status.pos();
    }

    @Override
    protected void init() {
        centerX = width / 2;
        contentTop = height / 2 - 120;
        barX = centerX - BAR_WIDTH / 2;
        barY = contentTop + 60;

        int buttonW = 42;
        int gap = 3;
        int rowLeft = centerX - (buttonW * 5 + gap * 4) / 2;
        int transportY = contentTop + 88;
        playButton = addRenderableWidget(Button.builder(Component.literal("Play"), b -> sendAction(Action.PLAY))
            .bounds(rowLeft, transportY, buttonW, 20).build());
        pauseButton = addRenderableWidget(Button.builder(Component.literal("Pause"), b -> sendAction(Action.PAUSE))
            .bounds(rowLeft + (buttonW + gap), transportY, buttonW, 20).build());
        stopButton = addRenderableWidget(Button.builder(Component.literal("Stop"), b -> sendAction(Action.STOP))
            .bounds(rowLeft + (buttonW + gap) * 2, transportY, buttonW, 20).build());
        rewindButton = addRenderableWidget(Button.builder(Component.literal("Rewind"), b -> sendAction(Action.REWIND))
            .bounds(rowLeft + (buttonW + gap) * 3, transportY, buttonW, 20).build());
        fastForwardButton = addRenderableWidget(Button.builder(Component.literal("FF"), b -> sendAction(Action.FAST_FORWARD))
            .bounds(rowLeft + (buttonW + gap) * 4, transportY, buttonW, 20).build());
        ejectButton = addRenderableWidget(Button.builder(Component.literal("Eject tape"), b -> sendAction(Action.EJECT))
            .bounds(centerX - 45, contentTop + 112, 90, 20).build());

        int pagingY = contentTop + 150 + PAGE_SIZE * 20 + 2;
        prevButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
            .bounds(centerX - 100, pagingY, 20, 18).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
            .bounds(centerX + 80, pagingY, 20, 18).build());

        listSignature = "";
        rebuildList();
        updateButtons();
    }

    public void setStatus(PlaybackStatus status, String error) {
        this.status = status;
        this.error = error;
        rebuildList();
        updateButtons();
    }

    private void changePage(int delta) {
        int pages = Math.max(1, (status.recordings().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Mth.clamp(page + delta, 0, pages - 1);
        listSignature = ""; // force a rebuild for the new page
        rebuildList();
        updateButtons();
    }

    /** Rebuild the recording buttons only when the list content, current recording or page changes. */
    private void rebuildList() {
        int pages = Math.max(1, (status.recordings().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Mth.clamp(page, 0, pages - 1);
        var signature = page + "|" + status.recordingName() + "|"
            + String.join(",", status.recordings().stream().map(PlaybackStatus.Entry::name).toList());
        if (signature.equals(listSignature)) return;
        listSignature = signature;

        for (var button : recordingButtons) removeWidget(button);
        recordingButtons.clear();

        int listTop = contentTop + 150;
        int start = page * PAGE_SIZE;
        var recordings = status.recordings();
        for (int i = 0; i < PAGE_SIZE && start + i < recordings.size(); i++) {
            var entry = recordings.get(start + i);
            boolean current = entry.name().equals(status.recordingName());
            var label = Component.literal((current ? "▶ " : "") + entry.name() + "   " + formatTime(entry.seconds()));
            var button = Button.builder(label, b -> sendSelect(entry.name()))
                .bounds(centerX - 100, listTop + i * 20, 200, 18).build();
            recordingButtons.add(addRenderableWidget(button));
        }
    }

    private void updateButtons() {
        boolean playing = "playing".equals(status.state());
        boolean rewinding = "rewinding".equals(status.state());
        playButton.active = status.hasTape() && status.hasMonitor() && !playing;
        pauseButton.active = playing || rewinding;
        stopButton.active = playing || rewinding;
        rewindButton.active = status.hasTape() && !rewinding && status.position() > 0.05;
        fastForwardButton.active = status.hasTape()
            && (status.length() <= 0 || status.position() < status.length() - 0.05);
        ejectButton.active = status.hasTape();

        int pages = Math.max(1, (status.recordings().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        boolean paged = status.recordings().size() > PAGE_SIZE;
        prevButton.visible = paged;
        nextButton.visible = paged;
        prevButton.active = page > 0;
        nextButton.active = page < pages - 1;
        for (var button : recordingButtons) button.active = status.hasMonitor();
    }

    @Override
    public void tick() {
        if (++refreshCounter >= REFRESH_TICKS) {
            refreshCounter = 0;
            send(Action.REFRESH, "", 0);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && status.hasTape() && status.length() > 0
            && mouseX >= barX && mouseX <= barX + BAR_WIDTH && mouseY >= barY - 1 && mouseY <= barY + 9) {
            double fraction = Mth.clamp((mouseX - barX) / BAR_WIDTH, 0, 1);
            send(Action.SEEK, "", fraction * status.length());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, centerX, contentTop, 0xFFFFFF);

        var monitor = status.hasMonitor()
            ? Component.literal("Monitor: connected").withStyle(ChatFormatting.GREEN)
            : Component.literal("Monitor: none adjacent").withStyle(ChatFormatting.RED);
        graphics.drawCenteredString(font, monitor, centerX, contentTop + 18, 0xFFFFFF);

        var tape = status.hasTape()
            ? Component.literal("Tape: " + status.tapeLabel()).withStyle(ChatFormatting.AQUA)
            : Component.literal("Tape: none - right-click with a tape to insert").withStyle(ChatFormatting.RED);
        graphics.drawCenteredString(font, tape, centerX, contentTop + 30, 0xFFFFFF);

        var stateLine = switch (status.state()) {
            case "playing" -> Component.literal("▶ Playing" + recordingSuffix()).withStyle(ChatFormatting.GREEN);
            case "rewinding" -> Component.literal("◀◀ Rewinding").withStyle(ChatFormatting.GOLD);
            case "filled" -> Component.literal("■ Stopped" + recordingSuffix()).withStyle(ChatFormatting.GRAY);
            default -> Component.literal("No tape").withStyle(ChatFormatting.DARK_GRAY);
        };
        graphics.drawCenteredString(font, stateLine, centerX, contentTop + 44, 0xFFFFFF);

        // Progress bar.
        double fraction = status.length() > 0 ? Mth.clamp(status.position() / status.length(), 0, 1) : 0;
        int fillColor = switch (status.state()) {
            case "playing" -> 0xFF57A64E;
            case "rewinding" -> 0xFFF2B233;
            default -> 0xFF888888;
        };
        graphics.fill(barX - 1, barY - 1, barX + BAR_WIDTH + 1, barY + 9, 0xFF000000);
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + 8, 0xFF2B2B2B);
        graphics.fill(barX, barY, barX + (int) (BAR_WIDTH * fraction), barY + 8, fillColor);

        var time = Component.literal(formatTime(status.position()) + " / " + formatTime(status.length()))
            .withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(font, time, centerX, contentTop + 72, 0xFFFFFF);

        var header = status.recordings().isEmpty()
            ? Component.literal("No recordings on this tape").withStyle(ChatFormatting.DARK_GRAY)
            : Component.literal("Recordings").withStyle(ChatFormatting.WHITE);
        graphics.drawCenteredString(font, header, centerX, contentTop + 138, 0xFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (prevButton.visible) {
            int pages = Math.max(1, (status.recordings().size() + PAGE_SIZE - 1) / PAGE_SIZE);
            graphics.drawCenteredString(font, (page + 1) + " / " + pages, centerX, prevButton.getY() + 5, 0xFFFFFF);
        }
        if (!error.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(error).withStyle(ChatFormatting.RED),
                centerX, contentTop + 234, 0xFFFFFF);
        }
    }

    private String recordingSuffix() {
        return status.recordingName().isEmpty() ? "" : ": " + status.recordingName();
    }

    private static String formatTime(double seconds) {
        int total = (int) Math.max(0, seconds);
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }

    private void sendAction(Action action) {
        send(action, "", 0);
    }

    private void sendSelect(String name) {
        send(Action.SELECT, name, 0);
    }

    private void send(Action action, String name, double seconds) {
        PacketHandler.CHANNEL.sendToServer(new ServerboundPlaybackActionPacket(status.pos(), action, name, seconds));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
