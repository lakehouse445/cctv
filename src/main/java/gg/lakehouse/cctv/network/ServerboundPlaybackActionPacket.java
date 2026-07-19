package gg.lakehouse.cctv.network;

import gg.lakehouse.cctv.playback.PlaybackDeckBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerboundPlaybackActionPacket(BlockPos pos, Action action, String name, double seconds) {
    public enum Action {
        PLAY, SELECT, PAUSE, STOP, REWIND, FAST_FORWARD, SEEK, EJECT, REFRESH
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(action);
        buf.writeUtf(name);
        buf.writeDouble(seconds);
    }

    public static ServerboundPlaybackActionPacket decode(FriendlyByteBuf buf) {
        return new ServerboundPlaybackActionPacket(buf.readBlockPos(), buf.readEnum(Action.class),
            buf.readUtf(), buf.readDouble());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 64) return;
            if (!(player.level().getBlockEntity(pos) instanceof PlaybackDeckBlockEntity deck)) return;

            String error = switch (action) {
                case PLAY -> deck.play(null);
                case SELECT -> deck.play(name.isEmpty() ? null : name);
                case PAUSE -> deck.pause();
                case STOP -> deck.stop();
                case REWIND -> deck.rewind();
                case FAST_FORWARD -> deck.fastForward();
                case SEEK -> deck.seek(seconds);
                case EJECT -> eject(deck, player);
                case REFRESH -> null;
            };
            PacketHandler.sendTo(player, new ClientboundPlaybackStatusPacket(deck.status(), error == null ? "" : error));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String eject(PlaybackDeckBlockEntity deck, ServerPlayer player) {
        if (!deck.hasTape()) return "No tape in the deck";
        player.getInventory().placeItemBackInInventory(deck.ejectTape());
        return null;
    }
}
