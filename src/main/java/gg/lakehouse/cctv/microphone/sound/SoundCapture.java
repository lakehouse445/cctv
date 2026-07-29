package gg.lakehouse.cctv.microphone.sound;

import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.microphone.MicrophoneRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * The one funnel between "a sound happened here" and the microphone mixers:
 * finds listening mics in the vanilla audible range (16 blocks, stretched by
 * volume), resolves the clip off-thread, and drops it on each mic panned
 * and faded by position. Safe to call from any thread.
 */
public final class SoundCapture {
    private SoundCapture() {
    }

    public static void capture(ServerLevel level, double x, double y, double z,
                               ResourceLocation sound, float volume, float pitch) {
        if (volume <= 0) return;
        double range = 16.0 * Math.max(1, volume);
        var microphones = MicrophoneRegistry.near(level, x, y, z, range)
            .stream().filter(MicrophoneBlockEntity::isListening).toList();
        if (microphones.isEmpty()) return;
        var assets = SoundAssets.get(level.getServer());
        if (assets == null) return;

        float amplitude = Math.min(1, volume);
        assets.fetch(sound).thenAccept(clip -> {
            if (clip == null) return;
            for (var microphone : microphones) {
                microphone.hearSound(clip.pcm(), x, y, z,
                    amplitude * clip.volume(), range, pitch * clip.pitch());
            }
        });
    }
}
