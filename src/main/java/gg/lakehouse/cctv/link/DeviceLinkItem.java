package gg.lakehouse.cctv.link;

import gg.lakehouse.cctv.camera.CameraBlockEntity;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * Links a device to a wired modem without cables: click the device, then the
 * modem. The link rides an unbroken run of solid blocks between them, shown
 * as a snake while holding this item. Sneak-click a device or modem to
 * unlink it. The Camera Link and Microphone Link are the same item pointed
 * at different devices.
 */
public class DeviceLinkItem extends Item {
    public enum Kind {
        CAMERA("Camera"),
        MICROPHONE("Microphone");

        final String noun;

        Kind(String noun) {
            this.noun = noun;
        }
    }

    private final Kind kind;

    public DeviceLinkItem(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    private boolean matches(@Nullable BlockEntity entity) {
        return kind == Kind.CAMERA ? entity instanceof CameraBlockEntity : entity instanceof MicrophoneBlockEntity;
    }

    @Override
    public InteractionResult onItemUseFirst(net.minecraft.world.item.ItemStack stack, UseOnContext context) {
        // Runs before the block's own use(): a wired modem's right-click
        // otherwise wins the click and toggles its physical peripheral
        // instead of receiving the link.
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var player = context.getPlayer();
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            var state = level.getBlockState(pos);
            var name = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
            boolean handled = matches(level.getBlockEntity(pos))
                || (name != null && "computercraft".equals(name.getNamespace()));
            return handled ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        var links = CameraLinks.get(serverLevel);
        links.syncTo(serverPlayer);

        if (matches(level.getBlockEntity(pos))) {
            if (player.isShiftKeyDown()) {
                bar(serverPlayer, links.unlinkDevice(serverLevel, pos)
                    ? kind.noun + " unlinked" : "This " + kind.noun.toLowerCase() + " is not linked");
            } else {
                stack.getOrCreateTag().putLong("LinkDevice", pos.asLong());
                bar(serverPlayer, kind.noun + " selected - now click a wired modem");
            }
            return InteractionResult.CONSUME;
        }

        if (CameraLinks.wiredElement(serverLevel, pos) != null) {
            if (player.isShiftKeyDown()) {
                bar(serverPlayer, links.unlinkModem(serverLevel, pos)
                    ? "Modem links cleared" : "This modem has no links");
                return InteractionResult.CONSUME;
            }
            var tag = stack.getTag();
            if (tag == null || !tag.contains("LinkDevice")) {
                bar(serverPlayer, "Select a " + kind.noun.toLowerCase() + " first");
                return InteractionResult.CONSUME;
            }
            var device = BlockPos.of(tag.getLong("LinkDevice"));
            var error = links.link(serverLevel, device, pos);
            if (error != null) {
                bar(serverPlayer, error);
            } else {
                tag.remove("LinkDevice");
                long count = links.links().stream().filter(link -> link.modem.equals(pos)).count();
                bar(serverPlayer, kind.noun + " linked (" + count + "/" + CameraLinks.MODEM_LIMIT + ")");
                links.tick(serverLevel);
            }
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    private static void bar(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }
}
