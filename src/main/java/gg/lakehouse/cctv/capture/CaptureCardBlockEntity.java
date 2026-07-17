package gg.lakehouse.cctv.capture;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import gg.lakehouse.cctv.media.TermFrame;
import gg.lakehouse.cctv.network.CaptureStatus;
import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the terminal of an adjacent monitor. Recordings are kept in memory
 * only (they do not survive a chunk unload or server restart) until tapes land.
 */
public class CaptureCardBlockEntity extends BlockEntity {
    public static final int MAX_FRAMES = 3000;
    public static final int DEFAULT_FPS = 5;

    private final CaptureCardPeripheral peripheral = new CaptureCardPeripheral(this);
    private final List<TermFrame> frames = new ArrayList<>();
    private boolean recording;
    private int fps = DEFAULT_FPS;
    private int tickCounter;

    public CaptureCardBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.CAPTURE_CARD_BLOCK_ENTITY.get(), pos, state);
    }

    public CaptureCardPeripheral peripheral() {
        return peripheral;
    }

    public boolean isRecording() {
        return recording;
    }

    public int frameCount() {
        return frames.size();
    }

    @Nullable
    public MonitorBlockEntity findMonitor() {
        if (level == null) return null;
        for (var direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction)) instanceof MonitorBlockEntity monitor) {
                return monitor;
            }
        }
        return null;
    }

    @Nullable
    private Terminal targetTerminal() {
        var monitor = findMonitor();
        if (monitor == null) return null;
        var serverMonitor = monitor.getCachedServerMonitor();
        if (serverMonitor == null) {
            // Force the server monitor into existence, as a wrapping computer would.
            monitor.peripheral();
            serverMonitor = monitor.getCachedServerMonitor();
        }
        return serverMonitor == null ? null : serverMonitor.getTerminal();
    }

    /** @return null on success, otherwise a human-readable error. */
    @Nullable
    public String startRecording(int requestedFps) {
        if (recording) return "Already recording";
        if (findMonitor() == null) return "No monitor next to the capture card";
        if (targetTerminal() == null) return "Monitor is blank - wrap it with a computer first";
        fps = Mth.clamp(requestedFps, 1, 20);
        frames.clear();
        tickCounter = 0;
        recording = true;
        return null;
    }

    /** @return null on success, otherwise a human-readable error. */
    @Nullable
    public String stopRecording() {
        if (!recording) return "Not recording";
        recording = false;
        return null;
    }

    public void serverTick() {
        if (!recording) return;
        int interval = Math.max(1, 20 / fps);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        var terminal = targetTerminal();
        if (terminal == null) {
            recording = false;
            return;
        }
        frames.add(TermFrame.capture(terminal));
        if (frames.size() >= MAX_FRAMES) recording = false;
    }

    public byte[] exportBytes() throws IOException {
        return TermFrame.writeAll(fps, frames);
    }

    public CaptureStatus status() {
        return new CaptureStatus(worldPosition, recording, frames.size(), fps, findMonitor() != null);
    }
}
