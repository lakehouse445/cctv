package gg.lakehouse.cctv.camera;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side camera renderer: a real raycast against the world. With
 * appearance providers (client assets available), rays intersect the actual
 * baked model quads and sample the actual textures — including cutout
 * transparency and translucent tinting — and entities are their real models,
 * posed for this frame. Without providers (dedicated server), everything
 * falls back to map-color boxes. A terminal cell shows 2x3 pixels through the
 * teletext drawing characters; output is full-RGB and
 * {@link CameraFrameEncoder} handles the palette and dithering.
 */
final class CameraRaycaster {
    static final double MAX_DISTANCE = 128.0;
    private static final double FADE_START = MAX_DISTANCE * 0.75;
    private static final double BASE_FOV_DEGREES = 70.0;
    private static final int MAX_QUAD_HITS = 64;
    private static final int MAX_TRANSLUCENT_LAYERS = 4;

    /** CC:T's default 16-color palette, used only for the cheap motion-detection frames. */
    private static final int[] FIXED_PALETTE = {
        0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
        0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111
    };
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** Nearest-fixed-palette lookup keyed by RGB quantized to 4 bits per channel; 0 = unset, else char + 1. */
    private static final char[] QUANTIZE_CACHE = new char[4096];

    /** Body and head colors for the fallback figures; anything absent falls back by hostility. */
    private static final Map<EntityType<?>, int[]> ENTITY_COLORS = Map.ofEntries(
        Map.entry(EntityType.PLAYER, new int[]{0x3F76E4, 0xBD8B72}),
        Map.entry(EntityType.VILLAGER, new int[]{0x7F5C3F, 0xBD8B72}),
        Map.entry(EntityType.ZOMBIE, new int[]{0x3C6E8F, 0x5AA85A}),
        Map.entry(EntityType.SKELETON, new int[]{0xB8B8B8, 0xD0D0D0}),
        Map.entry(EntityType.CREEPER, new int[]{0x50A050, 0x58B058}),
        Map.entry(EntityType.SPIDER, new int[]{0x342A24, 0x453832}),
        Map.entry(EntityType.ENDERMAN, new int[]{0x161616, 0x161616}),
        Map.entry(EntityType.PIG, new int[]{0xF0A0A0, 0xF0A0A0}),
        Map.entry(EntityType.COW, new int[]{0x6B4A33, 0x8A6A50}),
        Map.entry(EntityType.SHEEP, new int[]{0xE8E8E8, 0xD8C8B8}),
        Map.entry(EntityType.CHICKEN, new int[]{0xF0F0F0, 0xE0D8C8}),
        Map.entry(EntityType.WOLF, new int[]{0xB8B0A8, 0xC0B8B0}),
        Map.entry(EntityType.CAT, new int[]{0xC89058, 0xC89058}),
        Map.entry(EntityType.HORSE, new int[]{0x8A6A45, 0x7A5A38}),
        Map.entry(EntityType.IRON_GOLEM, new int[]{0xC8C0B8, 0xB8B0A8})
    );
    private static final int[] HOSTILE_COLORS = {0xAA3C3C, 0xC05050};
    private static final int[] NEUTRAL_COLORS = {0x999999, 0xAAAAAA};

    /** A rendered frame: packed 0xRRGGBB per pixel, row-major. */
    record PixelFrame(int width, int height, int[] pixels) {
    }

    private final ServerLevel level;
    private final BlockPos cameraPos;
    private final Vec3 origin;
    private final Vec3 forward;
    private final Vec3 right;
    private final Vec3 up;
    private final double tanHalfH;
    private final double tanHalfV;
    /** World units one pixel spans per unit of distance; times hit distance and texel density = mip choice. */
    private final double pixelFootprint;
    private final int width;
    private final int height;
    private final int skyDarken;
    private final int zenithColor;
    private final int horizonColor;
    @Nullable
    private final BlockAppearanceProvider blockAppearance;
    @Nullable
    private final EntityAppearanceProvider entityAppearance;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lightCache =
        new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /** Per-frame cache of position-dependent geometry (chest lids, sign text). */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<TexturedQuad>> dynamicCache =
        new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE;
    private int chunkZ = Integer.MIN_VALUE;
    private LevelChunkSection section;
    private int sectionX = Integer.MIN_VALUE;
    private int sectionY = Integer.MIN_VALUE;
    private int sectionZ = Integer.MIN_VALUE;
    private boolean chunkMissing;
    private int boxHitAxis;

    /** Players that actually painted pixels in the last render — ground truth for camera_player events. */
    private final List<String> visiblePlayers = new ArrayList<>();

    private final double[] hitT = new double[MAX_QUAD_HITS];
    private final double[] hitU = new double[MAX_QUAD_HITS];
    private final double[] hitV = new double[MAX_QUAD_HITS];
    private final TexturedQuad[] hitQuads = new TexturedQuad[MAX_QUAD_HITS];
    private final BlockState[] hitStates = new BlockState[MAX_QUAD_HITS];
    private final long[] hitOwners = new long[MAX_QUAD_HITS];
    private final double[] uvA = new double[2];
    private final double[] uvB = new double[2];

    private static final long[] NO_SPILL = new long[0];
    private static final net.minecraft.core.Direction[] SIDES = net.minecraft.core.Direction.values();
    /** Sorted keys of cells bordering geometry that overhangs its block (rotated signs, banners). */
    private final long[] spillNeighborhood;
    private final Map<BlockState, Boolean> spillFlags = new HashMap<>();
    private final BlockPos.MutableBlockPos sourcePos = new BlockPos.MutableBlockPos();

