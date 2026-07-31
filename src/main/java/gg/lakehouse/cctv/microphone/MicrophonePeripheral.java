package gg.lakehouse.cctv.microphone;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua-facing microphone. Raises microphone_audio events:
 * event, side, samples, left, right — DFPWM strings at 16 kHz, one bit per
 * sample. Unpack with cc.audio.dfpwm.make_decoder, then repeat each value
 * three times to reach the speaker's 48 kHz. samples is the mono mix;
 * left/right carry the stereo stage, panned by each voice's position
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

    /** The channels arrive DFPWM-packed; byte arrays cross into Lua as strings. */
    void queueAudioEvent(byte[] mono, byte[] left, byte[] right) {
        for (var computer : computers) {
            computer.queueEvent("microphone_audio", computer.getAttachmentName(), mono, left, right);
        }
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

    /**
     * Sets the intercom's screen text (VCR-style); call with no text to
     * restore the automatic LIVE/MUTED readout. Desktop mics have no screen.
     */
    @LuaFunction(mainThread = true)
    public final void setDisplay(java.util.Optional<String> text) throws dan200.computercraft.api.lua.LuaException {
        if (text.isPresent() && text.get().length() > MicrophoneBlockEntity.DISPLAY_CELLS) {
            throw new dan200.computercraft.api.lua.LuaException(
                "Display text is limited to " + MicrophoneBlockEntity.DISPLAY_CELLS + " characters");
        }
        blockEntity.setDisplayText(text.orElse(null));
    }

    @javax.annotation.Nullable
    @LuaFunction(mainThread = true)
    public final String getDisplay() {
        return blockEntity.displayText();
    }
}
