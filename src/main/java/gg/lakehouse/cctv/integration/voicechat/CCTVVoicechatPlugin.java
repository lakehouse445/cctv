package gg.lakehouse.cctv.integration.voicechat;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.microphone.MicrophoneRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Simple Voice Chat into CC:TV microphones. Only classloaded when SVC
 * is installed — it discovers this class via the ForgeVoicechatPlugin annotation.
 */
@ForgeVoicechatPlugin
public class CCTVVoicechatPlugin implements VoicechatPlugin {
    /** Inside this many blocks the microphone hears at full volume. */
    private static final int CLOSE_RANGE = 2;

    private final Map<UUID, PlayerPipeline> pipelines = new ConcurrentHashMap<>();
    private VoicechatApi api;

    @Override
    public String getPluginId() {
        return CCTV.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        CCTV.LOGGER.info("CC:TV voice chat integration active");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        var connection = event.getSenderConnection();
        if (connection == null || api == null) return;
        if (!(connection.getPlayer().getPlayer() instanceof ServerPlayer player)) return;

        var microphones = MicrophoneRegistry.near(player.level(),
            player.getX(), player.getEyeY(), player.getZ(), MicrophoneBlockEntity.PICKUP_RANGE);
        if (microphones.isEmpty()) return;
        if (microphones.stream().noneMatch(MicrophoneBlockEntity::isListening)) return;

        var opusData = event.getPacket().getOpusEncodedData();
        if (opusData == null || opusData.length == 0) return;

        var pipeline = pipelines.computeIfAbsent(player.getUUID(), id -> new PlayerPipeline(api.createDecoder()));
        byte[] samples;
        synchronized (pipeline) {
            var pcm = pipeline.decoder.decode(opusData);
            if (pcm == null || pcm.length == 0) return;
            samples = pipeline.filter.process(pcm);
        }
        long now = System.currentTimeMillis();
        if (now - lastHeardLog > 10_000) {
            lastHeardLog = now;
            CCTV.LOGGER.debug("Microphone pipeline: hearing {} ({} samples/packet into {} mic(s))",
                player.getName().getString(), samples.length, microphones.size());
        }
        for (var microphone : microphones) {
            process(samples, microphone, player);
        }
    }

    /** Rate limit for the hearing log; written only from the voice thread. */
    private long lastHeardLog;

    /**
     * Positions one voice on the microphone's stereo stage and pushes it.
     * Distance sets the volume: full within {@link #CLOSE_RANGE} blocks, then
     * a linear fade to silence at pickup range. The lateral angle from the
     * microphone's facing sets a balance-style pan — a centered voice is full
     * in both ears, an off-axis voice loses the far ear — so the stage reads
     * like the mic's own point of view.
     */
    private static void process(byte[] samples, MicrophoneBlockEntity microphone, ServerPlayer player) {
        var pos = microphone.getBlockPos();
        double dx = player.getX() - (pos.getX() + 0.5);
        double dy = player.getEyeY() - (pos.getY() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double gain = (MicrophoneBlockEntity.PICKUP_RANGE - distance)
            / (double) (MicrophoneBlockEntity.PICKUP_RANGE - CLOSE_RANGE);
        gain = Math.max(0, Math.min(1, gain));

        double pan = 0;
        var state = microphone.getBlockState();
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            var right = state.getValue(HorizontalDirectionalBlock.FACING).getClockWise();
            double lateral = Math.sqrt(dx * dx + dz * dz);
            if (lateral > 0.01) pan = (dx * right.getStepX() + dz * right.getStepZ()) / lateral;
        }
        double leftGain = gain * Math.min(1, 1 - pan);
        double rightGain = gain * Math.min(1, 1 + pan);

        var mono = new byte[samples.length];
        var left = new byte[samples.length];
        var right = new byte[samples.length];
        for (int i = 0; i < samples.length; i++) {
            mono[i] = (byte) Math.round(samples[i] * gain);
            left[i] = (byte) Math.round(samples[i] * leftGain);
            right[i] = (byte) Math.round(samples[i] * rightGain);
        }
        microphone.queueVoice(mono, left, right);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        var pipeline = pipelines.remove(event.getPlayerUuid());
        if (pipeline != null) pipeline.decoder.close();
    }

    private record PlayerPipeline(OpusDecoder decoder, Downsampler filter) {
        PlayerPipeline(OpusDecoder decoder) {
            this(decoder, new Downsampler());
        }
    }
}
