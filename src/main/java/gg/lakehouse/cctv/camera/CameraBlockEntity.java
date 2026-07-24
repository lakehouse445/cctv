package gg.lakehouse.cctv.camera;

import gg.lakehouse.cctv.camera.client.ClientCameraAppearances;
import gg.lakehouse.cctv.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * A security camera. Renders 16-color frames of the world server-side on
 * demand (cached so several watchers share one render), and runs a slow
 * low-res render in the background to raise camera_motion events while a
 * computer is attached. Pan/tilt/zoom are offsets from the mounted facing.
 */
public class CameraBlockEntity extends BlockEntity {
    public static final int MAX_WIDTH = 162;
    public static final int MAX_HEIGHT = 81;
    public static final int DEFAULT_WIDTH = 51;
    public static final int DEFAULT_HEIGHT = 19;
    public static final float MAX_YAW = 60;
    public static final float MAX_PITCH = 45;
    public static final double MAX_ZOOM = 10;

    /** Frames are cached this many ticks, capping renders at 10 fps per camera. */
    private static final int FRAME_CACHE_TICKS = 2;
    /** The red light stays on this long after the last frame request. */
    private static final int WATCHED_LINGER_TICKS = 100;
    private static final int MOTION_INTERVAL_TICKS = 10;
    private static final int MOTION_WIDTH = 32;
    private static final int MOTION_HEIGHT = 18;

