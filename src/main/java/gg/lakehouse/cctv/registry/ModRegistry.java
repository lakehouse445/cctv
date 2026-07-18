package gg.lakehouse.cctv.registry;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.capture.CaptureCardBlock;
import gg.lakehouse.cctv.capture.CaptureCardBlockEntity;
import gg.lakehouse.cctv.microphone.MicrophoneBlock;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import gg.lakehouse.cctv.tape.TapeItem;
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

    public static final RegistryObject<CaptureCardBlock> CAPTURE_CARD = BLOCKS.register("capture_card",
        () -> new CaptureCardBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> CAPTURE_CARD_ITEM = ITEMS.register("capture_card",
        () -> new BlockItem(CAPTURE_CARD.get(), new Item.Properties()));

    public static final RegistryObject<Item> TAPE = ITEMS.register("tape",
        () -> new TapeItem(new Item.Properties()));

    public static final RegistryObject<MicrophoneBlock> MICROPHONE = BLOCKS.register("microphone",
        () -> new MicrophoneBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(1.5F)
            .sound(SoundType.STONE)));

    public static final RegistryObject<Item> MICROPHONE_ITEM = ITEMS.register("microphone",
        () -> new BlockItem(MICROPHONE.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<MicrophoneBlockEntity>> MICROPHONE_BLOCK_ENTITY = BLOCK_ENTITIES.register("microphone",
        () -> BlockEntityType.Builder.of(MicrophoneBlockEntity::new, MICROPHONE.get()).build(null));

    public static final RegistryObject<BlockEntityType<CaptureCardBlockEntity>> CAPTURE_CARD_BLOCK_ENTITY = BLOCK_ENTITIES.register("capture_card",
        () -> BlockEntityType.Builder.of(CaptureCardBlockEntity::new, CAPTURE_CARD.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cctv"))
            .icon(() -> CAPTURE_CARD_ITEM.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(CAPTURE_CARD_ITEM.get());
                output.accept(TAPE.get());
                output.accept(MICROPHONE_ITEM.get());
            })
            .build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
