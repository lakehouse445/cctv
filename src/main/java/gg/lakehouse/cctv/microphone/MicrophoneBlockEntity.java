package gg.lakehouse.cctv.microphone;

import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * An intercom microphone. The voice chat plugin pushes filtered 8-bit 48 kHz
 * samples in from the voice network thread; the server tick drains completed
 * chunks and raises microphone_audio events on attached computers.
 */
public class MicrophoneBlockEntity extends BlockEntity {
    public static final int PICKUP_RANGE = 8;
    public static final int SAMPLE_RATE = 48000;
    /** 0.1 s of audio per Lua event. */
    private static final int CHUNK_SAMPLES = 4800;
    private static final int MAX_QUEUED_CHUNKS = 8;

    private final MicrophonePeripheral peripheral = new MicrophonePeripheral(this);
    private final Object audioLock = new Object();
    /** Each chunk is three parallel channels: mono mix, left, right. */
    private final ArrayDeque<byte[][]> chunks = new ArrayDeque<>();
    private byte[][] accumulator = {new byte[CHUNK_SAMPLES], new byte[CHUNK_SAMPLES], new byte[CHUNK_SAMPLES]};
    private int accumulated;
    private volatile boolean listening;

    public MicrophoneBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.MICROPHONE_BLOCK_ENTITY.get(), pos, state);
    }

    public MicrophonePeripheral peripheral() {
        return peripheral;
    }

    public boolean isListening() {
        return listening;
    }

    public void setListening(boolean listening) {
        this.listening = listening;
        setChanged();
    }

    /** Called from the voice network thread with the mono mix and the stereo stage channels. */
    public void pushAudio(byte[] mono, byte[] left, byte[] right) {
        if (!listening) return;
        synchronized (audioLock) {
            int offset = 0;
            while (offset < mono.length) {
                int copy = Math.min(mono.length - offset, CHUNK_SAMPLES - accumulated);
                System.arraycopy(mono, offset, accumulator[0], accumulated, copy);
                System.arraycopy(left, offset, accumulator[1], accumulated, copy);
                System.arraycopy(right, offset, accumulator[2], accumulated, copy);
                accumulated += copy;
                offset += copy;
                if (accumulated == CHUNK_SAMPLES) {
                    if (chunks.size() >= MAX_QUEUED_CHUNKS) chunks.pollFirst();
                    chunks.addLast(accumulator);
                    accumulator = new byte[][]{new byte[CHUNK_SAMPLES], new byte[CHUNK_SAMPLES], new byte[CHUNK_SAMPLES]};
                    accumulated = 0;
                }
            }
        }
    }

    public void serverTick() {
        while (true) {
            byte[][] chunk;
            synchronized (audioLock) {
                chunk = chunks.pollFirst();
            }
            if (chunk == null) return;
            peripheral.queueAudioEvent(chunk[0], chunk[1], chunk[2]);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) MicrophoneRegistry.add(this);
    }

    @Override
    public void setRemoved() {
        MicrophoneRegistry.remove(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        MicrophoneRegistry.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Listening", listening);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        listening = tag.getBoolean("Listening");
    }
}
