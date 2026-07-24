package gg.lakehouse.cctv;

import com.mojang.logging.LogUtils;
import dan200.computercraft.api.ForgeComputerCraftAPI;
import gg.lakehouse.cctv.camera.CameraBlockEntity;
import gg.lakehouse.cctv.capture.CaptureCardBlockEntity;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.playback.PlaybackDeckBlockEntity;
import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(CCTV.MOD_ID)
public final class CCTV {
    public static final String MOD_ID = "cctv";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CCTV() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.register(modBus);
        PacketHandler.register();
        modBus.addListener(this::commonSetup);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.server.ServerStartedEvent event) ->
                gg.lakehouse.cctv.camera.server.ServerCameraAssets.begin(event.getServer()));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.TickEvent.LevelTickEvent event) -> {
                if (event.phase == net.minecraftforge.event.TickEvent.Phase.END
                    && event.level instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && serverLevel.getGameTime() % 5 == 0) {
                    gg.lakehouse.cctv.link.CameraLinks.get(serverLevel).tick(serverLevel);
                }
            });
        // Links render the moment the item is held: the client needs the
        // link list before any right-click ever syncs it.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) ->
                syncLinks(event.getEntity()));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) ->
                syncLinks(event.getEntity()));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) ->
                syncLinks(event.getEntity()));
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                gg.lakehouse.cctv.client.CameraLinkRenderer::onRenderLevel));
    }

    private static void syncLinks(net.minecraft.world.entity.player.Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
            && serverPlayer.serverLevel() != null) {
            gg.lakehouse.cctv.link.CameraLinks.get(serverPlayer.serverLevel()).syncTo(serverPlayer);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> ForgeComputerCraftAPI.registerPeripheralProvider((level, pos, side) -> {
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof CameraBlockEntity camera) {
                return LazyOptional.of(() -> camera.peripheral());
            }
            if (blockEntity instanceof CaptureCardBlockEntity captureCard) {
                return LazyOptional.of(() -> captureCard.peripheral());
            }
            if (blockEntity instanceof MicrophoneBlockEntity microphone) {
                return LazyOptional.of(() -> microphone.peripheral());
            }
            if (blockEntity instanceof PlaybackDeckBlockEntity deck) {
                return LazyOptional.of(() -> deck.peripheral());
            }
            if (blockEntity instanceof VcrBlockEntity vcr) {
                return LazyOptional.of(() -> vcr.peripheral());
            }
            return LazyOptional.empty();
        }));
    }
}
