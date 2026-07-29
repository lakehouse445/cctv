package gg.lakehouse.cctv.mixin;

import gg.lakehouse.cctv.microphone.sound.LevelEventSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Level events (splash potions, block breaks, dispensers...) have no Forge
 * hook and their packets exclude the acting player, so a lone player's
 * actions never reach the network at all. This taps the source, before any
 * recipient filtering, and hands the event to the microphone sound table.
 */
@Mixin(ServerLevel.class)
public class ServerLevelMixin {
    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void cctv$levelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo callback) {
        LevelEventSounds.handle((ServerLevel) (Object) this, type, pos, data);
    }
}
