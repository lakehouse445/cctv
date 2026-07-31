package gg.lakehouse.cctv.tape;

import net.minecraft.nbt.Tag;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;

/**
 * A cassette whose label sticker takes dye. Only the label faces carry the
 * tint index, so dye recolors the sticker and nothing else; vanilla's
 * dye-in-crafting and cauldron-washing recognize any DyeableLeatherItem.
 * An undyed label is plain white, not vanilla's leather brown.
 */
public interface DyeableCassette extends DyeableLeatherItem {
    int DEFAULT_LABEL_COLOR = 0xFFFFFF;

    @Override
    default int getColor(ItemStack stack) {
        var display = stack.getTagElement("display");
        return display != null && display.contains("color", Tag.TAG_ANY_NUMERIC)
            ? display.getInt("color") : DEFAULT_LABEL_COLOR;
    }
}
