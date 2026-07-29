package gg.lakehouse.cctv.microphone.sound;

import gg.lakehouse.cctv.CCTV;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Feeds played sounds into nearby microphones. The Forge sound event fires
 * at the source, before recipient filtering, so sounds whose packets
 * exclude the acting player (doors, buttons, chests opened by the only
 * player online) are heard all the same. Level events travel a separate
 * road: the ServerLevel mixin and LevelEventSounds.
 */
@Mod.EventBusSubscriber(modid = CCTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SoundTap {
    private SoundTap() {
    }

    @SubscribeEvent
    public static void onSound(PlayLevelSoundEvent.AtPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getSource() == SoundSource.MUSIC) return;
        var holder = event.getSound();
        if (holder == null) return;
        var position = event.getPosition();
        SoundCapture.capture(level, position.x, position.y, position.z,
            holder.value().getLocation(), event.getNewVolume(), event.getNewPitch());
    }

    @SubscribeEvent
    public static void onEntitySound(PlayLevelSoundEvent.AtEntity event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getSource() == SoundSource.MUSIC) return;
        var holder = event.getSound();
        if (holder == null) return;
        var entity = event.getEntity();
        SoundCapture.capture(level, entity.getX(), entity.getY(), entity.getZ(),
            holder.value().getLocation(), event.getNewVolume(), event.getNewPitch());
    }
}
