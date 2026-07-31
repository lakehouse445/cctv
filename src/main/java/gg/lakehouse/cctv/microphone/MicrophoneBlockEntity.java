package gg.lakehouse.cctv.microphone;

import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * An intercom microphone. A tick-driven mixer folds two source kinds into
 * one 16 kHz 8-bit stream: voice pieces queued from the voice network
 * thread, and world sounds dropped on the mixer by the sound tap. Completed
 * chunks pack as DFPWM and raise microphone_audio events on attached
 * computers. The stream runs while any source is active plus a short tail,
 * and goes quiet between - recordings pad the gaps themselves.
 */
public class MicrophoneBlockEntity extends BlockEntity {
    public static final int PICKUP_RANGE = 8;
    public static final int SAMPLE_RATE = 16000;
    /** 0.1 s of audio per Lua event. */
    private static final int CHUNK_SAMPLES = 1600;
    private static final int MAX_QUEUED_CHUNKS = 8;
    /** Samples the mixer produces per server tick. */
    private static final int TICK_SAMPLES = SAMPLE_RATE / 20;
    /** Ticks of silence streamed after the last active source, as a natural tail. */
    private static final int ACTIVE_TAIL_TICKS = 10;
    private static final int MAX_QUEUED_VOICE = 40;
    private static final int MAX_PLAYING_SOUNDS = 32;

    private final MicrophonePeripheral peripheral = new MicrophonePeripheral(this);
    /** Mono, left, right DFPWM streams for the Lua events. */
    private final DfpwmEncoder[] encoders = {new DfpwmEncoder(), new DfpwmEncoder(), new DfpwmEncoder()};
    private final Object audioLock = new Object();
    /** Each chunk is three parallel channels: mono mix, left, right. */
    private final ArrayDeque<byte[][]> chunks = new ArrayDeque<>();
    private byte[][] accumulator = {new byte[CHUNK_SAMPLES], new byte[CHUNK_SAMPLES], new byte[CHUNK_SAMPLES]};
    private int accumulated;
    /** Voice pieces (three parallel channels each) awaiting the mixer. */
    private final ArrayDeque<byte[][]> voiceQueue = new ArrayDeque<>();
    private int voiceOffset;
    private final List<PlayingSound> sounds = new ArrayList<>();
    private int activeTail;
    private volatile boolean listening;
    @javax.annotation.Nullable
    private String displayText;

    /** Cells on the intercom's little screen. */
    public static final int DISPLAY_CELLS = 6;
    // Client-side button travel, owned by MicrophoneRenderer. -1 = unset.
    public float clientPress = -1;
    public long clientPressAt;

