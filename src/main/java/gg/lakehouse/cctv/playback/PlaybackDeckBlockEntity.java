package gg.lakehouse.cctv.playback;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.media.TermFrame;
import gg.lakehouse.cctv.media.Terminals;
import gg.lakehouse.cctv.network.PlaybackStatus;
import gg.lakehouse.cctv.registry.ModRegistry;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
 * Plays tape recordings onto an adjacent monitor. Playback position is a
 * fractional frame index; the state lives in the blockstate (for the four
 * model variants) and position/length/tape sync to the client for the
 * reel-size and label rendering later.
 */
public class PlaybackDeckBlockEntity extends BlockEntity {
    /** Rewind runs this many times faster than playback, backwards. */
    private static final double REWIND_SPEED = 8.0;
    /** Fast-forward winds the head forward this many seconds per press. */
    private static final double FAST_FORWARD_SECONDS = 10;
    /** Sync position to watching clients this often while the reels move. */
    private static final int SYNC_INTERVAL_TICKS = 20;

    private final PlaybackDeckPeripheral peripheral = new PlaybackDeckPeripheral(this);
    private ItemStack tape = ItemStack.EMPTY;
    private DeckState state = DeckState.EMPTY;
    @Nullable
    private String recordingName;
    @Nullable
    private List<TermFrame> frames;
    private int fps = 5;
    private double framePosition;
    private int lastAppliedFrame = -1;
    private int syncCounter;
    @Nullable
    private String pendingResumeRecording;
    private double pendingResumeSeconds;
    /** Latched front-panel button (DeckButton ordinal, -1 none); pops out on eject or another press. */
    private int pressedButton = -1;

