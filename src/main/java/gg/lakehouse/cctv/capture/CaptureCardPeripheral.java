package gg.lakehouse.cctv.capture;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeStorage;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class CaptureCardPeripheral implements IPeripheral {
    private final CaptureCardBlockEntity blockEntity;

    public CaptureCardPeripheral(CaptureCardBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public String getType() {
        return "capture_card";
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other || (other instanceof CaptureCardPeripheral peripheral && peripheral.blockEntity == blockEntity);
    }

    // === Recording ===

    @LuaFunction(mainThread = true)
    public final void record(Optional<Integer> fps) throws LuaException {
        var error = blockEntity.startRecording(fps.orElse(CaptureCardBlockEntity.DEFAULT_FPS));
        if (error != null) throw new LuaException(error);
    }

    @LuaFunction(mainThread = true)
    public final void stop() throws LuaException {
        var error = blockEntity.stopRecording();
        if (error != null) throw new LuaException(error);
    }

    @LuaFunction(mainThread = true)
    public final boolean isRecording() {
        return blockEntity.isRecording();
    }

    @LuaFunction(mainThread = true)
    public final int getFrameCount() {
        return blockEntity.frameCount();
    }

    /** "monitor" or "computer"; the choice only matters when both are adjacent. */
    @LuaFunction(mainThread = true)
    public final String getSource() {
        return blockEntity.source().name().toLowerCase(java.util.Locale.ROOT);
    }

    @LuaFunction(mainThread = true)
    public final void setSource(String name) throws LuaException {
        var source = switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "monitor" -> CaptureCardBlockEntity.Source.MONITOR;
            case "computer", "pc" -> CaptureCardBlockEntity.Source.COMPUTER;
            default -> throw new LuaException("Unknown source (monitor or computer)");
        };
        var error = blockEntity.setSource(source);
        if (error != null) throw new LuaException(error);
    }

    // === Tape ===

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
    public final long getCapacity() throws LuaException {
        requireTape();
        return TapeItem.CAPACITY_BYTES;
    }

    @LuaFunction(mainThread = true)
    public final long getFreeSpace() throws LuaException {
        var tapeId = requireTape();
        return TapeItem.CAPACITY_BYTES - TapeStorage.usedBytes(server(), tapeId);
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

    @LuaFunction(mainThread = true)
    public final boolean delete(String name) throws LuaException {
        var tapeId = requireTape();
        var deleted = TapeStorage.delete(server(), tapeId, name);
        if (deleted && blockEntity.hasTape()) {
            TapeItem.setUsedBytes(blockEntity.tape(), TapeStorage.usedBytes(server(), tapeId));
        }
        return deleted;
    }

    @LuaFunction
    public final void export() throws LuaException {
        throw new LuaException("Export from the capture card's screen for now");
    }

    private UUID requireTape() throws LuaException {
        var tapeId = blockEntity.tapeId();
        if (tapeId == null) {
            if (!blockEntity.hasTape()) throw new LuaException("No tape in the capture card");
            // Fresh tape that has never been written: give it an id now.
            return TapeItem.getOrCreateId(blockEntity.tape());
        }
        return tapeId;
    }

    private MinecraftServer server() throws LuaException {
        var level = blockEntity.getLevel();
        var server = level == null ? null : level.getServer();
        if (server == null) throw new LuaException("Capture card is not loaded");
        return server;
    }
}
