package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.CCTV;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CCTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(TapeItemRenderer.SPRITE_MODEL);
        event.register(CassetteRenderer.CASSETTE_MODEL);
        event.register(CassetteRenderer.CASSETTE_GLASS_MODEL);
    }
}
