package gg.lakehouse.cctv.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import gg.lakehouse.cctv.camera.CameraBlock;
import gg.lakehouse.cctv.link.DeviceLinkItem;
import gg.lakehouse.cctv.microphone.MicrophoneBlock;
import gg.lakehouse.cctv.network.ClientboundCameraLinksPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holding a Camera Link or Microphone Link shows the linking overlay through
 * walls: squares inside every device of the held item's kind (white when
 * selected) and wired modem, and the solid block snake each link rides
 * between them.
 */
public final class CameraLinkRenderer {
    private static volatile List<ClientboundCameraLinksPacket.Entry> links = List.of();
    private static final List<BlockPos> CAMERAS = new ArrayList<>();
    private static final List<BlockPos> MICROPHONES = new ArrayList<>();
    private static final List<BlockPos> MODEMS = new ArrayList<>();
    private static long lastScan = Long.MIN_VALUE;

    private CameraLinkRenderer() {
    }

    public static void accept(ClientboundCameraLinksPacket packet) {
        links = packet.entries();
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null) return;
        var held = player.getMainHandItem().getItem() instanceof DeviceLinkItem ? player.getMainHandItem()
            : player.getOffhandItem().getItem() instanceof DeviceLinkItem ? player.getOffhandItem() : null;
        if (held == null) return;
        var kind = ((DeviceLinkItem) held.getItem()).kind();

        if (level.getGameTime() != lastScan && level.getGameTime() % 20 == 0) {
            lastScan = level.getGameTime();
            scan(player.blockPosition());
        }

        long selected = held.getTag() != null && held.getTag().contains("LinkDevice")
            ? held.getTag().getLong("LinkDevice") : Long.MIN_VALUE;

        var cameraPos = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        var matrix = pose.last().pose();
        pose.popPose();

