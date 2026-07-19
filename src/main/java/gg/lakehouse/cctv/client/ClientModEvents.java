package gg.lakehouse.cctv.client;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
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
        event.register(CassetteRenderer.CASSETTE_REEL_MODEL);
        for (var model : PlaybackDeckRenderer.BUTTON_MODELS) event.register(model);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistry.PLAYBACK_DECK_BLOCK_ENTITY.get(), PlaybackDeckRenderer::new);
    }
}
