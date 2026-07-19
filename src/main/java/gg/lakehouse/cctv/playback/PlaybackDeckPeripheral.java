package gg.lakehouse.cctv.playback;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
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

public class PlaybackDeckPeripheral implements IPeripheral {
    private final PlaybackDeckBlockEntity blockEntity;

    public PlaybackDeckPeripheral(PlaybackDeckBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public String getType() {
        return "playback_deck";
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other || (other instanceof PlaybackDeckPeripheral peripheral && peripheral.blockEntity == blockEntity);
    }

    // === Transport ===

    @LuaFunction(mainThread = true)
    public final void play(Optional<String> name) throws LuaException {
        throwIfError(blockEntity.play(name.orElse(null)));
    }

    @LuaFunction(mainThread = true)
    public final void pause() throws LuaException {
        throwIfError(blockEntity.pause());
    }

    @LuaFunction(mainThread = true)
    public final void stop() throws LuaException {
        throwIfError(blockEntity.stop());
    }

    @LuaFunction(mainThread = true)
    public final void rewind() throws LuaException {
        throwIfError(blockEntity.rewind());
    }

    @LuaFunction(mainThread = true)
    public final void fastForward() throws LuaException {
        throwIfError(blockEntity.fastForward());
    }

    @LuaFunction(mainThread = true)
    public final void seek(double seconds) throws LuaException {
        throwIfError(blockEntity.seek(seconds));
    }

    // === Status ===

    @LuaFunction(mainThread = true)
    public final String getState() {
        return blockEntity.state().getSerializedName();
    }

    @LuaFunction(mainThread = true)
    public final double getPosition() {
        return blockEntity.positionSeconds();
    }

    @LuaFunction(mainThread = true)
    public final double getLength() {
        return blockEntity.lengthSeconds();
    }

    @Nullable
    @LuaFunction(mainThread = true)
    public final String getRecording() {
        return blockEntity.recordingName();
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
    public final void eject() throws LuaException {
        if (!blockEntity.hasTape()) throw new LuaException("No tape in the deck");
        var level = blockEntity.getLevel();
        if (level == null) throw new LuaException("Deck is not loaded");
        var pos = blockEntity.getBlockPos();
        Containers.dropItemStack(level, pos.getX(), pos.getY() + 0.5, pos.getZ(), blockEntity.ejectTape());
    }

    // === Helpers ===

    private UUID requireTape() throws LuaException {
        if (!blockEntity.hasTape()) throw new LuaException("No tape in the deck");
        var tapeId = blockEntity.tapeId();
        if (tapeId == null) throw new LuaException("Tape is blank");
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
