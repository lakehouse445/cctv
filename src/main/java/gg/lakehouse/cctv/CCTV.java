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
