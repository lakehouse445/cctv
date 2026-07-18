package gg.lakehouse.cctv;

import gg.lakehouse.cctv.tape.TapeItem;
import gg.lakehouse.cctv.tape.TapeLabelState;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CCTV.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonForgeEvents {
    private CommonForgeEvents() {
    }

    /** Tape labels are capped at {@link TapeItem#MAX_LABEL_CHARS}; longer anvil names are refused. */
    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        if (!(event.getLeft().getItem() instanceof TapeItem)) return;
        var name = event.getName();
        boolean tooLong = name != null && name.length() > TapeItem.MAX_LABEL_CHARS;

        // Only the client-side firing updates the indicator, so the render
        // thread never races the server thread in single-player.
        if (event.getPlayer().level().isClientSide) {
            TapeLabelState.anvilNameTooLong = tooLong;
        }

        if (tooLong) {
            event.setCanceled(true);
            // Cancelling alone leaves any previously-computed result in the
            // output slot, still takeable - clear it explicitly.
            if (event.getPlayer().containerMenu instanceof AnvilMenu anvil) {
                anvil.getSlot(2).set(ItemStack.EMPTY);
            }
        }
    }
}
