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
        event.register(CassetteRenderer.CASSETTE_MODEL);
        event.register(CassetteRenderer.CASSETTE_GLASS_MODEL);
        event.register(CassetteRenderer.CASSETTE_REEL_MODEL);
        event.register(CompactCassetteItemRenderer.MODEL_3D);
        event.register(CompactCassetteItemRenderer.GLASS_MODEL);
        for (var model : PlaybackDeckRenderer.BUTTON_MODELS) event.register(model);
        event.register(MicrophoneRenderer.BUTTON_MODEL);
        event.register(CameraRenderer.ARM_MODEL);
        event.register(CameraRenderer.HEAD_MODEL);
    }

    private static boolean geometryExported;

    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        // Dev only: refresh the vanilla geometry pack the mod ships
        // (copied into resources as entity_geometry.json.gz).
        if (!net.minecraftforge.fml.loading.FMLEnvironment.production) {
            event.enqueueWork(() -> EntityGeometryExporter.export(
                net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("entity_geometry.json.gz")));
        }
        // Every client: once models are baked, export ALL layers (modded
        // included) so admins can hand the file to a dedicated server.
        event.enqueueWork(() -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (net.minecraftforge.event.TickEvent.ClientTickEvent tick) -> {
                if (geometryExported || tick.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
                geometryExported = true;
                EntityGeometryExporter.exportAll(
                    net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("cctv-entity-geometry.json.gz"));
            }));
    }

    @SubscribeEvent
    public static void onItemColors(net.minecraftforge.client.event.RegisterColorHandlersEvent.Item event) {
        // Both cassettes' dyeable colour strips carry tint index 1 on their
        // 3D models (the only models now - GUI renders them too); every
        // other index is -1.
        event.register((stack, tintIndex) -> tintIndex == 1
                ? ((net.minecraft.world.item.DyeableLeatherItem) stack.getItem()).getColor(stack) : -1,
            ModRegistry.TAPE.get(), ModRegistry.COMPACT_CASSETTE.get());
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistry.PLAYBACK_DECK_BLOCK_ENTITY.get(), PlaybackDeckRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.CAMERA_BLOCK_ENTITY.get(), CameraRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.VCR_BLOCK_ENTITY.get(), VcrRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.MICROPHONE_BLOCK_ENTITY.get(), MicrophoneRenderer::new);
    }
}