    private static final class PlayingSound {
        byte[] pcm;
        float gainMono;
        float gainLeft;
        float gainRight;
        float cursor;
        float step;
    }

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
        if (!listening) {
            synchronized (audioLock) {
                voiceQueue.clear();
                voiceOffset = 0;
                sounds.clear();
                chunks.clear();
                accumulated = 0;
                activeTail = 0;
            }
        }
        setChanged();
        syncToClients();
    }

    @javax.annotation.Nullable
    public String displayText() {
        return displayText;
    }

    /** Sets the intercom's custom readout, or null to return to the automatic one. */
    public void setDisplayText(@javax.annotation.Nullable String text) {
        if (text != null && text.length() > DISPLAY_CELLS) text = text.substring(0, DISPLAY_CELLS);
        if (java.util.Objects.equals(displayText, text)) return;
        displayText = text;
        setChanged();
        syncToClients();
    }

    /** The renderers read listening state and display text; push every change. */
    private void syncToClients() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /** Called from the voice network thread with the mono mix and the stereo stage channels. */
    public void queueVoice(byte[] mono, byte[] left, byte[] right) {
        if (!listening) return;
        synchronized (audioLock) {
            if (voiceQueue.size() >= MAX_QUEUED_VOICE) return;
            voiceQueue.addLast(new byte[][]{mono, left, right});
        }
    }

    /** Called from the sound loader thread: put a world sound on the mixer, panned by its position. */
    public void hearSound(byte[] pcm, double x, double y, double z, float amplitude, double range, float pitch) {
        if (!listening || pcm.length == 0) return;
        double dx = x - (worldPosition.getX() + 0.5);
        double dy = y - (worldPosition.getY() + 0.5);
        double dz = z - (worldPosition.getZ() + 0.5);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float gain = (float) (amplitude * Math.max(0, 1 - distance / range));
        if (gain < 0.004F) return;

        double pan = 0;
        var state = getBlockState();
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            var rightward = state.getValue(HorizontalDirectionalBlock.FACING).getClockWise();
            double lateral = Math.sqrt(dx * dx + dz * dz);
            if (lateral > 0.01) pan = (dx * rightward.getStepX() + dz * rightward.getStepZ()) / lateral;
        }
        var sound = new PlayingSound();
        sound.pcm = pcm;
        sound.gainMono = gain;
        sound.gainLeft = gain * (float) Math.min(1, 1 - pan);
        sound.gainRight = gain * (float) Math.min(1, 1 + pan);
        sound.step = Math.max(0.25F, Math.min(4, pitch));
        synchronized (audioLock) {
            if (sounds.size() < MAX_PLAYING_SOUNDS) sounds.add(sound);
        }
    }

    public void serverTick() {
        synchronized (audioLock) {
            if (listening) mixTick();
        }
        while (true) {
            byte[][] chunk;
            synchronized (audioLock) {
                chunk = chunks.pollFirst();
            }
            if (chunk == null) return;
            // Server thread only: the encoders' predictor state must stay
            // continuous across chunks, one encoder per channel.
            peripheral.queueAudioEvent(
                encoders[0].encode(chunk[0]),
                encoders[1].encode(chunk[1]),
                encoders[2].encode(chunk[2]));
        }
    }

    /** Under audioLock: one tick of the mixer, voice plus world sounds. */
    private void mixTick() {
        boolean idle = voiceQueue.isEmpty() && sounds.isEmpty();
        if (idle) {
            if (activeTail <= 0) return;
            activeTail--;
        } else {
            activeTail = ACTIVE_TAIL_TICKS;
        }

        var mono = new int[TICK_SAMPLES];
        var left = new int[TICK_SAMPLES];
        var right = new int[TICK_SAMPLES];

        int filled = 0;
        while (filled < TICK_SAMPLES && !voiceQueue.isEmpty()) {
            var piece = voiceQueue.peekFirst();
            int take = Math.min(TICK_SAMPLES - filled, piece[0].length - voiceOffset);
            for (int i = 0; i < take; i++) {
                mono[filled + i] += piece[0][voiceOffset + i];
                left[filled + i] += piece[1][voiceOffset + i];
                right[filled + i] += piece[2][voiceOffset + i];
            }
            filled += take;
            voiceOffset += take;
            if (voiceOffset >= piece[0].length) {
                voiceQueue.pollFirst();
                voiceOffset = 0;
            }
        }

        var iterator = sounds.iterator();
        while (iterator.hasNext()) {
            var sound = iterator.next();
            for (int i = 0; i < TICK_SAMPLES; i++) {
                int index = (int) sound.cursor;
                if (index >= sound.pcm.length) break;
                int sample = sound.pcm[index];
                mono[i] += Math.round(sample * sound.gainMono);
                left[i] += Math.round(sample * sound.gainLeft);
                right[i] += Math.round(sample * sound.gainRight);
                sound.cursor += sound.step;
            }
            if ((int) sound.cursor >= sound.pcm.length) iterator.remove();
        }

        accumulate(clamp(mono), clamp(left), clamp(right));
    }

    private static byte[] clamp(int[] samples) {
        var out = new byte[samples.length];
        for (int i = 0; i < samples.length; i++) {
            out[i] = (byte) Math.max(-127, Math.min(127, samples[i]));
        }
        return out;
    }

    /** Under audioLock: append a tick's worth of channels to the chunk accumulator. */
    private void accumulate(byte[] mono, byte[] left, byte[] right) {
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
        if (displayText != null) tag.putString("DisplayText", displayText);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        listening = tag.getBoolean("Listening");
        displayText = tag.contains("DisplayText") ? tag.getString("DisplayText") : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        var tag = new CompoundTag();
        tag.putBoolean("Listening", listening);
        if (displayText != null) tag.putString("DisplayText", displayText);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