    public PlaybackDeckBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.PLAYBACK_DECK_BLOCK_ENTITY.get(), pos, state);
    }

    public PlaybackDeckPeripheral peripheral() {
        return peripheral;
    }

    public DeckState state() {
        return state;
    }

    public boolean hasTape() {
        return !tape.isEmpty();
    }

    public ItemStack tape() {
        return tape;
    }

    @Nullable
    public UUID tapeId() {
        return tape.isEmpty() ? null : TapeItem.getId(tape);
    }

    @Nullable
    public String recordingName() {
        return recordingName;
    }

    public double positionSeconds() {
        if (frames == null && pendingResumeRecording != null) return pendingResumeSeconds;
        return framePosition / Math.max(1, fps);
    }

    public double lengthSeconds() {
        return frames == null ? 0 : frames.size() / (double) Math.max(1, fps);
    }

    /** A snapshot for the GUI: tape, transport state, position and the tape's recordings. */
    public PlaybackStatus status() {
        var recordings = new ArrayList<PlaybackStatus.Entry>();
        var server = level == null ? null : level.getServer();
        var tapeId = tapeId();
        if (server != null && tapeId != null) {
            for (var info : TapeStorage.list(server, tapeId)) {
                recordings.add(new PlaybackStatus.Entry(info.name(), info.frames(), info.fps()));
            }
        }
        // Before the transport loads a recording, take its length from the tape listing.
        double length = lengthSeconds();
        if (length <= 0 && recordingName != null) {
            for (var entry : recordings) {
                if (entry.name().equals(recordingName)) {
                    length = entry.seconds();
                    break;
                }
            }
        }
        return new PlaybackStatus(worldPosition, state.getSerializedName(), findMonitor() != null, hasTape(),
            hasTape() ? tape.getHoverName().getString() : "",
            recordingName == null ? "" : recordingName,
            positionSeconds(), length, fps, recordings);
    }

    // === Tape slot ===

    public boolean insertTape(ItemStack stack) {
        if (!tape.isEmpty() || !(stack.getItem() instanceof TapeItem)) return false;
        tape = stack.split(1);
        setState(DeckState.FILLED);
        // The head sits wherever this tape was last ejected.
        recordingName = TapeItem.getResumeRecording(tape);
        pendingResumeRecording = recordingName;
        pendingResumeSeconds = TapeItem.getResumeSeconds(tape);
        frames = null;
        framePosition = 0;
        markDirtyAndSync();
        return true;
    }

    public ItemStack ejectTape() {
        var ejected = tape;
        if (!ejected.isEmpty()) {
            double seconds = frames == null ? pendingResumeSeconds : positionSeconds();
            var recording = frames == null ? pendingResumeRecording : recordingName;
            TapeItem.setResumePoint(ejected, recording, seconds);
        }
        tape = ItemStack.EMPTY;
        recordingName = null;
        frames = null;
        framePosition = 0;
        lastAppliedFrame = -1;
        pendingResumeRecording = null;
        pendingResumeSeconds = 0;
        pressedButton = -1;
        setState(DeckState.EMPTY);
        markDirtyAndSync();
        return ejected;
    }

    public int pressedButton() {
        return pressedButton;
    }

    /** A button physically latches in even when the action refuses. */
    private void press(DeckButton button) {
        pressedButton = button.ordinal();
        markDirtyAndSync();
    }

    // === Transport controls; all return null on success or a human-readable error ===

    @Nullable
    public String play(@Nullable String name) {
        press(DeckButton.PLAY);
        if (tape.isEmpty()) return "No tape in the deck";
        if (findMonitor() == null) return "No monitor next to the playback deck";
        var server = level == null ? null : level.getServer();
        var tapeId = tapeId();
        if (server == null || tapeId == null) return "Tape is blank";

        var target = name;
        if (target == null) target = recordingName;
        if (target == null) {
            var recordings = TapeStorage.list(server, tapeId);
            if (recordings.isEmpty()) return "Nothing on this tape";
            target = recordings.get(0).name();
        }

        boolean switched = !target.equals(recordingName);
        if (switched || frames == null) {
            var error = loadRecording(target);
            if (error != null) return error;
            if (switched) framePosition = 0; // winding to another recording moves the head there
        }
        if (pendingResumeRecording != null) {
            if (pendingResumeRecording.equals(target)) {
                framePosition = Mth.clamp(pendingResumeSeconds * fps, 0, frames.size());
            }
            pendingResumeRecording = null;
            pendingResumeSeconds = 0;
        }
        if (framePosition >= frames.size()) return "Tape needs rewinding";
        lastAppliedFrame = -1;
        setState(DeckState.PLAYING);
        markDirtyAndSync();
        return null;
    }

    @Nullable
    public String pause() {
        press(DeckButton.PAUSE);
        if (state != DeckState.PLAYING && state != DeckState.REWINDING) return "Not playing";
        setState(DeckState.FILLED);
        markDirtyAndSync();
        return null;
    }

    @Nullable
    public String stop() {
        press(DeckButton.STOP);
        // No free rewind: stop halts the transport but the head stays put.
        if (tape.isEmpty()) return "No tape in the deck";
        setState(DeckState.FILLED);
        markDirtyAndSync();
        return null;
    }

    @Nullable
    public String rewind() {
        press(DeckButton.REWIND);
        if (tape.isEmpty()) return "No tape in the deck";
        if (frames == null) {
            var name = recordingName != null ? recordingName : pendingResumeRecording;
            if (name == null) return null; // fresh tape, nothing to rewind
            if (loadRecording(name) != null) {
                // Content gone; the reel still winds back instantly.
                framePosition = 0;
                pendingResumeRecording = null;
                pendingResumeSeconds = 0;
                markDirtyAndSync();
                return null;
            }
            if (pendingResumeRecording != null && pendingResumeRecording.equals(recordingName)) {
                framePosition = Mth.clamp(pendingResumeSeconds * fps, 0, frames.size());
            }
            pendingResumeRecording = null;
            pendingResumeSeconds = 0;
        }
        if (framePosition <= 0) return null; // already at the start
        setState(DeckState.REWINDING);
        markDirtyAndSync();
        return null;
    }

    @Nullable
    public String fastForward() {
        press(DeckButton.FAST_FORWARD);
        if (tape.isEmpty()) return "No tape in the deck";
        if (frames == null) {
            var name = recordingName != null ? recordingName : pendingResumeRecording;
            if (name == null) return "Nothing on this tape";
            var error = loadRecording(name);
            if (error != null) return error;
            if (pendingResumeRecording != null && pendingResumeRecording.equals(recordingName)) {
                framePosition = Mth.clamp(pendingResumeSeconds * fps, 0, frames.size());
            }
            pendingResumeRecording = null;
            pendingResumeSeconds = 0;
        }
        framePosition = Math.min(frames.size(), framePosition + FAST_FORWARD_SECONDS * fps);
        lastAppliedFrame = -1;
        markDirtyAndSync();
        return null;
    }

    @Nullable
    public String seek(double seconds) {
        if (recordingName == null || frames == null) return "Nothing loaded - play something first";
        framePosition = Mth.clamp(seconds, 0, lengthSeconds()) * fps;
        lastAppliedFrame = -1;
        markDirtyAndSync();
        return null;
    }

    // === Ticking ===

    public void serverTick() {
        if (state != DeckState.PLAYING && state != DeckState.REWINDING) return;
        if (frames == null) {
            // Restored from NBT mid-playback; reload lazily.
            if (recordingName == null || loadRecording(recordingName) != null) {
                setState(tape.isEmpty() ? DeckState.EMPTY : DeckState.FILLED);
                return;
            }
        }

        var terminal = targetTerminal();
        if (terminal == null && state == DeckState.PLAYING) {
            // Playback needs a screen; rewinding is happy to run blind.
            setState(DeckState.FILLED);
            markDirtyAndSync();
            return;
        }

        double step = fps / 20.0;
        if (state == DeckState.PLAYING) {
            framePosition += step;
            if (framePosition >= frames.size()) {
                framePosition = frames.size();
                applyFrameIfChanged(terminal, frames.size() - 1);
                setState(DeckState.FILLED);
                markDirtyAndSync();
                return;
            }
        } else {
            framePosition -= step * REWIND_SPEED;
            if (framePosition <= 0) {
                framePosition = 0;
                if (terminal != null) applyFrameIfChanged(terminal, 0);
                setState(DeckState.FILLED);
                markDirtyAndSync();
                return;
            }
        }
        if (terminal != null) applyFrameIfChanged(terminal, (int) framePosition);

        if (++syncCounter >= SYNC_INTERVAL_TICKS) {
            syncCounter = 0;
            markDirtyAndSync();
        }
    }

    // === Internals ===

    @Nullable
    private String loadRecording(String name) {
        var server = level == null ? null : level.getServer();
        var tapeId = tapeId();
        if (server == null || tapeId == null) return "Tape is blank";
        var data = TapeStorage.read(server, tapeId, name);
        if (data == null) return "No recording named " + name;
        try {
            var recording = TermFrame.readAll(data);
            frames = recording.frames();
            fps = Math.max(1, recording.fps());
            recordingName = name;
            return null;
        } catch (IOException e) {
            CCTV.LOGGER.error("Unreadable recording {} in playback deck at {}", name, worldPosition, e);
            return "Recording is damaged";
        }
    }

    @Nullable
    private MonitorBlockEntity findMonitor() {
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
            monitor.peripheral();
            serverMonitor = monitor.getCachedServerMonitor();
        }
        return serverMonitor == null ? null : serverMonitor.getTerminal();
    }

    private void applyFrameIfChanged(Terminal terminal, int frameIndex) {
        if (frames == null || frames.isEmpty()) return;
        int clamped = Mth.clamp(frameIndex, 0, frames.size() - 1);
        if (clamped == lastAppliedFrame) return;
        lastAppliedFrame = clamped;
        Terminals.applyFrame(terminal, frames.get(clamped));
    }

    private void setState(DeckState newState) {
        if (state == newState) return;
        state = newState;
        if (level != null && !level.isClientSide) {
            var blockState = level.getBlockState(worldPosition);
            if (blockState.hasProperty(PlaybackDeckBlock.STATE)) {
                level.setBlock(worldPosition, blockState.setValue(PlaybackDeckBlock.STATE, newState), 3);
            }
        }
    }

    private void markDirtyAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // === Persistence & sync ===

    private void saveShared(CompoundTag tag) {
        if (!tape.isEmpty()) tag.put("Tape", tape.save(new CompoundTag()));
        tag.putString("State", state.getSerializedName());
        if (recordingName != null) tag.putString("Recording", recordingName);
        tag.putDouble("Position", framePosition);
        tag.putInt("Fps", fps);
        tag.putInt("TotalFrames", frames == null ? 0 : frames.size());
        if (pendingResumeRecording != null) {
            tag.putString("PendingResumeRecording", pendingResumeRecording);
            tag.putDouble("PendingResumeSeconds", pendingResumeSeconds);
        }
        tag.putInt("PressedButton", pressedButton);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        saveShared(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tape = tag.contains("Tape") ? ItemStack.of(tag.getCompound("Tape")) : ItemStack.EMPTY;
        var stateName = tag.getString("State");
        state = DeckState.EMPTY;
        for (var value : DeckState.values()) {
            if (value.getSerializedName().equals(stateName)) state = value;
        }
        recordingName = tag.contains("Recording") ? tag.getString("Recording") : null;
        framePosition = tag.getDouble("Position");
        fps = Math.max(1, tag.getInt("Fps"));
        pendingResumeRecording = tag.contains("PendingResumeRecording") ? tag.getString("PendingResumeRecording") : null;
        pendingResumeSeconds = tag.getDouble("PendingResumeSeconds");
        pressedButton = tag.contains("PressedButton") ? tag.getInt("PressedButton") : -1;
    }

    @Override
    public CompoundTag getUpdateTag() {
        var tag = super.getUpdateTag();
        saveShared(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
