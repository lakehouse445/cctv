package gg.lakehouse.cctv.capture;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.media.TermFrame;
import gg.lakehouse.cctv.network.CaptureStatus;
import gg.lakehouse.cctv.registry.ModRegistry;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records the terminal of an adjacent monitor onto the inserted tape.
 * Frames buffer in memory while recording and are committed to the tape
 * (world/cctv/tapes/&lt;id&gt;) when recording stops.
 */
public class CaptureCardBlockEntity extends BlockEntity {
    public static final int MAX_FRAMES = 3000;
    public static final int DEFAULT_FPS = 5;

    private final CaptureCardPeripheral peripheral = new CaptureCardPeripheral(this);
    private final List<TermFrame> frames = new ArrayList<>();
    private ItemStack tape = ItemStack.EMPTY;
    private boolean recording;
    private int fps = DEFAULT_FPS;
    private int tickCounter;
    @Nullable
    private TermFrame.MonitorInfo monitorInfo;

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

    // === Tape slot ===

    public boolean hasTape() {
        return !tape.isEmpty();
    }

    public ItemStack tape() {
        return tape;
    }

    public boolean insertTape(ItemStack stack) {
        if (!tape.isEmpty() || !(stack.getItem() instanceof TapeItem)) return false;
        tape = stack.split(1);
        var server = level == null ? null : level.getServer();
        var tapeId = TapeItem.getId(tape);
        if (server != null && tapeId != null) TapeItem.setUsedBytes(tape, TapeStorage.usedBytes(server, tapeId));
        setChanged();
        return true;
    }

    public ItemStack ejectTape() {
        if (recording) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("Capture card at {}: {}", worldPosition, error);
        }
        var ejected = tape;
        tape = ItemStack.EMPTY;
        setChanged();
        return ejected;
    }

    // === Recording ===

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
        if (tape.isEmpty()) return "No tape in the capture card";
        var monitor = findMonitor();
        if (monitor == null) return "No monitor next to the capture card";
        var terminal = targetTerminal();
        if (terminal == null) return "Monitor is blank - wrap it with a computer first";
        monitorInfo = TermFrame.MonitorInfo.derive(monitor.getWidth(), monitor.getHeight(),
            terminal.getWidth(), terminal.getHeight());
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
        return stopAndCommit();
    }

    @Nullable
    private String stopAndCommit() {
        recording = false;
        if (frames.isEmpty()) return null;
        var server = level == null ? null : level.getServer();
        var tapeId = tape.isEmpty() ? null : TapeItem.getOrCreateId(tape);
        String error = null;
        if (server == null || tapeId == null) {
            error = "No tape - recording discarded";
        } else {
            try {
                var data = TermFrame.write(fps, frames, null, monitorInfo);
                var name = TapeStorage.save(server, tapeId, data);
                if (name == null) {
                    error = "Tape full - recording discarded";
                } else {
                    TapeItem.setUsedBytes(tape, TapeStorage.usedBytes(server, tapeId));
                }
            } catch (IOException e) {
                CCTV.LOGGER.error("Failed to write recording to tape {}", tapeId, e);
                error = "Failed to write to tape";
            }
        }
        frames.clear();
        setChanged();
        return error;
    }

    public void serverTick() {
        if (!recording) return;
        int interval = Math.max(1, 20 / fps);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        var terminal = targetTerminal();
        if (terminal == null) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("Capture card at {}: {}", worldPosition, error);
            return;
        }
        // Raw monitor resolution: the export renders the true face; playback
        // rescales to whatever screen plays the tape.
        frames.add(TermFrame.capture(terminal));
        if (frames.size() >= MAX_FRAMES) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("Capture card at {}: {}", worldPosition, error);
        }
    }

    // === Export & status ===

    @Nullable
    public UUID tapeId() {
        return tape.isEmpty() ? null : TapeItem.getId(tape);
    }

    @Nullable
    public byte[] exportLatest() {
        var server = level == null ? null : level.getServer();
        var tapeId = tapeId();
        if (server == null || tapeId == null) return null;
        var recordings = TapeStorage.list(server, tapeId);
        if (recordings.isEmpty()) return null;
        return TapeStorage.read(server, tapeId, recordings.get(recordings.size() - 1).name());
    }

    public CaptureStatus status() {
        var server = level == null ? null : level.getServer();
        var tapeId = tapeId();
        long used = 0;
        int recordings = 0;
        if (server != null && tapeId != null) {
            used = TapeStorage.usedBytes(server, tapeId);
            recordings = TapeStorage.list(server, tapeId).size();
        }
        return new CaptureStatus(worldPosition, recording, frames.size(), fps, findMonitor() != null,
            !tape.isEmpty(), tape.isEmpty() ? "" : tape.getHoverName().getString(),
            used, TapeItem.CAPACITY_BYTES, recordings);
    }

    // === Persistence ===

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!tape.isEmpty()) tag.put("Tape", tape.save(new CompoundTag()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tape = tag.contains("Tape") ? ItemStack.of(tag.getCompound("Tape")) : ItemStack.EMPTY;
    }
}
