package gg.lakehouse.cctv.capture;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.Optional;

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

    @LuaFunction
    public final void export() throws LuaException {
        throw new LuaException("Export from the capture card's screen for now - Lua export arrives with tapes");
    }
}