    CameraRaycaster(ServerLevel level, BlockPos cameraPos, float yawDegrees, float pitchDegrees, double zoom,
                    int pixelWidth, int pixelHeight,
                    @Nullable BlockAppearanceProvider blockAppearance,
                    @Nullable EntityAppearanceProvider entityAppearance) {
        this.level = level;
        this.cameraPos = cameraPos;
        this.origin = Vec3.atCenterOf(cameraPos);
        this.width = pixelWidth;
        this.height = pixelHeight;
        this.blockAppearance = blockAppearance;
        this.entityAppearance = entityAppearance;

        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        forward = new Vec3(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
        right = forward.cross(new Vec3(0, 1, 0)).normalize();
        up = right.cross(forward).normalize();

        double horizontalFov = Math.toRadians(BASE_FOV_DEGREES / Math.max(1, zoom));
        tanHalfH = Math.tan(horizontalFov / 2);
        tanHalfV = tanHalfH * height / (double) width;
        pixelFootprint = 2 * tanHalfH / width;

        skyDarken = level.getSkyDarken();
        double day = 1 - skyDarken / 11.0;
        zenithColor = pack(
            (int) Mth.lerp(day, 0x06, 0x5A),
            (int) Mth.lerp(day, 0x08, 0x8F),
            (int) Mth.lerp(day, 0x0F, 0xE8));
        horizonColor = pack(
            (int) Mth.lerp(day, 0x11, 0xB8),
            (int) Mth.lerp(day, 0x18, 0xD0),
            (int) Mth.lerp(day, 0x2A, 0xF5));

        spillNeighborhood = blockAppearance == null ? NO_SPILL : collectSpillNeighborhood();
    }

    /**
     * Cells bordering blocks whose emitted geometry overhangs the block:
     * a rotated sign board reaches ~0.2 into its neighbors, and the march
     * only tests a block's quads in its own cell. Spilling blocks are all
     * block entities today, so loaded chunk block-entity maps enumerate
     * every candidate in range.
     */
    private long[] collectSpillNeighborhood() {
        var cells = new ArrayList<Long>();
        int chunkRadius = (int) (MAX_DISTANCE / 16) + 1;
        int baseX = cameraPos.getX() >> 4;
        int baseZ = cameraPos.getZ() >> 4;
        for (int cx = baseX - chunkRadius; cx <= baseX + chunkRadius; cx++) {
            for (int cz = baseZ - chunkRadius; cz <= baseZ + chunkRadius; cz++) {
                var loaded = level.getChunkSource().getChunkNow(cx, cz);
                if (loaded == null) continue;
                for (var at : loaded.getBlockEntities().keySet()) {
                    if (!spills(loaded.getBlockState(at))) continue;
                    for (var side : SIDES) {
                        cells.add(BlockPos.asLong(at.getX() + side.getStepX(),
                            at.getY() + side.getStepY(), at.getZ() + side.getStepZ()));
                    }
                }
            }
        }
        if (cells.isEmpty()) return NO_SPILL;
        var table = new long[cells.size()];
        for (int i = 0; i < table.length; i++) table[i] = cells.get(i);
        java.util.Arrays.sort(table);
        return table;
    }

    /** True when any of the state's static quads reach outside the unit cell. */
    private boolean spills(BlockState state) {
        var cached = spillFlags.get(state);
        if (cached != null) return cached;
        boolean result = false;
        for (var quad : blockAppearance.quads(state)) {
            for (int i = 0; i < 4 && !result; i++) {
                result = quad.xs()[i] < -0.001f || quad.xs()[i] > 1.001f
                    || quad.ys()[i] < -0.001f || quad.ys()[i] > 1.001f
                    || quad.zs()[i] < -0.001f || quad.zs()[i] > 1.001f;
            }
            if (result) break;
        }
        spillFlags.put(state, result);
        return result;
    }

    PixelFrame render() {
        var pixels = new int[width * height];
        var depth = new double[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                pixels[index] = castRay(rayDirection(col, row), depth, index);
            }
        }
        paintEntities(pixels, depth);
        return new PixelFrame(width, height, pixels);
    }

    /** Player names with surviving pixels in the last render, nearest first. */
    List<String> visiblePlayers() {
        return visiblePlayers;
    }

