package gg.lakehouse.cctv.camera;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Whether someone has a chest open right now — resolved through the players'
 * open container menus (the lid angle itself is client-only animation state,
 * and reflection into the openers counter would break under production
 * mappings). Cameras show lids open while a container is in use.
 */
public final class ChestOpenness {
    private ChestOpenness() {
    }

    public static boolean isOpen(ServerLevel level, BlockEntity blockEntity) {
        if (!(blockEntity instanceof ChestBlockEntity chest)) return false;
        for (var player : level.players()) {
            if (player.containerMenu instanceof ChestMenu menu) {
                var container = menu.getContainer();
                if (container == chest) return true;
                if (container instanceof CompoundContainer compound && compound.contains(chest)) return true;
            }
        }
        return false;
    }
}
