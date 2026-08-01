package gg.lakehouse.cctv.vcr;

import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.media.FrameScaler;
import gg.lakehouse.cctv.media.TermFrame;
import gg.lakehouse.cctv.media.Terminals;
import gg.lakehouse.cctv.registry.ModRegistry;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One VCR deck. Vertically stacked decks form an implicit RAID array whose
 * primary is the bottom deck. The array records an adjacent monitor onto the
 * pooled tapes (SPAN splits big recordings into segments across tapes, STRIPE
 * deals frames across lanes, MIRROR copies everywhere; loop mode records
 * forever by taping over the oldest footage) and can play its recordings back,
 * reassembling segment groups and showing TAPE MISSING over spanned gaps.
 */
public class VcrBlockEntity extends BlockEntity {
    public static final int MAX_FRAMES = 3000;
    public static final int DEFAULT_FPS = 5;
    /** Segment size for spanned/looped recordings. */
    public static final int SEGMENT_FRAMES = 500;
    /** Below this much free space the deck's red FULL light comes on. */
    private static final long FULL_THRESHOLD_BYTES = 16 * 1024;

    // Front-panel display states, synced to the client for the 7-segment readout.
    public static final int DISPLAY_IDLE = 0;
    public static final int DISPLAY_RECORDING = 1;
    public static final int DISPLAY_PLAYING = 2;
    /** The readout is a fixed 12-cell character display. */
    public static final int DISPLAY_CELLS = 12;

    private final VcrPeripheral peripheral = new VcrPeripheral(this);
    private ItemStack tape = ItemStack.EMPTY;
    private RaidMode mode = RaidMode.SPAN; // used on the primary deck

    // Recording state, only meaningful on the primary:
    private final List<TermFrame> frames = new ArrayList<>();
    private boolean recording;
    private boolean loop;
    @Nullable
    private UUID loopGroup;
    private int loopSegmentIndex;
    private int fps = DEFAULT_FPS;
    private int tickCounter;

    // Playback state, only meaningful on the primary:
    @Nullable
    private List<TermFrame> playFrames;
    @Nullable
    private String playName;
    private boolean playing;
    private int playFps = DEFAULT_FPS;
    private double playPosition;
    private int lastAppliedFrame = -1;

    // Display state; runtime only, mirrored to watching clients.
    private int displayMode = DISPLAY_IDLE;
    private long displayStart;
    /** Custom front-panel text set from Lua; overrides the automatic readout. */
    @Nullable
    private String displayText;

