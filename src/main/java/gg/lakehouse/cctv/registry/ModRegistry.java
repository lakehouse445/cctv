package gg.lakehouse.cctv.registry;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.camera.CameraBlock;
import gg.lakehouse.cctv.camera.CameraBlockEntity;
import gg.lakehouse.cctv.capture.CaptureCardBlock;
import gg.lakehouse.cctv.capture.CaptureCardBlockEntity;
import gg.lakehouse.cctv.microphone.DesktopMicrophoneBlock;
import gg.lakehouse.cctv.microphone.MicrophoneBlock;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.playback.PlaybackDeckBlock;
import gg.lakehouse.cctv.playback.PlaybackDeckBlockEntity;
import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.vcr.VcrBlock;
import gg.lakehouse.cctv.vcr.VcrBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRegistry {
    private ModRegistry() {
    }

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, CCTV.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, CCTV.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CCTV.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CCTV.MOD_ID);

    public static final RegistryObject<CameraBlock> CAMERA = BLOCKS.register("camera",
        () -> new CameraBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_GRAY)
            .strength(2.0F)
            .sound(SoundType.METAL)));

    public static final RegistryObject<Item> CAMERA_ITEM = ITEMS.register("camera",
        () -> new BlockItem(CAMERA.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<CameraBlockEntity>> CAMERA_BLOCK_ENTITY = BLOCK_ENTITIES.register("camera",
        () -> BlockEntityType.Builder.of(CameraBlockEntity::new, CAMERA.get()).build(null));

    public static final RegistryObject<CaptureCardBlock> CAPTURE_CARD = BLOCKS.register("capture_card",
        () -> new CaptureCardBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> CAPTURE_CARD_ITEM = ITEMS.register("capture_card",
        () -> new BlockItem(CAPTURE_CARD.get(), new Item.Properties()));

    public static final RegistryObject<Item> TAPE = ITEMS.register("tape",
        () -> new TapeItem(new Item.Properties()));

    /** Audio cassette. Model and item only for now; behavior comes in a later pass. */
    public static final RegistryObject<Item> COMPACT_CASSETTE = ITEMS.register("compact_cassette",
        () -> new gg.lakehouse.cctv.tape.CompactCassetteItem(new Item.Properties()));

    public static final RegistryObject<MicrophoneBlock> INTERCOM = BLOCKS.register("intercom",
        () -> new MicrophoneBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(1.5F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> INTERCOM_ITEM = ITEMS.register("intercom",
        () -> new BlockItem(INTERCOM.get(), new Item.Properties()));

    public static final RegistryObject<DesktopMicrophoneBlock> DESKTOP_MICROPHONE = BLOCKS.register("desktop_microphone",
        () -> new DesktopMicrophoneBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(1.5F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> DESKTOP_MICROPHONE_ITEM = ITEMS.register("desktop_microphone",
        () -> new BlockItem(DESKTOP_MICROPHONE.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<MicrophoneBlockEntity>> MICROPHONE_BLOCK_ENTITY = BLOCK_ENTITIES.register("microphone",
        () -> BlockEntityType.Builder.of(MicrophoneBlockEntity::new, INTERCOM.get(), DESKTOP_MICROPHONE.get()).build(null));

    public static final RegistryObject<PlaybackDeckBlock> PLAYBACK_DECK = BLOCKS.register("playback_deck",
        () -> new PlaybackDeckBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> PLAYBACK_DECK_ITEM = ITEMS.register("playback_deck",
        () -> new BlockItem(PLAYBACK_DECK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<PlaybackDeckBlockEntity>> PLAYBACK_DECK_BLOCK_ENTITY = BLOCK_ENTITIES.register("playback_deck",
        () -> BlockEntityType.Builder.of(PlaybackDeckBlockEntity::new, PLAYBACK_DECK.get()).build(null));

    public static final RegistryObject<VcrBlock> VCR = BLOCKS.register("vcr",
        () -> new VcrBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> VCR_ITEM = ITEMS.register("vcr",
        () -> new BlockItem(VCR.get(), new Item.Properties()));

    public static final RegistryObject<Item> CAMERA_LINK = ITEMS.register("camera_link",
        () -> new gg.lakehouse.cctv.link.DeviceLinkItem(
            gg.lakehouse.cctv.link.DeviceLinkItem.Kind.CAMERA, new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> MICROPHONE_LINK = ITEMS.register("microphone_link",
        () -> new gg.lakehouse.cctv.link.DeviceLinkItem(
            gg.lakehouse.cctv.link.DeviceLinkItem.Kind.MICROPHONE, new Item.Properties().stacksTo(1)));

    public static final RegistryObject<BlockEntityType<VcrBlockEntity>> VCR_BLOCK_ENTITY = BLOCK_ENTITIES.register("vcr",
        () -> BlockEntityType.Builder.of(VcrBlockEntity::new, VCR.get()).build(null));

    public static final RegistryObject<BlockEntityType<CaptureCardBlockEntity>> CAPTURE_CARD_BLOCK_ENTITY = BLOCK_ENTITIES.register("capture_card",
        () -> BlockEntityType.Builder.of(CaptureCardBlockEntity::new, CAPTURE_CARD.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cctv"))
            .icon(() -> CAPTURE_CARD_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> {
                // Full-block peripherals, then the smaller ones, then items.
                output.accept(CAPTURE_CARD_ITEM.get());
                output.accept(PLAYBACK_DECK_ITEM.get());
                output.accept(VCR_ITEM.get());
                output.accept(CAMERA_ITEM.get());
                output.accept(INTERCOM_ITEM.get());
                output.accept(DESKTOP_MICROPHONE_ITEM.get());
                output.accept(TAPE.get());
                output.accept(COMPACT_CASSETTE.get());
                output.accept(CAMERA_LINK.get());
                output.accept(MICROPHONE_LINK.get());
            })
            .build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
