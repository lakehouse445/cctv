package gg.lakehouse.cctv.tape;

import net.minecraft.world.item.Item;

/**
 * Audio cassette. Holds no behavior yet - recording logic comes in a later
 * pass; for now it is a dyeable-label item like the VHS tape.
 */
public class CompactCassetteItem extends Item implements DyeableCassette {
    public CompactCassetteItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
            private gg.lakehouse.cctv.client.CompactCassetteItemRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new gg.lakehouse.cctv.client.CompactCassetteItemRenderer();
                return renderer;
            }
        });
    }
}
