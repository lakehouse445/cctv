package gg.lakehouse.cctv.camera;

import gg.lakehouse.cctv.network.ClientboundOpenCameraScreenPacket;
import gg.lakehouse.cctv.network.PacketHandler;
import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Security camera: mounts on walls, floors and ceilings. The base and arm are
 * static; the head pans and tilts (rendered by the block entity renderer from
 * the camera's yaw/pitch). FACING is the at-rest lens direction; ACTIVE
 * lights the recording lamp while something watches the feed.
 */
public class CameraBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private static final VoxelShape SHAPE_FLOOR = Block.box(3, 0, 3, 13, 13, 13);
    private static final VoxelShape SHAPE_CEILING = Block.box(3, 3, 3, 13, 16, 13);
    private static final VoxelShape SHAPE_WALL_NORTH = Block.box(3, 3, 3, 13, 13, 16);
    private static final VoxelShape SHAPE_WALL_SOUTH = Block.box(3, 3, 0, 13, 13, 13);
    private static final VoxelShape SHAPE_WALL_EAST = Block.box(0, 3, 3, 13, 13, 13);
    private static final VoxelShape SHAPE_WALL_WEST = Block.box(3, 3, 3, 16, 13, 13);

    public CameraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(FACE, AttachFace.WALL)
            .setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FACE, ACTIVE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var clicked = context.getClickedFace();
        if (clicked == Direction.UP) {
            return defaultBlockState()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
        }
        if (clicked == Direction.DOWN) {
            return defaultBlockState()
                .setValue(FACE, AttachFace.CEILING)
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
        }
        return defaultBlockState()
            .setValue(FACE, AttachFace.WALL)
            .setValue(FACING, clicked);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> SHAPE_FLOOR;
            case CEILING -> SHAPE_CEILING;
            default -> switch (state.getValue(FACING)) {
                case SOUTH -> SHAPE_WALL_SOUTH;
                case EAST -> SHAPE_WALL_EAST;
                case WEST -> SHAPE_WALL_WEST;
                default -> SHAPE_WALL_NORTH;
            };
        };
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CameraBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
            && level.getBlockEntity(pos) instanceof CameraBlockEntity camera) {
            if (camera.isLocked()) {
                serverPlayer.displayClientMessage(Component.literal("Camera is locked"), true);
            } else {
                PacketHandler.sendTo(serverPlayer,
                    new ClientboundOpenCameraScreenPacket(pos, camera.getYaw(), camera.getPitch(), (float) camera.getZoom()));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModRegistry.CAMERA_BLOCK_ENTITY.get()) return null;
        return (tickLevel, pos, tickState, blockEntity) -> ((CameraBlockEntity) blockEntity).serverTick();
    }
}