    /** Cheap fixed-palette render for motion detection: one hex color character per pixel. */
    String[] renderQuantizedLines() {
        var frame = render();
        var lines = new String[height];
        var row = new char[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) row[x] = quantizeFixed(frame.pixels()[y * width + x]);
            lines[y] = new String(row);
        }
        return lines;
    }

    private Vec3 rayDirection(int col, int row) {
        double ndcX = (col + 0.5) / width * 2 - 1;
        double ndcY = 1 - (row + 0.5) / height * 2;
        return forward
            .add(right.scale(ndcX * tanHalfH))
            .add(up.scale(ndcY * tanHalfV))
            .normalize();
    }

    private int castRay(Vec3 direction, double[] depth, int index) {
        depth[index] = MAX_DISTANCE;
        int x = cameraPos.getX();
        int y = cameraPos.getY();
        int z = cameraPos.getZ();
        int stepX = direction.x > 0 ? 1 : -1;
        int stepY = direction.y > 0 ? 1 : -1;
        int stepZ = direction.z > 0 ? 1 : -1;
        double tDeltaX = direction.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.x);
        double tDeltaY = direction.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.y);
        double tDeltaZ = direction.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / direction.z);
        double tMaxX = direction.x == 0 ? Double.POSITIVE_INFINITY
            : (stepX > 0 ? x + 1 - origin.x : origin.x - x) * tDeltaX;
        double tMaxY = direction.y == 0 ? Double.POSITIVE_INFINITY
            : (stepY > 0 ? y + 1 - origin.y : origin.y - y) * tDeltaY;
        double tMaxZ = direction.z == 0 ? Double.POSITIVE_INFINITY
            : (stepZ > 0 ? z + 1 - origin.z : origin.z - z) * tDeltaZ;

        double tintR = 1;
        double tintG = 1;
        double tintB = 1;
        int translucentLayers = 0;

        var pos = new BlockPos.MutableBlockPos();
        while (true) {
            int axis;
            double t;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                axis = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                axis = 1;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                axis = 2;
            }
            if (t > MAX_DISTANCE) return applyTint(skyColor(direction), tintR, tintG, tintB);
            if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                return applyTint(skyColor(direction), tintR, tintG, tintB);
            }

            var state = blockStateAt(x, y, z);
            if (chunkMissing) return applyTint(skyColor(direction), tintR, tintG, tintB);
            boolean air = state == null || state.isAir();
            boolean nearSpill = spillNeighborhood.length > 0
                && java.util.Arrays.binarySearch(spillNeighborhood, BlockPos.asLong(x, y, z)) >= 0;
            if (air && !nearSpill) continue;
            pos.set(x, y, z);

            if (blockAppearance == null) {
                var mapColor = state.getMapColor(level, pos);
                if (mapColor == MapColor.NONE) continue;
                if (!state.canOcclude() && state.getFluidState().isEmpty()
                    && state.getCollisionShape(level, pos).isEmpty()) {
                    continue;
                }
                depth[index] = t;
                double shade = litFactor(x, y, z, axis, stepX, stepY, stepZ)
                    * faceFactor(axis, direction);
                return applyTint(fadeToHorizon(shadeColor(mapColor.col, shade), t), tintR, tintG, tintB);
            }

            // No INVISIBLE-shape skip here: signs, banners and skulls report
            // INVISIBLE (they're renderer-drawn) yet our providers know their
            // geometry. Blocks with truly nothing yield empty quads below.
            int hits = 0;
            if (!air) hits = gatherHits(state, x, y, z, x, y, z, false, direction, hits);
            if (nearSpill) {
                // A neighbor's overhang reaches into this cell. Test it,
                // keeping only hits inside this cell, so geometry the ray
                // has not reached yet still occludes by march order.
                boolean missing = chunkMissing;
                for (var side : SIDES) {
                    int nx = x + side.getStepX();
                    int ny = y + side.getStepY();
                    int nz = z + side.getStepZ();
                    var neighbor = blockStateAt(nx, ny, nz);
                    if (neighbor == null || neighbor.isAir() || !spills(neighbor)) continue;
                    hits = gatherHits(neighbor, nx, ny, nz, x, y, z, true, direction, hits);
                }
                chunkMissing = missing;
            }
            if (hits == 0) continue;

            for (int hit = 0; hit < hits; hit++) {
                var quad = hitQuads[hit];
                int texel = quad.texture().sample(hitU[hit], hitV[hit],
                    hitT[hit] * pixelFootprint * quad.texelDensity());
                int alpha = quad.alphaOverride() >= 0 ? quad.alphaOverride() : (texel >>> 24);
                // Cutout textures (no partial alpha at base level) resolve
                // opaque above vanilla's cutout_mipped threshold: partial
                // alpha here is only mip softening, and the translucent
                // path would let atlas corners bordering transparent
                // padding fall through at a distance.
                boolean cutout = quad.alphaOverride() < 0 && quad.texture().cutout();
                if (alpha < (cutout ? 26 : 32)) continue;
                int rgb = texel & 0xFFFFFF;
                if (quad.colorMul() != 0xFFFFFF) rgb = mulColor(rgb, quad.colorMul());
                if (quad.tintIndex() != TexturedQuad.TINT_NONE) {
                    rgb = mulColor(rgb, blockAppearance.tint(hitStates[hit], level,
                        BlockPos.of(hitOwners[hit]), quad.tintIndex()));
                }
                if (!cutout && alpha < 224 && translucentLayers < MAX_TRANSLUCENT_LAYERS) {
                    double a = alpha / 255.0;
                    tintR *= (1 - a) + a * ((rgb >> 16) & 0xFF) / 255.0;
                    tintG *= (1 - a) + a * ((rgb >> 8) & 0xFF) / 255.0;
                    tintB *= (1 - a) + a * (rgb & 0xFF) / 255.0;
                    translucentLayers++;
                    continue;
                }
                depth[index] = hitT[hit];
                double shade = litFactor(x, y, z, axis, stepX, stepY, stepZ)
                    * faceFactorFromNormal(quad.nx(), quad.ny(), quad.nz());
                int color = fadeToHorizon(shadeColor(rgb, shade), hitT[hit]);
                return applyTint(color, tintR, tintG, tintB);
            }
        }
    }

    /**
     * Intersects one block's merged static and dynamic quads along the ray,
     * keeping the nearest hits sorted in the shared hit arrays. When
     * {@code bounded}, only hits whose point lies inside the marching cell
     * are kept: overhanging neighbor geometry must not paint through cells
     * the ray has not marched yet.
     */
    private int gatherHits(BlockState state, int sourceX, int sourceY, int sourceZ,
                           int cellX, int cellY, int cellZ, boolean bounded,
                           Vec3 direction, int hits) {
        var quads = blockAppearance.quads(state);
        var dynamic = dynamicQuadsAt(state, sourceX, sourceY, sourceZ);
        if (!dynamic.isEmpty()) {
            if (quads.isEmpty()) {
                quads = dynamic;
            } else {
                var merged = new ArrayList<TexturedQuad>(quads.size() + dynamic.size());
                merged.addAll(quads);
                merged.addAll(dynamic);
                quads = merged;
            }
        }
        if (quads.isEmpty()) return hits;
        sourcePos.set(sourceX, sourceY, sourceZ);
        var offset = blockAppearance.offset(state, level, sourcePos);
        double localX = origin.x - sourceX - offset.x;
        double localY = origin.y - sourceY - offset.y;
        double localZ = origin.z - sourceZ - offset.z;
        long source = BlockPos.asLong(sourceX, sourceY, sourceZ);

        int quadCount = quads.size();
        for (int qi = 0; qi < quadCount; qi++) {
            var quad = quads.get(qi);
            double tq = intersectQuad(quad, localX, localY, localZ,
                direction.x, direction.y, direction.z, uvA);
            if (tq < 0 || tq > MAX_DISTANCE) continue;
            if (bounded) {
                double hx = origin.x + direction.x * tq;
                double hy = origin.y + direction.y * tq;
                double hz = origin.z + direction.z * tq;
                if (hx < cellX - 1e-4 || hx > cellX + 1 + 1e-4
                    || hy < cellY - 1e-4 || hy > cellY + 1 + 1e-4
                    || hz < cellZ - 1e-4 || hz > cellZ + 1 + 1e-4) {
                    continue;
                }
            }
            // Every quad is tested; a full hit list drops its farthest
            // entry, never a nearer one, so geometry-heavy cells cannot
            // clip front faces.
            if (hits == MAX_QUAD_HITS) {
                if (tq >= hitT[MAX_QUAD_HITS - 1]) continue;
                hits--;
            }
            int insert = hits;
            while (insert > 0 && hitT[insert - 1] > tq) {
                hitT[insert] = hitT[insert - 1];
                hitU[insert] = hitU[insert - 1];
                hitV[insert] = hitV[insert - 1];
                hitQuads[insert] = hitQuads[insert - 1];
                hitStates[insert] = hitStates[insert - 1];
                hitOwners[insert] = hitOwners[insert - 1];
                insert--;
            }
            hitT[insert] = tq;
            hitU[insert] = uvA[0];
            hitV[insert] = uvA[1];
            hitQuads[insert] = quad;
            hitStates[insert] = state;
            hitOwners[insert] = source;
            hits++;
        }
        return hits;
    }

    /** Rate limit for appearance-failure logging on per-frame paths. */
    private static long lastAppearanceError;

    private static void logAppearanceError(String what, Object subject, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastAppearanceError < 10_000) return;
        lastAppearanceError = now;
        gg.lakehouse.cctv.CCTV.LOGGER.warn("Camera {} failed for {}; drawing nothing there", what, subject, e);
    }

    private List<TexturedQuad> dynamicQuadsAt(BlockState state, int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        var cached = dynamicCache.get(key);
        if (cached == null) {
            // A block entity in a strange state (a minecart-displayed shulker,
            // a modded BE) must cost its own pixels, never the level tick.
            try {
                cached = blockAppearance.dynamicQuads(state, level, new BlockPos(x, y, z));
            } catch (Exception e) {
                logAppearanceError("dynamic geometry", state, e);
                cached = List.of();
            }
            dynamicCache.put(key, cached);
        }
        return cached;
    }

    /** Light sampled in the cell the ray entered the face from: its exposed side. */
    private double litFactor(int x, int y, int z, int axis, int stepX, int stepY, int stepZ) {
        return switch (axis) {
            case 0 -> lightFactor(x - stepX, y, z);
            case 1 -> lightFactor(x, y - stepY, z);
            default -> lightFactor(x, y, z - stepZ);
        };
    }

    private double lightFactor(BlockPos pos) {
        return lightFactor(pos.getX(), pos.getY(), pos.getZ());
    }

    private double lightFactor(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int light = lightCache.getOrDefault(key, -1);
        if (light < 0) {
            light = level.getRawBrightness(new BlockPos(x, y, z), skyDarken);
            lightCache.put(key, light);
        }
        return 0.25 + 0.75 * light / 15.0;
    }

    /** Vanilla's per-face diffuse: up 1.0, down 0.5, north/south 0.8, east/west 0.6. */
    private static double faceFactor(int axis, Vec3 direction) {
        return switch (axis) {
            case 1 -> direction.y < 0 ? 1.0 : 0.5;
            case 0 -> 0.6;
            default -> 0.8;
        };
    }

    private static double faceFactorFromNormal(float nx, float ny, float nz) {
        double ax = Math.abs(nx);
        double ay = Math.abs(ny);
        double az = Math.abs(nz);
        double sum = ax + ay + az;
        if (sum < 1e-6) return 0.8;
        double vertical = ny > 0 ? 1.0 : 0.5;
        return (ax * 0.6 + ay * vertical + az * 0.8) / sum;
    }

    private static int shadeColor(int base, double shade) {
        return pack(
            (int) (((base >> 16) & 0xFF) * shade),
            (int) (((base >> 8) & 0xFF) * shade),
            (int) ((base & 0xFF) * shade));
    }

    private static int mulColor(int a, int b) {
        return pack(
            ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255,
            ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255,
            (a & 0xFF) * (b & 0xFF) / 255);
    }

    private static int applyTint(int color, double r, double g, double b) {
        if (r > 0.999 && g > 0.999 && b > 0.999) return color;
        return pack(
            (int) (((color >> 16) & 0xFF) * r),
            (int) (((color >> 8) & 0xFF) * g),
            (int) ((color & 0xFF) * b));
    }

    private int fadeToHorizon(int color, double t) {
        if (t <= FADE_START) return color;
        double fade = (t - FADE_START) / (MAX_DISTANCE - FADE_START) * 0.85;
        return pack(
            (int) Mth.lerp(fade, (color >> 16) & 0xFF, (horizonColor >> 16) & 0xFF),
            (int) Mth.lerp(fade, (color >> 8) & 0xFF, (horizonColor >> 8) & 0xFF),
            (int) Mth.lerp(fade, color & 0xFF, horizonColor & 0xFF));
    }

    private int skyColor(Vec3 direction) {
        double t = Mth.clamp((direction.y + 0.1) / 0.9, 0, 1);
        return pack(
            (int) Mth.lerp(t, (horizonColor >> 16) & 0xFF, (zenithColor >> 16) & 0xFF),
            (int) Mth.lerp(t, (horizonColor >> 8) & 0xFF, (zenithColor >> 8) & 0xFF),
            (int) Mth.lerp(t, horizonColor & 0xFF, zenithColor & 0xFF));
    }

    // === Quad intersection ===

    /** Ray vs quad (two triangles); returns entry distance or -1, texture coords in uvOut. */
    private double intersectQuad(TexturedQuad quad, double ox, double oy, double oz,
                                 double dx, double dy, double dz, double[] uvOut) {
        double tA = intersectTriangle(quad, 0, 1, 2, ox, oy, oz, dx, dy, dz, uvOut);
        double tB = intersectTriangle(quad, 0, 2, 3, ox, oy, oz, dx, dy, dz, uvB);
        if (tA >= 0 && (tB < 0 || tA <= tB)) return tA;
        if (tB >= 0) {
            uvOut[0] = uvB[0];
            uvOut[1] = uvB[1];
            return tB;
        }
        return -1;
    }

    private static double intersectTriangle(TexturedQuad quad, int a, int b, int c,
                                            double ox, double oy, double oz,
                                            double dx, double dy, double dz, double[] uvOut) {
        double ax = quad.xs()[a];
        double ay = quad.ys()[a];
        double az = quad.zs()[a];
        double e1x = quad.xs()[b] - ax;
        double e1y = quad.ys()[b] - ay;
        double e1z = quad.zs()[b] - az;
        double e2x = quad.xs()[c] - ax;
        double e2y = quad.ys()[c] - ay;
        double e2z = quad.zs()[c] - az;
        double px = dy * e2z - dz * e2y;
        double py = dz * e2x - dx * e2z;
        double pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-9) return -1;
        double inv = 1 / det;
        double tx = ox - ax;
        double ty = oy - ay;
        double tz = oz - az;
        double u = (tx * px + ty * py + tz * pz) * inv;
        if (u < -0.001 || u > 1.001) return -1;
        double qx = ty * e1z - tz * e1y;
        double qy = tz * e1x - tx * e1z;
        double qz = tx * e1y - ty * e1x;
        double v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < -0.001 || u + v > 1.001) return -1;
        double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        if (t < 1e-4) return -1;
        uvOut[0] = quad.us()[a] + u * (quad.us()[b] - quad.us()[a]) + v * (quad.us()[c] - quad.us()[a]);
        uvOut[1] = quad.vs()[a] + u * (quad.vs()[b] - quad.vs()[a]) + v * (quad.vs()[c] - quad.vs()[a]);
        return t;
    }

    // === Entities ===

    private record Nametag(String text, Vec3 anchor) {
    }

    private void paintEntities(int[] pixels, double[] depth) {
        var searchBox = new AABB(cameraPos).inflate(MAX_DISTANCE);
        var entities = level.getEntities((Entity) null, searchBox,
            entity -> (entity instanceof LivingEntity living && living.isAlive()
                && !living.isInvisible() && !entity.isSpectator())
                || entity instanceof net.minecraft.world.entity.vehicle.Boat
                || entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                || entity instanceof net.minecraft.world.entity.decoration.Painting
                || entity instanceof net.minecraft.world.entity.decoration.ItemFrame
                || entity instanceof net.minecraft.world.entity.item.ItemEntity
                || entity instanceof net.minecraft.world.entity.ExperienceOrb
                || entity instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity
                || entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal
                || entity instanceof net.minecraft.world.entity.projectile.Projectile);
        // Near entities paint first and claim depth, so a fully covered
        // entity fails the depth test everywhere and painted stays false —
        // without this, a nearer mob painted later could bury a player's
        // pixels while the player still reported visible.
        entities.sort(java.util.Comparator.comparingDouble(
            entity -> entity.getBoundingBox().getCenter().subtract(origin).dot(forward)));
        var nametags = new ArrayList<Nametag>();
        visiblePlayers.clear();
        for (var entity : entities) {
            var bounds = entity.getBoundingBox();
            double along = bounds.getCenter().subtract(origin).dot(forward);
            if (along < 1.0 || along > MAX_DISTANCE) continue;
            double light = lightFactor(BlockPos.containing(bounds.getCenter()));

            List<TexturedQuad> modelQuads = null;
            if (entityAppearance != null) {
                try {
                    modelQuads = entityAppearance.capture(entity);
                } catch (Exception e) {
                    logAppearanceError("entity capture", entity.getType(), e);
                }
            }
            boolean painted;
            if (modelQuads != null && !modelQuads.isEmpty()) {
                painted = paintEntityModel(pixels, depth, entity, modelQuads, light);
            } else {
                painted = paintEntityBox(pixels, depth, entity, bounds, light);
            }
            if (painted && entity instanceof Player player) {
                visiblePlayers.add(player.getGameProfile().getName());
            }

            String name = null;
            if (entity instanceof Player player && !player.isDiscrete()) {
                name = player.getGameProfile().getName();
            } else if (entity.hasCustomName()) {
                name = entity.getCustomName().getString();
            }
            if (name != null && along <= 64) {
                nametags.add(new Nametag(name, new Vec3(
                    (bounds.minX + bounds.maxX) / 2, bounds.maxY + 0.4, (bounds.minZ + bounds.maxZ) / 2)));
            }
        }
        // Tags don't write depth, so overlap resolves by paint order alone.
        // The entity sort built this list nearest-first: paint it backwards
        // so the nearest tag lands last, on top.
        for (int i = nametags.size() - 1; i >= 0; i--) paintNametag(pixels, depth, nametags.get(i));
    }

    // === Nametags ===

    static final String GLYPH_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.";
    /** 4x5 pixel glyphs, five 4-char rows each, same order as GLYPH_CHARS. Shared with sign text. */
    static final String[] GLYPHS = {
        ".##." + "#..#" + "####" + "#..#" + "#..#",
        "###." + "#..#" + "###." + "#..#" + "###.",
        ".###" + "#..." + "#..." + "#..." + ".###",
        "###." + "#..#" + "#..#" + "#..#" + "###.",
        "####" + "#..." + "###." + "#..." + "####",
        "####" + "#..." + "###." + "#..." + "#...",
        ".###" + "#..." + "#.##" + "#..#" + ".###",
        "#..#" + "#..#" + "####" + "#..#" + "#..#",
        "###." + ".#.." + ".#.." + ".#.." + "###.",
        "..##" + "...#" + "...#" + "#..#" + ".##.",
        "#..#" + "#.#." + "##.." + "#.#." + "#..#",
        "#..." + "#..." + "#..." + "#..." + "####",
        "#..#" + "####" + "####" + "#..#" + "#..#",
        "#..#" + "##.#" + "#.##" + "#..#" + "#..#",
        ".##." + "#..#" + "#..#" + "#..#" + ".##.",
        "###." + "#..#" + "###." + "#..." + "#...",
        ".##." + "#..#" + "#..#" + "#.##" + ".###",
        "###." + "#..#" + "###." + "#.#." + "#..#",
        ".###" + "#..." + ".##." + "...#" + "###.",
        "####" + ".#.." + ".#.." + ".#.." + ".#..",
        "#..#" + "#..#" + "#..#" + "#..#" + ".##.",
        "#..#" + "#..#" + "#..#" + ".##." + ".#..",
        "#..#" + "#..#" + "####" + "####" + "#..#",
        "#..#" + ".##." + ".##." + "#..#" + "#..#",
        "#..#" + "#..#" + ".##." + ".#.." + ".#..",
        "####" + "..#." + ".#.." + "#..." + "####",
        ".##." + "#..#" + "#.##" + "##.#" + ".##.",
        ".#.." + "##.." + ".#.." + ".#.." + "###.",
        "###." + "...#" + ".##." + "#..." + "####",
        "###." + "...#" + ".##." + "...#" + "###.",
        "#..#" + "#..#" + "####" + "...#" + "...#",
        "####" + "#..." + "###." + "...#" + "###.",
        ".###" + "#..." + "###." + "#..#" + ".##.",
        "####" + "...#" + "..#." + ".#.." + ".#..",
        ".##." + "#..#" + ".##." + "#..#" + ".##.",
        ".##." + "#..#" + ".###" + "...#" + "###.",
        "...." + "...." + "...." + "...." + "####",
        "...." + "...." + "####" + "...." + "....",
        "...." + "...." + "...." + "...." + ".#.."
    };

    private void paintNametag(int[] pixels, double[] depth, Nametag nametag) {
        var v = nametag.anchor().subtract(origin);
        double along = v.dot(forward);
        if (along < 1) return;
        double sx = v.dot(right) / (along * tanHalfH);
        double sy = v.dot(up) / (along * tanHalfV);
        if (Math.abs(sx) > 1.3 || Math.abs(sy) > 1.3) return;
        int centerCol = (int) ((sx + 1) / 2 * width);
        int baseRow = (int) ((1 - sy) / 2 * height);
        double pixelsPerBlock = height / (2 * tanHalfV * along);
        double tagDistance = along - 0.2;

        var font = blockAppearance != null ? FontSheet.get(blockAppearance::texture) : FontSheet.get();
        if (font != null) {
            paintFontNametag(pixels, depth, font, nametag.text(), centerCol, baseRow, pixelsPerBlock, tagDistance);
        } else {
            paintLegacyNametag(pixels, depth, nametag.text(), centerCol, baseRow, pixelsPerBlock, tagDistance);
        }
    }

    private void paintFontNametag(int[] pixels, double[] depth, FontSheet font, String name,
                                  int centerCol, int baseRow, double pixelsPerBlock, double tagDistance) {
        int scale = Mth.clamp((int) Math.round(0.35 * pixelsPerBlock / FontSheet.HEIGHT), 1, 4);
        var text = name;
        while (text.length() > 1 && font.lineWidth(text) > 120) text = text.substring(0, text.length() - 1);
        int textWidth = font.lineWidth(text) * scale;
        int left = centerCol - textWidth / 2;
        int top = baseRow - FontSheet.HEIGHT * scale;

        for (int row = top - scale; row < baseRow + scale; row++) {
            for (int col = left - scale; col < left + textWidth + scale; col++) {
                if (row < 0 || row >= height || col < 0 || col >= width) continue;
                int index = row * width + col;
                if (depth[index] <= tagDistance) continue;
                pixels[index] = 0x101018;
            }
        }
        int pen = left;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int advance = font.advance(c);
            if (advance == 0) continue;
            for (int gy = 0; gy < FontSheet.HEIGHT; gy++) {
                for (int gx = 0; gx < advance - 1; gx++) {
                    if (!font.isSet(c, gx, gy)) continue;
                    for (int py = 0; py < scale; py++) {
                        for (int px = 0; px < scale; px++) {
                            int row = top + gy * scale + py;
                            int col = pen + gx * scale + px;
                            if (row < 0 || row >= height || col < 0 || col >= width) continue;
                            int index = row * width + col;
                            if (depth[index] <= tagDistance) continue;
                            pixels[index] = 0xF0F0F0;
                        }
                    }
                }
            }
            pen += advance * scale;
        }
    }

    /** The built-in 4x5 glyphs, for frames before any provider supplies ascii.png. */
    private void paintLegacyNametag(int[] pixels, double[] depth, String name,
                                    int centerCol, int baseRow, double pixelsPerBlock, double tagDistance) {
        int scale = Mth.clamp((int) Math.round(0.35 * pixelsPerBlock / 5), 1, 4);
        var text = name.toUpperCase().replace(' ', '_');
        if (text.length() > 16) text = text.substring(0, 16);
        int textWidth = text.length() * 5 * scale - scale;
        int left = centerCol - textWidth / 2;
        int top = baseRow - 5 * scale;

        for (int row = top - scale; row < baseRow + scale; row++) {
            for (int col = left - scale; col < left + textWidth + scale; col++) {
                if (row < 0 || row >= height || col < 0 || col >= width) continue;
                int index = row * width + col;
                if (depth[index] <= tagDistance) continue;
                pixels[index] = 0x101018;
            }
        }
        for (int i = 0; i < text.length(); i++) {
            int glyph = GLYPH_CHARS.indexOf(text.charAt(i));
            if (glyph < 0) continue;
            var rows = GLYPHS[glyph];
            for (int gy = 0; gy < 5; gy++) {
                for (int gx = 0; gx < 4; gx++) {
                    int at = gy * 4 + gx;
                    if (at >= rows.length() || rows.charAt(at) == '.') continue;
                    for (int py = 0; py < scale; py++) {
                        for (int px = 0; px < scale; px++) {
                            int row = top + gy * scale + py;
                            int col = left + i * 5 * scale + gx * scale + px;
                            if (row < 0 || row >= height || col < 0 || col >= width) continue;
                            int index = row * width + col;
                            if (depth[index] <= tagDistance) continue;
                            pixels[index] = 0xF0F0F0;
                        }
                    }
                }
            }
        }
    }

    private boolean paintEntityModel(int[] pixels, double[] depth, Entity entity,
                                     List<TexturedQuad> quads, double light) {
        boolean painted = false;
        var rel = entity.position().subtract(origin);
        for (var quad : quads) {
            int minCol = Integer.MAX_VALUE;
            int maxCol = Integer.MIN_VALUE;
            int minRow = Integer.MAX_VALUE;
            int maxRow = Integer.MIN_VALUE;
            for (int i = 0; i < 4; i++) {
                double vx = quad.xs()[i] + rel.x;
                double vy = quad.ys()[i] + rel.y;
                double vz = quad.zs()[i] + rel.z;
                double a = Math.max(0.1, vx * forward.x + vy * forward.y + vz * forward.z);
                double sx = (vx * right.x + vy * right.y + vz * right.z) / (a * tanHalfH);
                double sy = (vx * up.x + vy * up.y + vz * up.z) / (a * tanHalfV);
                int col = (int) ((sx + 1) / 2 * width);
                int row = (int) ((1 - sy) / 2 * height);
                minCol = Math.min(minCol, col);
                maxCol = Math.max(maxCol, col);
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
            }
            minCol = Math.max(0, minCol - 1);
            maxCol = Math.min(width - 1, maxCol + 1);
            minRow = Math.max(0, minRow - 1);
            maxRow = Math.min(height - 1, maxRow + 1);
            if (minCol > maxCol || minRow > maxRow) continue;

            double faceShade = light * faceFactorFromNormal(quad.nx(), quad.ny(), quad.nz());
            for (int row = minRow; row <= maxRow; row++) {
                for (int col = minCol; col <= maxCol; col++) {
                    var direction = rayDirection(col, row);
                    double t = intersectQuad(quad, -rel.x, -rel.y, -rel.z,
                        direction.x, direction.y, direction.z, uvA);
                    if (t < 0) continue;
                    int index = row * width + col;
                    if (t >= depth[index]) continue;
                    int texel = quad.texture().sample(uvA[0], uvA[1],
                        t * pixelFootprint * quad.texelDensity());
                    if ((texel >>> 24) < 128) continue;
                    int rgb = texel & 0xFFFFFF;
                    if (quad.colorMul() != 0xFFFFFF) rgb = mulColor(rgb, quad.colorMul());
                    pixels[index] = fadeToHorizon(shadeColor(rgb, faceShade), t);
                    depth[index] = t;
                    painted = true;
                }
            }
        }
        return painted;
    }

    private boolean paintEntityBox(int[] pixels, double[] depth, Entity entity, AABB bounds, double light) {
        boolean painted = false;
        var colors = colorsFor(entity);
        boolean humanoid = entity.getBbHeight() >= 1.4 && entity.getBbWidth() <= 1.0;
        AABB bodyBox = bounds;
        AABB headBox = null;
        if (humanoid) {
            double headHeight = entity.getBbHeight() * 0.25;
            double headWidth = entity.getBbWidth() * 0.7;
            double centerX = (bounds.minX + bounds.maxX) / 2;
            double centerZ = (bounds.minZ + bounds.maxZ) / 2;
            bodyBox = new AABB(bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY - headHeight, bounds.maxZ);
            headBox = new AABB(centerX - headWidth / 2, bounds.maxY - headHeight, centerZ - headWidth / 2,
                centerX + headWidth / 2, bounds.maxY, centerZ + headWidth / 2);
        }

        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            var v = new Vec3(
                (corner & 1) == 0 ? bounds.minX : bounds.maxX,
                (corner & 2) == 0 ? bounds.minY : bounds.maxY,
                (corner & 4) == 0 ? bounds.minZ : bounds.maxZ).subtract(origin);
            double a = Math.max(0.1, v.dot(forward));
            int col = (int) ((v.dot(right) / (a * tanHalfH) + 1) / 2 * width);
            int row = (int) ((1 - v.dot(up) / (a * tanHalfV)) / 2 * height);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        minCol = Math.max(0, minCol - 1);
        maxCol = Math.min(width - 1, maxCol + 1);
        minRow = Math.max(0, minRow - 1);
        maxRow = Math.min(height - 1, maxRow + 1);
        if (minCol > maxCol || minRow > maxRow) return false;

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                var direction = rayDirection(col, row);
                double bestT = Double.MAX_VALUE;
                int bestAxis = -1;
                int color = 0;
                double t = intersectBox(bodyBox, direction);
                if (t >= 0) {
                    bestT = t;
                    bestAxis = boxHitAxis;
                    color = colors[0];
                }
                if (headBox != null) {
                    t = intersectBox(headBox, direction);
                    if (t >= 0 && t < bestT) {
                        bestT = t;
                        bestAxis = boxHitAxis;
                        color = colors[1];
                    }
                }
                if (bestT == Double.MAX_VALUE) continue;
                int index = row * width + col;
                if (bestT >= depth[index]) continue;
                double shade = light * faceFactor(bestAxis < 0 ? 2 : bestAxis, direction);
                pixels[index] = fadeToHorizon(shadeColor(color, shade), bestT);
                depth[index] = bestT;
                painted = true;
            }
        }
        return painted;
    }

    private static int[] colorsFor(Entity entity) {
        var colors = ENTITY_COLORS.get(entity.getType());
        if (colors != null) return colors;
        return entity instanceof Enemy ? HOSTILE_COLORS : NEUTRAL_COLORS;
    }

    /** Ray-AABB slab test; returns entry distance (world units) or -1, entry axis in {@link #boxHitAxis}. */
    private double intersectBox(AABB box, Vec3 direction) {
        double tMin = 0.05;
        double tMax = MAX_DISTANCE;
        int axis = -1;
        for (int a = 0; a < 3; a++) {
            double component = a == 0 ? direction.x : a == 1 ? direction.y : direction.z;
            double originC = a == 0 ? origin.x : a == 1 ? origin.y : origin.z;
            double min = a == 0 ? box.minX : a == 1 ? box.minY : box.minZ;
            double max = a == 0 ? box.maxX : a == 1 ? box.maxY : box.maxZ;
            if (Math.abs(component) < 1e-9) {
                if (originC < min || originC > max) return -1;
                continue;
            }
            double t1 = (min - originC) / component;
            double t2 = (max - originC) / component;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tMin) {
                tMin = t1;
                axis = a;
            }
            tMax = Math.min(tMax, t2);
            if (tMax < tMin) return -1;
        }
        boxHitAxis = axis;
        return tMin;
    }

    // === World access ===

    /** @return the state at the position, or null for air / empty sections; sets {@link #chunkMissing} on unloaded chunks. */
    @Nullable
    private BlockState blockStateAt(int x, int y, int z) {
        chunkMissing = false;
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        if (sx != sectionX || sy != sectionY || sz != sectionZ) {
            sectionX = sx;
            sectionY = sy;
            sectionZ = sz;
            if (sx != chunkX || sz != chunkZ) {
                chunk = level.getChunkSource().getChunkNow(sx, sz);
                chunkX = sx;
                chunkZ = sz;
            }
            section = null;
            if (chunk != null) {
                int index = chunk.getSectionIndexFromSectionY(sy);
                if (index >= 0 && index < chunk.getSections().length) section = chunk.getSections()[index];
            }
        }
        if (chunk == null) {
            chunkMissing = true;
            return null;
        }
        if (section == null || section.hasOnlyAir()) return null;
        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    private static int pack(int r, int g, int b) {
        return (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
    }

    private static char quantizeFixed(int rgb) {
        int key = ((rgb >> 12) & 0xF00) | ((rgb >> 8) & 0xF0) | ((rgb >> 4) & 0xF);
        char cached = QUANTIZE_CACHE[key];
        if (cached != 0) return (char) (cached - 1);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < FIXED_PALETTE.length; i++) {
            int pr = (FIXED_PALETTE[i] >> 16) & 0xFF;
            int pg = (FIXED_PALETTE[i] >> 8) & 0xFF;
            int pb = FIXED_PALETTE[i] & 0xFF;
            int distance = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        QUANTIZE_CACHE[key] = (char) (HEX[best] + 1);
        return HEX[best];
    }
}
