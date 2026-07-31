package gg.lakehouse.cctv.vcr;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Containers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One VCR deck. Deck-level functions act on this deck's tape; array-level
 * functions act on the whole stack regardless of which deck was wrapped.
 */
public class VcrPeripheral implements IPeripheral {
    private final VcrBlockEntity blockEntity;

    public VcrPeripheral(VcrBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public String getType() {
        return "vcr";
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other || (other instanceof VcrPeripheral peripheral && peripheral.blockEntity == blockEntity);
    }

    // === This deck ===

    @LuaFunction(mainThread = true)
    public final int getIndex() {
        return blockEntity.deckIndex();
    }

    @LuaFunction(mainThread = true)
    public final boolean hasTape() {
        return blockEntity.hasTape();
    }

    @Nullable
    @LuaFunction(mainThread = true)
    public final String getTapeLabel() {
        return blockEntity.hasTape() ? blockEntity.tape().getHoverName().getString() : null;
    }

    @LuaFunction(mainThread = true)
    public final long getFreeSpace() throws LuaException {
        requireTape();
        return blockEntity.freeBytes();
    }

    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> list() throws LuaException {
        var tapeId = requireTape();
        var result = new ArrayList<Map<String, Object>>();
        for (var info : TapeStorage.list(server(), tapeId)) {
            var entry = new HashMap<String, Object>();
            entry.put("name", info.name());
            entry.put("bytes", info.bytes());
            entry.put("fps", info.fps());
            entry.put("frames", info.frames());
            entry.put("seconds", info.frames() / (double) Math.max(1, info.fps()));
            result.add(entry);
        }
        return result;
    }

    /**
     * Sets a front-panel readout. No text restores the automatic one; no
     * deck index targets the wrapped deck, so any deck can address every
     * panel in its array: setDisplay("MASTER", 1), setDisplay(nil, 3).
     */
    @LuaFunction(mainThread = true)
    public final void setDisplay(Optional<String> text, Optional<Integer> deck) throws LuaException {
        if (text.isPresent() && text.get().length() > VcrBlockEntity.DISPLAY_CELLS) {
            throw new LuaException("Display text is limited to " + VcrBlockEntity.DISPLAY_CELLS + " characters");
        }
        deckFor(deck).setDisplayText(text.orElse(null));
    }

    @Nullable
    @LuaFunction(mainThread = true)
    public final String getDisplay(Optional<Integer> deck) throws LuaException {
        return deckFor(deck).displayText();
    }

    @LuaFunction(mainThread = true)
    public final void eject() throws LuaException {
        if (!blockEntity.hasTape()) throw new LuaException("No tape in this deck");
        var level = blockEntity.getLevel();
        if (level == null) throw new LuaException("Deck is not loaded");
        var pos = blockEntity.getBlockPos();
        Containers.dropItemStack(level, pos.getX(), pos.getY() + 0.5, pos.getZ(), blockEntity.ejectTape());
    }

    // === The array ===

    @LuaFunction(mainThread = true)
    public final String getMode() {
        return blockEntity.mode().name();
    }

    @LuaFunction(mainThread = true)
    public final void setMode(String name) throws LuaException {
        var mode = RaidMode.byName(name);
        if (mode == null) throw new LuaException("Unknown mode (SPAN, STRIPE or MIRROR)");
        throwIfError(blockEntity.setMode(mode));
    }

    @LuaFunction(mainThread = true)
    public final int getDeckCount() {
        return blockEntity.stack().size();
    }

    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getDecks() {
        var result = new ArrayList<Map<String, Object>>();
        for (var deck : blockEntity.stack()) {
            var entry = new HashMap<String, Object>();
            entry.put("index", deck.deckIndex());
            entry.put("hasTape", deck.hasTape());
            entry.put("label", deck.hasTape() ? deck.tape().getHoverName().getString() : null);
            entry.put("freeSpace", deck.freeBytes());
            entry.put("full", deck.isFull());
            result.add(entry);
        }
        return result;
    }

    @LuaFunction(mainThread = true)
    public final long getCapacity() {
        long minFree = Long.MAX_VALUE;
        long sumFree = 0;
        int taped = 0;
        for (var deck : blockEntity.stack()) {
            if (!deck.hasTape()) continue;
            taped++;
            sumFree += deck.freeBytes();
            minFree = Math.min(minFree, deck.freeBytes());
        }
        if (taped == 0) return 0;
        return switch (blockEntity.mode()) {
            case SPAN -> sumFree;
            case STRIPE -> minFree * taped;
            case MIRROR -> minFree;
        };
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getHealth() {
        var empty = new ArrayList<Integer>();
        var full = new ArrayList<Integer>();
        for (var deck : blockEntity.stack()) {
            if (!deck.hasTape()) empty.add(deck.deckIndex());
            else if (deck.isFull()) full.add(deck.deckIndex());
        }
        var result = new HashMap<String, Object>();
        result.put("status", empty.isEmpty() && full.isEmpty() ? "OK" : "DEGRADED");
        result.put("empty", empty);
        result.put("full", full);
        return result;
    }

    @LuaFunction(mainThread = true)
    public final void record(Optional<Integer> fps, Optional<Boolean> loop) throws LuaException {
        throwIfError(blockEntity.startRecording(fps.orElse(VcrBlockEntity.DEFAULT_FPS), loop.orElse(false)));
    }

    @LuaFunction(mainThread = true)
    public final void stop() throws LuaException {
        throwIfError(blockEntity.stopRecording());
    }

    @LuaFunction(mainThread = true)
    public final boolean isRecording() {
        return blockEntity.isRecording();
    }

    @LuaFunction(mainThread = true)
    public final int getFrameCount() {
        return blockEntity.frameCount();
    }

    // === Array playback ===

    @LuaFunction(mainThread = true)
    public final void play(String name) throws LuaException {
        throwIfError(blockEntity.play(name));
    }

    @LuaFunction(mainThread = true)
    public final void stopPlayback() throws LuaException {
        throwIfError(blockEntity.stopPlayback());
    }

    @LuaFunction(mainThread = true)
    public final boolean isPlaying() {
        return blockEntity.isPlaying();
    }

    @LuaFunction(mainThread = true)
    public final double getPlaybackPosition() {
        return blockEntity.playbackPositionSeconds();
    }

    @LuaFunction(mainThread = true)
    public final double getPlaybackLength() {
        return blockEntity.playbackLengthSeconds();
    }

    /** Everything playable on the array: plain recordings plus segment groups. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> listAll() throws LuaException {
        var server = server();
        var result = new ArrayList<Map<String, Object>>();
        var groups = new HashMap<String, Map<String, Object>>();
        for (var deck : blockEntity.stack()) {
            var tapeId = deck.tapeId();
            if (tapeId == null) continue;
            for (var info : TapeStorage.list(server, tapeId)) {
                var segment = info.segment();
                if (segment == null) {
                    var entry = new HashMap<String, Object>();
                    entry.put("name", info.name());
                    entry.put("deck", deck.deckIndex());
                    entry.put("bytes", info.bytes());
                    entry.put("fps", info.fps());
                    entry.put("frames", info.frames());
                    entry.put("seconds", info.frames() / (double) Math.max(1, info.fps()));
                    result.add(entry);
                } else {
                    var group = groups.computeIfAbsent(segment.shortId(), key -> {
                        var entry = new HashMap<String, Object>();
                        entry.put("name", key);
                        entry.put("group", true);
                        entry.put("fps", info.fps());
                        entry.put("frames", 0);
                        entry.put("segments", 0);
                        entry.put("loop", segment.totalFrames() < 0);
                        result.add(entry);
                        return entry;
                    });
                    group.put("frames", (Integer) group.get("frames") + info.frames());
                    group.put("segments", (Integer) group.get("segments") + 1);
                }
            }
        }
        for (var group : groups.values()) {
            group.put("seconds", (Integer) group.get("frames") / (double) Math.max(1, (Integer) group.get("fps")));
        }
        return result;
    }

    // === Helpers ===

    /** This deck when no index is given, else the array deck at the 1-based bottom-up index. */
    private VcrBlockEntity deckFor(Optional<Integer> index) throws LuaException {
        if (index.isEmpty()) return blockEntity;
        var stack = blockEntity.stack();
        for (var deck : stack) {
            if (deck.deckIndex() == index.get()) return deck;
        }
        throw new LuaException("No deck " + index.get() + " in this array (1.." + stack.size() + ")");
    }

    private UUID requireTape() throws LuaException {
        if (!blockEntity.hasTape()) throw new LuaException("No tape in this deck");
        var tapeId = blockEntity.tapeId();
        if (tapeId == null) return TapeItem.getOrCreateId(blockEntity.tape());
        return tapeId;
    }

    private MinecraftServer server() throws LuaException {
        var level = blockEntity.getLevel();
        var server = level == null ? null : level.getServer();
        if (server == null) throw new LuaException("Deck is not loaded");
        return server;
    }

    private static void throwIfError(@Nullable String error) throws LuaException {
        if (error != null) throw new LuaException(error);
    }
}
