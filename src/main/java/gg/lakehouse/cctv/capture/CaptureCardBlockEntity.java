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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records the terminal of an adjacent monitor - or an adjacent computer's
 * own screen - onto the inserted tape. Frames buffer in memory while
 * recording and are committed to the tape (world/cctv/tapes/&lt;id&gt;)
 * when recording stops.
 */
public class CaptureCardBlockEntity extends BlockEntity {
    public static final int MAX_FRAMES = 3000;
    /**
     * Estimated heap cap for buffered frames; recording commits at the cap.
     * Native-resolution monitor frames are big - without a ceiling a long
     * recording of an oversized source walks the server into an
     * OutOfMemoryError. Sized so max-size-monitor recordings never trip it.
     */
    public static final long MAX_BUFFER_BYTES = 256L * 1024 * 1024;
    public static final int DEFAULT_FPS = 5;

    /** What the card points at. The choice only matters when both are adjacent. */
    public enum Source {
        MONITOR, COMPUTER
    }

    private final CaptureCardPeripheral peripheral = new CaptureCardPeripheral(this);
    private final List<TermFrame> frames = new ArrayList<>();
    private ItemStack tape = ItemStack.EMPTY;
    private boolean recording;
    private int fps = DEFAULT_FPS;
    private int tickCounter;
    private long bufferBytes;
    private Source source = Source.MONITOR;
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
    public dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity findComputer() {
        if (level == null) return null;
        for (var direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction))
                instanceof dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity computer) {
                return computer;
            }
        }
        return null;
    }

    public Source source() {
        return source;
    }

    /** @return null on success, otherwise a human-readable error. */
    @Nullable
    public String setSource(Source newSource) {
        if (recording) return "Stop recording first";
        if (source != newSource) {
            source = newSource;
            setChanged();
        }
        return null;
    }

    /** The chosen source when it is present, else whichever screen exists. */
    private Source effectiveSource() {
        boolean monitor = findMonitor() != null;
        boolean computer = findComputer() != null;
        if (source == Source.MONITOR) return monitor || !computer ? Source.MONITOR : Source.COMPUTER;
        return computer || !monitor ? Source.COMPUTER : Source.MONITOR;
    }

    @Nullable
    private Terminal targetTerminal() {
        if (effectiveSource() == Source.COMPUTER) {
            var computer = findComputer();
            if (computer == null) return null;
            // The server computer's own terminal is private; its sync state
            // rehydrates into a Terminal snapshot we can capture.
            return computer.createServerComputer().getTerminalState().create();
        }
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
        if (monitor == null && findComputer() == null) return "No monitor or computer next to the capture card";
        var terminal = targetTerminal();
        if (terminal == null) return "Monitor is blank - wrap it with a computer first";
        // Computers have no physical face; their recordings export as the
        // plain terminal render, like pre-monitor-metadata tapes.
        monitorInfo = effectiveSource() == Source.MONITOR && monitor != null
            ? TermFrame.MonitorInfo.derive(monitor.getWidth(), monitor.getHeight(),
                terminal.getWidth(), terminal.getHeight())
            : null;
        fps = TermFrame.snapFps(requestedFps);
        frames.clear();
        bufferBytes = 0;
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
        bufferBytes = 0;
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
        var captured = TermFrame.capture(terminal);
        frames.add(captured);
        bufferBytes += captured.estimatedBytes();
        if (frames.size() >= MAX_FRAMES || bufferBytes >= MAX_BUFFER_BYTES) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("Capture card at {}: {}", worldPosition, error);
        }
    }

    /** A chunk unload mid-recording used to discard every buffered frame; commit instead. */
    @Override
    public void onChunkUnloaded() {
        if (recording) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("Capture card at {} unloaded mid-recording: {}", worldPosition, error);
        }
        super.onChunkUnloaded();
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
            findComputer() != null, source == Source.COMPUTER,
            !tape.isEmpty(), tape.isEmpty() ? "" : tape.getHoverName().getString(),
            used, TapeItem.CAPACITY_BYTES, recordings);
    }

    // === Persistence ===

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!tape.isEmpty()) tag.put("Tape", tape.save(new CompoundTag()));
        if (source == Source.COMPUTER) tag.putBoolean("SourceComputer", true);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tape = tag.contains("Tape") ? ItemStack.of(tag.getCompound("Tape")) : ItemStack.EMPTY;
        source = tag.getBoolean("SourceComputer") ? Source.COMPUTER : Source.MONITOR;
    }
}
