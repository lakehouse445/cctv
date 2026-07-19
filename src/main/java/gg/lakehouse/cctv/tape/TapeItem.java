package gg.lakehouse.cctv.tape;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * A 10 MB cassette. The item only carries an id and a cached used-bytes figure;
 * the actual recordings live in the world folder under cctv/tapes/&lt;id&gt;.
 * Rename on an anvil to label it.
 */
public class TapeItem extends Item {
    public static final long CAPACITY_BYTES = 10L * 1024 * 1024;
    /** Hard cap for the written label; anvil renames are truncated to this. */
    public static final int MAX_LABEL_CHARS = 12;
    private static final String TAG_ID = "TapeId";
    private static final String TAG_USED = "UsedBytes";
    private static final String TAG_RESUME_RECORDING = "ResumeRecording";
    private static final String TAG_RESUME_SECONDS = "ResumeSeconds";

    public TapeItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static UUID getOrCreateId(ItemStack stack) {
        var tag = stack.getOrCreateTag();
        if (!tag.hasUUID(TAG_ID)) tag.putUUID(TAG_ID, UUID.randomUUID());
        return tag.getUUID(TAG_ID);
    }

    @Nullable
    public static UUID getId(ItemStack stack) {
        var tag = stack.getTag();
        return tag != null && tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : null;
    }

    public static long getUsedBytes(ItemStack stack) {
        var tag = stack.getTag();
        return tag == null ? 0 : tag.getLong(TAG_USED);
    }

    public static void setUsedBytes(ItemStack stack, long used) {
        stack.getOrCreateTag().putLong(TAG_USED, used);
    }

    // === Resume point: VHS tapes must be rewound ===

    /** Records where the head physically sits on this tape; null/zero clears it. */
    public static void setResumePoint(ItemStack stack, @Nullable String recording, double seconds) {
        var tag = stack.getOrCreateTag();
        if (recording == null || seconds <= 0) {
            tag.remove(TAG_RESUME_RECORDING);
            tag.remove(TAG_RESUME_SECONDS);
        } else {
            tag.putString(TAG_RESUME_RECORDING, recording);
            tag.putDouble(TAG_RESUME_SECONDS, seconds);
        }
    }

    @Nullable
    public static String getResumeRecording(ItemStack stack) {
        var tag = stack.getTag();
        return tag != null && tag.contains(TAG_RESUME_RECORDING) ? tag.getString(TAG_RESUME_RECORDING) : null;
    }

    public static double getResumeSeconds(ItemStack stack) {
        var tag = stack.getTag();
        return tag == null ? 0 : tag.getDouble(TAG_RESUME_SECONDS);
    }

    public static String formatTime(double seconds) {
        int total = (int) Math.round(seconds);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        long used = getUsedBytes(stack);
        tooltip.add(Component.literal(formatBytes(used) + " / " + formatBytes(CAPACITY_BYTES) + " used")
            .withStyle(ChatFormatting.GRAY));
        var resume = getResumeRecording(stack);
        if (resume != null) {
            tooltip.add(Component.literal("Not rewound - " + formatTime(getResumeSeconds(stack)) + " into " + resume)
                .withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
            private gg.lakehouse.cctv.client.TapeItemRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new gg.lakehouse.cctv.client.TapeItemRenderer();
                return renderer;
            }
        });
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