    public VcrBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.VCR_BLOCK_ENTITY.get(), pos, state);
    }

    public VcrPeripheral peripheral() {
        return peripheral;
    }

    // === Array shape ===

    public boolean isPrimary() {
        return level == null || !(level.getBlockEntity(worldPosition.below()) instanceof VcrBlockEntity);
    }

    public VcrBlockEntity primary() {
        var current = this;
        while (current.level != null
            && current.level.getBlockEntity(current.worldPosition.below()) instanceof VcrBlockEntity below) {
            current = below;
        }
        return current;
    }

    /** All decks of this array, bottom-up. */
    public List<VcrBlockEntity> stack() {
        var decks = new ArrayList<VcrBlockEntity>();
        var current = primary();
        decks.add(current);
        while (current.level != null
            && current.level.getBlockEntity(current.worldPosition.above()) instanceof VcrBlockEntity above) {
            decks.add(above);
            current = above;
        }
        return decks;
    }

    /** 1-based position in the array, counting from the bottom. */
    public int deckIndex() {
        return (int) (worldPosition.getY() - primary().worldPosition.getY()) + 1;
    }

    // === Tape slot ===

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

    private UUID tapeIdOrAssign() {
        return TapeItem.getOrCreateId(tape);
    }

    public long freeBytes() {
        if (tape.isEmpty()) return 0;
        return Math.max(0, TapeItem.CAPACITY_BYTES - TapeItem.getUsedBytes(tape));
    }

    public boolean isFull() {
        return hasTape() && freeBytes() < FULL_THRESHOLD_BYTES;
    }

    public boolean insertTape(ItemStack stack) {
        if (!tape.isEmpty() || !(stack.getItem() instanceof TapeItem)) return false;
        tape = stack.split(1);
        var server = level == null ? null : level.getServer();
        var tapeId = TapeItem.getId(tape);
        if (server != null && tapeId != null) TapeItem.setUsedBytes(tape, TapeStorage.usedBytes(server, tapeId));
        updateFillState();
        setChanged();
        return true;
    }

    public ItemStack ejectTape() {
        var ejected = tape;
        tape = ItemStack.EMPTY;
        updateFillState();
        setChanged();
        return ejected;
    }

    void updateFillState() {
        if (level == null || level.isClientSide) return;
        var fill = tape.isEmpty() ? VcrFill.EMPTY : (isFull() ? VcrFill.FULL : VcrFill.FILLING);
        var blockState = level.getBlockState(worldPosition);
        if (blockState.hasProperty(VcrBlock.FILL) && blockState.getValue(VcrBlock.FILL) != fill) {
            level.setBlock(worldPosition, blockState.setValue(VcrBlock.FILL, fill), 3);
        }
    }

    // === Display ===

    public int displayMode() {
        return displayMode;
    }

    /** Game time when the current display state began; the readout counts up from it. */
    public long displayStart() {
        return displayStart;
    }

    private void setDisplay(int mode) {
        if (displayMode == mode) return;
        displayMode = mode;
        displayStart = level == null ? 0 : level.getGameTime();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Nullable
    public String displayText() {
        return displayText;
    }

    /** Sets this deck's custom readout, or null to return to the automatic one. */
    public void setDisplayText(@Nullable String text) {
        if (text != null && text.length() > DISPLAY_CELLS) text = text.substring(0, DISPLAY_CELLS);
        if (Objects.equals(displayText, text)) return;
        displayText = text;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // === Mode ===

    public RaidMode mode() {
        return primary().mode;
    }

    @Nullable
    public String setMode(RaidMode newMode) {
        var head = primary();
        if (head.recording) return "Stop recording first";
        if (head.playing) return "Stop playback first";
        head.mode = newMode;
        head.setChanged();
        return null;
    }

    // === Recording ===

    public boolean isRecording() {
        return primary().recording;
    }

    public int frameCount() {
        return primary().frames.size();
    }

    @Nullable
    public String startRecording(int requestedFps, boolean loopMode) {
        var head = primary();
        if (head.recording) return "Already recording";
        if (head.playing) return "Stop playback first";
        if (head.decksWithTapes().isEmpty()) return "No tape in the array";
        if (!loopMode && head.decksWithSpace().isEmpty()) return "No tape with free space in the array";
        if (head.findMonitor() == null) return "No monitor next to the array";
        if (head.targetTerminal() == null) return "Monitor is blank - wrap it with a computer first";
        head.fps = Mth.clamp(requestedFps, 1, 20);
        head.frames.clear();
        head.tickCounter = 0;
        head.loop = loopMode;
        head.loopGroup = loopMode ? UUID.randomUUID() : null;
        head.loopSegmentIndex = 0;
        head.recording = true;
        head.setDisplay(DISPLAY_RECORDING);
        return null;
    }

    @Nullable
    public String stopRecording() {
        var head = primary();
        if (!head.recording) return "Not recording";
        return head.stopAndCommit();
    }

    // === Playback ===

    public boolean isPlaying() {
        return primary().playing;
    }

    public double playbackPositionSeconds() {
        var head = primary();
        return head.playPosition / Math.max(1, head.playFps);
    }

    public double playbackLengthSeconds() {
        var head = primary();
        return head.playFrames == null ? 0 : head.playFrames.size() / (double) Math.max(1, head.playFps);
    }

    @Nullable
    public String play(String name) {
        var head = primary();
        if (head.recording) return "Stop recording first";
        var server = head.level == null ? null : head.level.getServer();
        if (server == null) return "Array is not loaded";
        if (head.findMonitor() == null) return "No monitor next to the array";

        var error = head.loadPlayback(server, name);
        if (error != null) return error;
        head.playName = name;
        head.playPosition = 0;
        head.lastAppliedFrame = -1;
        head.playing = true;
        head.setDisplay(DISPLAY_PLAYING);
        return null;
    }

    @Nullable
    public String stopPlayback() {
        var head = primary();
        if (!head.playing && head.playFrames == null) return "Not playing";
        head.playing = false;
        head.playFrames = null;
        head.playName = null;
        head.playPosition = 0;
        head.setDisplay(DISPLAY_IDLE);
        return null;
    }

    // === Ticking (primary only) ===

    public void serverTick() {
        if (!isPrimary()) return;
        if (recording) tickRecording();
        else if (playing) tickPlayback();
        else setDisplay(DISPLAY_IDLE);
    }

    private void tickRecording() {
        int interval = Math.max(1, 20 / fps);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        var terminal = targetTerminal();
        if (terminal == null) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("VCR array at {}: {}", worldPosition, error);
            return;
        }
        frames.add(FrameScaler.toRecordingSize(TermFrame.capture(terminal)));
        if (loop) {
            if (frames.size() >= SEGMENT_FRAMES) {
                var error = commitLoopSegment();
                if (error != null) CCTV.LOGGER.warn("VCR array at {}: {}", worldPosition, error);
            }
        } else if (frames.size() >= MAX_FRAMES) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("VCR array at {}: {}", worldPosition, error);
        }
    }

    private void tickPlayback() {
        if (playFrames == null || playFrames.isEmpty()) {
            playing = false;
            return;
        }
        var terminal = targetTerminal();
        if (terminal == null) {
            playing = false;
            return;
        }
        playPosition += playFps / 20.0;
        if (playPosition >= playFrames.size()) {
            applyPlaybackFrame(terminal, playFrames.size() - 1);
            playing = false;
            return;
        }
        applyPlaybackFrame(terminal, (int) playPosition);
    }

    private void applyPlaybackFrame(Terminal terminal, int index) {
        int clamped = Mth.clamp(index, 0, playFrames.size() - 1);
        if (clamped == lastAppliedFrame) return;
        lastAppliedFrame = clamped;
        Terminals.applyFrame(terminal, playFrames.get(clamped));
    }

    /**
     * A chunk unload mid-recording used to discard every buffered frame.
     * Commit what we have. Only the head deck carries recording state, so
     * this acts on local fields and never walks the (possibly half-unloaded)
     * stack for delegation; the stripe/span commit may see fewer decks
     * during unload and then degrades with its usual warnings instead of
     * losing the footage outright.
     */
    @Override
    public void onChunkUnloaded() {
        if (recording) {
            var error = stopAndCommit();
            if (error != null) CCTV.LOGGER.warn("VCR array at {} unloaded mid-recording: {}", worldPosition, error);
        }
        super.onChunkUnloaded();
    }

    // === Commit paths ===

    @Nullable
    private String stopAndCommit() {
        recording = false;
        setDisplay(DISPLAY_IDLE);
        String error;
        if (loop) {
            error = frames.isEmpty() ? null : commitLoopSegment();
            loop = false;
            loopGroup = null;
        } else if (frames.isEmpty()) {
            error = null;
        } else {
            var server = level == null ? null : level.getServer();
            if (server == null) {
                error = "Array is not loaded";
            } else {
                error = switch (mode) {
                    case SPAN -> commitSpan(server);
                    case STRIPE -> commitStripe(server);
                    case MIRROR -> commitMirror(server);
                };
            }
        }
        frames.clear();
        refreshAllDecks();
        return error;
    }

    private String commitSpan(MinecraftServer server) {
        try {
            // Whole blob on one tape when possible.
            var data = TermFrame.writeAll(fps, frames);
            for (var deck : decksWithSpace()) {
                if (TapeStorage.save(server, deck.tapeIdOrAssign(), data) != null) return null;
            }
            // Otherwise split into a segment group spread across the array.
            var group = UUID.randomUUID();
            int total = frames.size();
            int segments = (total + SEGMENT_FRAMES - 1) / SEGMENT_FRAMES;
            boolean truncated = false;
            for (int index = 0; index < segments; index++) {
                var subset = frames.subList(index * SEGMENT_FRAMES, Math.min(total, (index + 1) * SEGMENT_FRAMES));
                var segment = new TermFrame.SegmentInfo(group, index, 0, 1, total);
                var bytes = TermFrame.write(fps, new ArrayList<>(subset), segment);
                if (!saveFirstFit(server, bytes)) truncated = true;
            }
            return truncated ? "Array ran out of room - recording truncated" : null;
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to commit spanned recording", e);
            return "Failed to write to the array";
        }
    }

    private String commitStripe(MinecraftServer server) {
        var decks = decksWithSpace();
        if (decks.isEmpty()) return "Recording discarded - no tape had enough room";
        int lanes = decks.size();
        var group = UUID.randomUUID();
        boolean any = false;
        for (int lane = 0; lane < lanes; lane++) {
            var subset = new ArrayList<TermFrame>();
            for (int i = lane; i < frames.size(); i += lanes) subset.add(frames.get(i));
            if (subset.isEmpty()) continue;
            try {
                var segment = new TermFrame.SegmentInfo(group, 0, lane, lanes, frames.size());
                var data = TermFrame.write(fps, subset, segment);
                if (TapeStorage.save(server, decks.get(lane).tapeIdOrAssign(), data) != null) any = true;
            } catch (IOException e) {
                CCTV.LOGGER.error("Failed to commit stripe lane {}", lane, e);
            }
        }
        return any ? null : "Recording discarded - no tape had enough room";
    }

    private String commitMirror(MinecraftServer server) {
        try {
            var data = TermFrame.writeAll(fps, frames);
            boolean any = false;
            for (var deck : decksWithSpace()) {
                if (TapeStorage.save(server, deck.tapeIdOrAssign(), data) != null) any = true;
            }
            return any ? null : "Recording discarded - no tape had enough room";
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to commit mirrored recording", e);
            return "Failed to write to the array";
        }
    }

    /** Commits the buffered frames as the next segment of the running loop chain. */
    private String commitLoopSegment() {
        var server = level == null ? null : level.getServer();
        if (server == null || loopGroup == null) {
            frames.clear();
            return "Array is not loaded";
        }
        int index = loopSegmentIndex++;
        String error = null;
        try {
            if (mode == RaidMode.STRIPE) {
                var decks = decksWithTapes();
                int lanes = decks.size();
                for (int lane = 0; lane < lanes; lane++) {
                    var subset = new ArrayList<TermFrame>();
                    for (int i = lane; i < frames.size(); i += lanes) subset.add(frames.get(i));
                    if (subset.isEmpty()) continue;
                    var segment = new TermFrame.SegmentInfo(loopGroup, index, lane, lanes, -1);
                    var data = TermFrame.write(fps, subset, segment);
                    if (!saveWithEviction(server, decks.get(lane), data)) error = "Loop segment lost - deck " + decks.get(lane).deckIndex() + " full";
                }
            } else {
                var segment = new TermFrame.SegmentInfo(loopGroup, index, 0, 1, -1);
                var data = TermFrame.write(fps, new ArrayList<>(frames), segment);
                if (mode == RaidMode.MIRROR) {
                    boolean any = false;
                    for (var deck : decksWithTapes()) {
                        if (saveWithEviction(server, deck, data)) any = true;
                    }
                    if (!any) error = "Loop segment lost - array full";
                } else {
                    if (!saveFirstFitWithEviction(server, data)) error = "Loop segment lost - array full";
                }
            }
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to commit loop segment", e);
            error = "Failed to write to the array";
        }
        frames.clear();
        refreshAllDecks();
        return error;
    }

    private boolean saveFirstFit(MinecraftServer server, byte[] data) throws IOException {
        for (var deck : decksWithSpace()) {
            if (TapeStorage.save(server, deck.tapeIdOrAssign(), data) != null) return true;
        }
        return false;
    }

    private boolean saveFirstFitWithEviction(MinecraftServer server, byte[] data) throws IOException {
        if (saveFirstFit(server, data)) return true;
        for (var deck : decksWithTapes()) {
            if (saveWithEviction(server, deck, data)) return true;
        }
        return false;
    }

    /** Saves to this deck, taping over its oldest recordings until the segment fits. */
    private boolean saveWithEviction(MinecraftServer server, VcrBlockEntity deck, byte[] data) throws IOException {
        var tapeId = deck.tapeIdOrAssign();
        if (TapeStorage.save(server, tapeId, data) != null) return true;
        for (int attempts = 0; attempts < 64; attempts++) {
            var recordings = TapeStorage.list(server, tapeId);
            if (recordings.isEmpty()) return false;
            var oldest = recordings.get(0);
            for (var info : recordings) {
                if (info.modifiedMs() < oldest.modifiedMs()) oldest = info;
            }
            if (!TapeStorage.delete(server, tapeId, oldest.name())) return false;
            if (TapeStorage.save(server, tapeId, data) != null) return true;
        }
        return false;
    }

    // === Playback assembly ===

    @Nullable
    private String loadPlayback(MinecraftServer server, String name) {
        // A plain recording on any deck wins first.
        for (var deck : stack()) {
            var tapeId = deck.tapeId();
            if (tapeId == null) continue;
            for (var info : TapeStorage.list(server, tapeId)) {
                if (info.segment() == null && info.name().equals(name)) {
                    var data = TapeStorage.read(server, tapeId, info.name());
                    if (data == null) return "Recording is damaged";
                    try {
                        var recording = TermFrame.readAll(data);
                        playFrames = recording.frames();
                        playFps = Math.max(1, recording.fps());
                        return null;
                    } catch (IOException e) {
                        return "Recording is damaged";
                    }
                }
            }
        }
        return assembleGroup(server, name);
    }

    private record LoadedSegment(TermFrame.SegmentInfo info, int fps, List<TermFrame> frames) {
    }

    @Nullable
    private String assembleGroup(MinecraftServer server, String shortId) {
        var segments = new ArrayList<LoadedSegment>();
        for (var deck : stack()) {
            var tapeId = deck.tapeId();
            if (tapeId == null) continue;
            for (var info : TapeStorage.list(server, tapeId)) {
                var segment = info.segment();
                if (segment == null || !segment.shortId().equals(shortId)) continue;
                var data = TapeStorage.read(server, tapeId, info.name());
                if (data == null) continue;
                try {
                    var recording = TermFrame.readAll(data);
                    segments.add(new LoadedSegment(segment, recording.fps(), recording.frames()));
                } catch (IOException e) {
                    CCTV.LOGGER.warn("Skipping damaged segment {} of {}", info.name(), shortId, e);
                }
            }
        }
        if (segments.isEmpty()) return "No recording named " + shortId;

        int totalFrames = -1;
        for (var segment : segments) {
            if (segment.info().totalFrames() >= 0) totalFrames = segment.info().totalFrames();
        }
        var byIndex = new TreeMap<Integer, List<LoadedSegment>>();
        for (var segment : segments) {
            byIndex.computeIfAbsent(segment.info().index(), key -> new ArrayList<>()).add(segment);
        }

        var assembled = new ArrayList<TermFrame>();
        var reference = segments.get(0).frames().isEmpty() ? null : segments.get(0).frames().get(0);
        int expectedIndexes = totalFrames >= 0 && segments.get(0).info().lanes() == 1
            ? (totalFrames + SEGMENT_FRAMES - 1) / SEGMENT_FRAMES
            : byIndex.lastKey() + 1;

        for (int index = 0; index < expectedIndexes; index++) {
            var atIndex = byIndex.get(index);
            if (atIndex == null) {
                // A spanned segment is on a tape that isn't here: hold the card.
                if (totalFrames >= 0 && reference != null) {
                    for (int i = 0; i < SEGMENT_FRAMES; i++) {
                        assembled.add(TermFrame.missingTapeFrame(reference.width(), reference.height(), reference.palette()));
                    }
                }
                continue;
            }
            int lanes = atIndex.get(0).info().lanes();
            if (lanes <= 1) {
                assembled.addAll(atIndex.get(0).frames());
            } else {
                if (atIndex.size() < lanes && totalFrames >= 0) {
                    return "Striped recording is missing a tape - unplayable";
                }
                var byLane = new TreeMap<Integer, List<TermFrame>>();
                for (var segment : atIndex) byLane.put(segment.info().lane(), segment.frames());
                int longest = 0;
                for (var lane : byLane.values()) longest = Math.max(longest, lane.size());
                for (int i = 0; i < longest; i++) {
                    for (var lane : byLane.values()) {
                        if (i < lane.size()) assembled.add(lane.get(i));
                    }
                }
            }
        }
        if (assembled.isEmpty()) return "Nothing playable in " + shortId;
        playFrames = assembled;
        playFps = Math.max(1, segments.get(0).fps());
        return null;
    }

    // === Shared helpers ===

    private void refreshAllDecks() {
        var server = level == null ? null : level.getServer();
        if (server == null) return;
        for (var deck : stack()) {
            var tapeId = deck.tapeId();
            if (tapeId != null) TapeItem.setUsedBytes(deck.tape, TapeStorage.usedBytes(server, tapeId));
            deck.updateFillState();
        }
    }

    List<VcrBlockEntity> decksWithTapes() {
        var result = new ArrayList<VcrBlockEntity>();
        for (var deck : stack()) {
            if (deck.hasTape()) result.add(deck);
        }
        return result;
    }

    private List<VcrBlockEntity> decksWithSpace() {
        var result = new ArrayList<VcrBlockEntity>();
        for (var deck : stack()) {
            if (deck.hasTape() && !deck.isFull()) result.add(deck);
        }
        return result;
    }

    @Nullable
    public MonitorBlockEntity findMonitor() {
        if (level == null) return null;
        for (var deck : stack()) {
            for (var direction : Direction.values()) {
                if (level.getBlockEntity(deck.worldPosition.relative(direction)) instanceof MonitorBlockEntity monitor) {
                    return monitor;
                }
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

    // === Persistence ===

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!tape.isEmpty()) tag.put("Tape", tape.save(new CompoundTag()));
        tag.putString("RaidMode", mode.name());
        if (displayText != null) tag.putString("DisplayText", displayText);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tape = tag.contains("Tape") ? ItemStack.of(tag.getCompound("Tape")) : ItemStack.EMPTY;
        var loaded = RaidMode.byName(tag.getString("RaidMode"));
        mode = loaded == null ? RaidMode.SPAN : loaded;
        // Only present in update tags; the mode and timer are never persisted.
        displayMode = tag.getInt("Display");
        displayStart = tag.getLong("DisplayStart");
        displayText = tag.contains("DisplayText") ? tag.getString("DisplayText") : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        var tag = new CompoundTag();
        tag.putInt("Display", displayMode);
        tag.putLong("DisplayStart", displayStart);
        if (displayText != null) tag.putString("DisplayText", displayText);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
