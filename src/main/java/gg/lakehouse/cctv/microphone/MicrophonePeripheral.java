package gg.lakehouse.cctv.microphone;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua-facing microphone. Raises microphone_audio events:
 * event, side, samples, left, right — tables of 8-bit signed amplitudes at
 * 48 kHz, ready to feed straight into speaker.playAudio. samples is the mono
 * mix; left/right carry the stereo stage, panned by each voice's position
 * relative to the microphone's facing.
 */
public class MicrophonePeripheral implements IPeripheral {
    private final MicrophoneBlockEntity blockEntity;
    private final Set<IComputerAccess> computers = ConcurrentHashMap.newKeySet();

    public MicrophonePeripheral(MicrophoneBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    @Override
    public String getType() {
        return "microphone";
    }

    @Override
    public Object getTarget() {
        return blockEntity;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other || (other instanceof MicrophonePeripheral peripheral && peripheral.blockEntity == blockEntity);
    }

    @Override
    public void attach(IComputerAccess computer) {
        computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        computers.remove(computer);
    }

    void queueAudioEvent(byte[] mono, byte[] left, byte[] right) {
        if (computers.isEmpty()) return;
        var monoTable = toTable(mono);
        var leftTable = toTable(left);
        var rightTable = toTable(right);
        for (var computer : computers) {
            computer.queueEvent("microphone_audio", computer.getAttachmentName(), monoTable, leftTable, rightTable);
        }
    }

    private static ArrayList<Integer> toTable(byte[] samples) {
        var table = new ArrayList<Integer>(samples.length);
        for (byte sample : samples) table.add((int) sample);
        return table;
    }

    @LuaFunction(mainThread = true)
    public final void setListening(boolean listening) {
        blockEntity.setListening(listening);
    }

    @LuaFunction(mainThread = true)
    public final boolean isListening() {
        return blockEntity.isListening();
    }

    @LuaFunction
    public final int getSampleRate() {
        return MicrophoneBlockEntity.SAMPLE_RATE;
    }

    @LuaFunction
    public final int getPickupRange() {
        return MicrophoneBlockEntity.PICKUP_RANGE;
    }
}
