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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges Simple Voice Chat into CC:TV microphones. Only classloaded when SVC
 * is installed — it discovers this class via the ForgeVoicechatPlugin annotation.
 */
@ForgeVoicechatPlugin
public class CCTVVoicechatPlugin implements VoicechatPlugin {
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
        for (var microphone : microphones) {
            microphone.pushAudio(samples);
        }
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        var pipeline = pipelines.remove(event.getPlayerUuid());
        if (pipeline != null) pipeline.decoder.close();
    }

    private record PlayerPipeline(OpusDecoder decoder, RadioFilter filter) {
        PlayerPipeline(OpusDecoder decoder) {
            this(decoder, new RadioFilter());
        }
    }
}