        var tesselator = Tesselator.getInstance();
        var builder = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Depth testing is off, so draw order is the layering:
        // insulation first, couplings above it, markers on top.
        int cr = 46;
        int cg = 44;
        int cb = 54;
        for (var entry : links) {
            var path = entry.path();
            var camera = BlockPos.of(entry.camera());
            var modem = BlockPos.of(entry.modem());
            if (path.length == 0) {
                segment(builder, matrix, camera, modem, 0.09f, cr, cg, cb, 255);
                continue;
            }
            BlockPos previous = null;
            for (long value : path) {
                var pos = BlockPos.of(value);
                if (previous != null) segment(builder, matrix, previous, pos, 0.09f, cr, cg, cb, 255);
                previous = pos;
            }
            var first = BlockPos.of(path[0]);
            var last = BlockPos.of(path[path.length - 1]);
            if (camera.distSqr(first) <= camera.distSqr(last)) {
                segment(builder, matrix, camera, first, 0.09f, cr, cg, cb, 255);
                segment(builder, matrix, modem, last, 0.09f, cr, cg, cb, 255);
            } else {
                segment(builder, matrix, camera, last, 0.09f, cr, cg, cb, 255);
                segment(builder, matrix, modem, first, 0.09f, cr, cg, cb, 255);
            }
        }
        for (var entry : links) {
            for (long value : entry.path()) {
                box(builder, matrix, BlockPos.of(value), 0.115f, 130, 95, 200, 255);
            }
        }
        var devices = kind == DeviceLinkItem.Kind.MICROPHONE ? MICROPHONES : CAMERAS;
        for (var pos : devices) {
            boolean isSelected = pos.asLong() == selected;
            if (kind == DeviceLinkItem.Kind.MICROPHONE) {
                box(builder, matrix, pos, 0.18f,
                    isSelected ? 255 : 110, 255, isSelected ? 255 : 130, 255);
            } else {
                box(builder, matrix, pos, 0.18f,
                    isSelected ? 255 : 80, isSelected ? 255 : 220, 255, 255);
            }
        }
        for (var pos : MODEMS) {
            box(builder, matrix, pos, 0.18f, 255, 170, 40, 255);
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    /**
     * The naive version read 41^3 = 68921 block states plus a reverse
     * registry lookup each, once a second, on the render thread - a visible
     * hitch. This walks chunk sections instead: an all-air section skips
     * wholesale (most of the cube, in any normal world), air blocks skip
     * before any classification, and the wired-modem registry lookup
     * memoizes per Block instance.
     */
    private static void scan(BlockPos center) {
        CAMERAS.clear();
        MICROPHONES.clear();
        MODEMS.clear();
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        int radius = 20;
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
                if (chunk == null) continue;
                int x0 = Math.max(minX, chunkX << 4);
                int x1 = Math.min(maxX, (chunkX << 4) + 15);
                int z0 = Math.max(minZ, chunkZ << 4);
                int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    var section = chunk.getSection(index);
                    if (section.hasOnlyAir()) continue;
                    int y0 = Math.max(minY, sectionY << 4);
                    int y1 = Math.min(maxY, (sectionY << 4) + 15);
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                var state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (state.isAir()) continue;
                                var block = state.getBlock();
                                if (block instanceof CameraBlock) {
                                    CAMERAS.add(new BlockPos(x, y, z));
                                } else if (block instanceof MicrophoneBlock) {
                                    MICROPHONES.add(new BlockPos(x, y, z));
                                } else if (isWiredModem(block)) {
                                    MODEMS.add(new BlockPos(x, y, z));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Reverse registry lookups are not free; each Block classifies once. */
    private static final Map<Block, Boolean> MODEM_BLOCKS = new HashMap<>();

    private static boolean isWiredModem(Block block) {
        return MODEM_BLOCKS.computeIfAbsent(block, b -> {
            var name = ForgeRegistries.BLOCKS.getKey(b);
            return name != null && "computercraft".equals(name.getNamespace())
                && name.getPath().contains("wired_modem");
        });
    }

    private static void box(com.mojang.blaze3d.vertex.BufferBuilder builder, org.joml.Matrix4f matrix,
                            BlockPos pos, float half, int r, int g, int b, int a) {
        float cx = pos.getX() + 0.5f;
        float cy = pos.getY() + 0.5f;
        float cz = pos.getZ() + 0.5f;
        prism(builder, matrix, cx - half, cy - half, cz - half, cx + half, cy + half, cz + half, r, g, b, a);
    }

    /** A continuous run between two block centers: one prism spanning both, joints overlapping. */
    private static void segment(com.mojang.blaze3d.vertex.BufferBuilder builder, org.joml.Matrix4f matrix,
                                BlockPos from, BlockPos to, float half, int r, int g, int b, int a) {
        prism(builder, matrix,
            Math.min(from.getX(), to.getX()) + 0.5f - half,
            Math.min(from.getY(), to.getY()) + 0.5f - half,
            Math.min(from.getZ(), to.getZ()) + 0.5f - half,
            Math.max(from.getX(), to.getX()) + 0.5f + half,
            Math.max(from.getY(), to.getY()) + 0.5f + half,
            Math.max(from.getZ(), to.getZ()) + 0.5f + half,
            r, g, b, a);
    }

    private static void prism(com.mojang.blaze3d.vertex.BufferBuilder builder, org.joml.Matrix4f matrix,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        quad(builder, matrix, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, r, g, b, a);
        quad(builder, matrix, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
        quad(builder, matrix, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, r, g, b, a);
        quad(builder, matrix, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        quad(builder, matrix, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quad(builder, matrix, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2, r, g, b, a);
    }

    private static void quad(com.mojang.blaze3d.vertex.BufferBuilder builder, org.joml.Matrix4f matrix,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             int r, int g, int b, int a) {
        builder.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x3, y3, z3).color(r, g, b, a).endVertex();
        builder.vertex(matrix, x4, y4, z4).color(r, g, b, a).endVertex();
    }
}