    /** Real model/texture rendering needs client assets; null on dedicated servers (map-color fallback). */
    @Nullable
    private static final BlockAppearanceProvider BLOCK_APPEARANCE =
        DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientCameraAppearances::blockProvider);
    @Nullable
    private static final EntityAppearanceProvider ENTITY_APPEARANCE =
        DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientCameraAppearances::entityProvider);

    private final CameraPeripheral peripheral = new CameraPeripheral(this);
    private final CameraFrameEncoder.Exposure exposure = new CameraFrameEncoder.Exposure();
    private float yaw;
    private float pitch;
    private double zoom = 1;
    private boolean locked;
    private double motionThreshold = 0.05;
    private ColorMode colorMode = ColorMode.BW;

    private CameraFrameEncoder.EncodedFrame cachedFrame;
    private long cachedTime = Long.MIN_VALUE;
    private long lastWatchedTime = Long.MIN_VALUE;
    private String[] previousMotionLines;
    private List<String> previousPlayers = List.of();
    private long nextMotionTime;

    public CameraBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.CAMERA_BLOCK_ENTITY.get(), pos, state);
    }

    public CameraPeripheral peripheral() {
        return peripheral;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = Mth.clamp(yaw, -MAX_YAW, MAX_YAW);
        syncToClient();
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = Mth.clamp(pitch, -MAX_PITCH, MAX_PITCH);
        syncToClient();
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = Mth.clamp(zoom, 1, MAX_ZOOM);
        setChanged();
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        setChanged();
    }

    public ColorMode getColorMode() {
        return colorMode;
    }

    public void setColorMode(ColorMode mode) {
        if (colorMode == mode) return;
        colorMode = mode;
        cachedFrame = null;
        setChanged();
    }

    public double getMotionThreshold() {
        return motionThreshold;
    }

    public void setMotionThreshold(double threshold) {
        this.motionThreshold = Mth.clamp(threshold, 0.001, 1);
        setChanged();
    }

    /** @return the encoded frame, or null when this camera isn't in a loaded server level. */
    @Nullable
    public CameraFrameEncoder.EncodedFrame renderFrame(int width, int height) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        long now = serverLevel.getGameTime();
        lastWatchedTime = now;
        if (cachedFrame != null && cachedFrame.width() == width && cachedFrame.height() == height
            && now - cachedTime < FRAME_CACHE_TICKS) {
            return cachedFrame;
        }
        // A terminal cell shows 2x3 pixels through the drawing characters.
        cachedFrame = CameraFrameEncoder.encode(raycaster(serverLevel, width * 2, height * 3).render(), width, height, colorMode, exposure);
        cachedTime = now;
        return cachedFrame;
    }

    private CameraRaycaster raycaster(ServerLevel serverLevel, int pixelWidth, int pixelHeight) {
        float baseYaw = getBlockState().getValue(CameraBlock.FACING).toYRot();
        // Lua pitch is positive-up; Minecraft's is positive-down. Dedicated
        // servers use the baked server-side assets once they're ready.
        var blocks = BLOCK_APPEARANCE != null ? BLOCK_APPEARANCE
            : gg.lakehouse.cctv.camera.server.ServerCameraAssets.blocks();
        var entities = ENTITY_APPEARANCE != null ? ENTITY_APPEARANCE
            : gg.lakehouse.cctv.camera.server.ServerCameraAssets.entities();
        return new CameraRaycaster(serverLevel, worldPosition, baseYaw + yaw, -pitch, zoom,
            pixelWidth, pixelHeight, blocks, entities);
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        long now = serverLevel.getGameTime();

        if (peripheral.hasComputers() && now >= nextMotionTime) {
            nextMotionTime = now + MOTION_INTERVAL_TICKS;
            var motionCaster = raycaster(serverLevel, MOTION_WIDTH, MOTION_HEIGHT);
            var lines = motionCaster.renderQuantizedLines();
            var players = motionCaster.visiblePlayers().stream().sorted().toList();
            if (!players.equals(previousPlayers)) {
                previousPlayers = players;
                peripheral.queuePlayerEvent(players);
            }
            if (previousMotionLines != null && !Arrays.equals(previousMotionLines, lines)) {
                int changed = 0;
                for (int row = 0; row < MOTION_HEIGHT; row++) {
                    var before = previousMotionLines[row];
                    var after = lines[row];
                    for (int col = 0; col < MOTION_WIDTH; col++) {
                        if (before.charAt(col) != after.charAt(col)) changed++;
                    }
                }
                double fraction = changed / (double) (MOTION_WIDTH * MOTION_HEIGHT);
                if (fraction >= motionThreshold) peripheral.queueMotionEvent(fraction);
            }
            previousMotionLines = lines;
        } else if (!peripheral.hasComputers()) {
            previousMotionLines = null;
            previousPlayers = List.of();
        }

        if (now % 20 == 0) {
            boolean watched = peripheral.hasComputers() || now - lastWatchedTime < WATCHED_LINGER_TICKS;
            var state = getBlockState();
            if (state.getValue(CameraBlock.ACTIVE) != watched) {
                serverLevel.setBlock(worldPosition, state.setValue(CameraBlock.ACTIVE, watched), 3);
            }
        }
    }

    /** The head pose renders client-side, so yaw/pitch changes push to watchers. */
    private void syncToClient() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        var tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putFloat("Yaw", yaw);
        tag.putFloat("Pitch", pitch);
        tag.putDouble("Zoom", zoom);
        tag.putBoolean("Locked", locked);
        tag.putDouble("MotionThreshold", motionThreshold);
        tag.putString("ColorMode", colorMode.getName());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        yaw = Mth.clamp(tag.getFloat("Yaw"), -MAX_YAW, MAX_YAW);
        pitch = Mth.clamp(tag.getFloat("Pitch"), -MAX_PITCH, MAX_PITCH);
        zoom = tag.contains("Zoom") ? Mth.clamp(tag.getDouble("Zoom"), 1, MAX_ZOOM) : 1;
        locked = tag.getBoolean("Locked");
        motionThreshold = tag.contains("MotionThreshold") ? Mth.clamp(tag.getDouble("MotionThreshold"), 0.001, 1) : 0.05;
        var mode = ColorMode.byName(tag.getString("ColorMode"));
        colorMode = mode != null ? mode : ColorMode.BW;
    }
}
