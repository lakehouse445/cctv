package gg.lakehouse.cctv.playback;

import gg.lakehouse.cctv.network.ClientboundOpenPlaybackScreenPacket;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.registry.ModRegistry;
import gg.lakehouse.cctv.tape.TapeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

public class PlaybackDeckBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final EnumProperty<DeckState> STATE = EnumProperty.create("state", DeckState.class);

    public PlaybackDeckBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(STATE, DeckState.EMPTY));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlaybackDeckBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide
            && player instanceof ServerPlayer serverPlayer
            && level.getBlockEntity(pos) instanceof PlaybackDeckBlockEntity deck) {
            var held = player.getItemInHand(hand);
            if (held.getItem() instanceof TapeItem && !deck.hasTape()) {
                deck.insertTape(held);
            } else if (player.isShiftKeyDown() && deck.hasTape()) {
                player.getInventory().placeItemBackInInventory(deck.ejectTape());
            } else {
                PacketHandler.sendTo(serverPlayer, new ClientboundOpenPlaybackScreenPacket(deck.status()));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof PlaybackDeckBlockEntity deck
            && deck.hasTape()) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), deck.ejectTape());
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModRegistry.PLAYBACK_DECK_BLOCK_ENTITY.get()) return null;
        return (tickLevel, pos, tickState, blockEntity) -> ((PlaybackDeckBlockEntity) blockEntity).serverTick();
    }
}
