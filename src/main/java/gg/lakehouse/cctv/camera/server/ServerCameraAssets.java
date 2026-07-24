package gg.lakehouse.cctv.camera.server;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.camera.BlockAppearanceProvider;
import gg.lakehouse.cctv.camera.EntityAppearanceProvider;
import gg.lakehouse.cctv.camera.TexturePixels;
import gg.lakehouse.cctv.camera.TexturedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full-quality camera rendering on dedicated servers: fetches the vanilla
 * client assets once, bakes block models itself, loads the exported entity
 * geometry, and serves the same appearance interfaces the client providers
 * do. Starts preparing asynchronously when the server boots; cameras use the
 * map-color fallback until everything is ready.
 */
public final class ServerCameraAssets {
    private static volatile Provider provider;
    private static volatile boolean starting;

    private ServerCameraAssets() {
    }

    public static void begin(MinecraftServer server) {
        if (provider != null || starting || !server.isDedicatedServer()) return;
        starting = true;
        var cacheDir = server.getServerDirectory().toPath().resolve("cctv-assets");
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            try {
                gg.lakehouse.cctv.camera.GeometryPack.overlay(cacheDir.resolve("entity_geometry.json.gz"));
                var sources = new AssetSources(cacheDir);
                provider = new Provider(sources);
                CCTV.LOGGER.info("Camera assets ready: dedicated server renders at full quality");
            } catch (Exception e) {
                CCTV.LOGGER.error("Camera assets unavailable; cameras stay on the map-color fallback", e);
            }
        });
    }

    @Nullable
    public static BlockAppearanceProvider blocks() {
        return provider;
    }

    @Nullable
    public static EntityAppearanceProvider entities() {
        return provider;
    }

    private static final class Provider implements BlockAppearanceProvider, EntityAppearanceProvider {
        private final ModelBaker baker;
        private final ServerEntityAppearances entities;
        private final Map<BlockState, List<TexturedQuad>> quadCache = new ConcurrentHashMap<>();
        private final Map<String, List<TexturedQuad>> modelQuadCache = new ConcurrentHashMap<>();
        private final int[] grassColormap;
        private final int[] foliageColormap;

        Provider(AssetSources sources) {
            baker = new ModelBaker(sources);
            entities = new ServerEntityAppearances(sources, baker);
            grassColormap = colormap(sources, "assets/minecraft/textures/colormap/grass.png");
            foliageColormap = colormap(sources, "assets/minecraft/textures/colormap/foliage.png");
        }

        private static int[] colormap(AssetSources sources, String path) {
            try {
                var data = sources.read(path);
                if (data == null) return null;
                var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
                var pixels = new int[image.getWidth() * image.getHeight()];
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
                return pixels;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public List<TexturedQuad> quads(BlockState state) {
            return quadCache.computeIfAbsent(state, this::build);
        }

        private List<TexturedQuad> build(BlockState state) {
            var result = new ArrayList<TexturedQuad>();
            // Rendered per-position through dynamicQuads instead.
            if (gg.lakehouse.cctv.camera.BlockEntityAppearances.isDynamic(state)) return result;
            try {
                var fluid = state.getFluidState();
                if (!(state.getBlock() instanceof LiquidBlock)) {
                    result.addAll(baker.bake(state));
                }
                if (!fluid.isEmpty()) addFluid(result, fluid);
                gg.lakehouse.cctv.camera.BlockEntityAppearances.appendExtras(result, state, baker::texture);
                if (result.isEmpty()) gg.lakehouse.cctv.camera.BlockEntityAppearances.build(result, state, baker::texture);
                // INVISIBLE shapes we don't cover (barriers, markers) stay unseen.
                if (result.isEmpty() && state.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE) {
                    addCollisionBoxes(result, state);
                }
            } catch (Exception e) {
                result.clear();
                addCollisionBoxes(result, state);
            }
            return result;
        }

        private void addFluid(List<TexturedQuad> result, FluidState fluid) {
            boolean water = fluid.is(FluidTags.WATER);
            var texture = baker.texture(water ? "minecraft:block/water_still" : "minecraft:block/lava_still");
            float top = fluid.isSource() ? 0.875f : Mth.clamp(fluid.getOwnHeight(), 0.1f, 0.875f);
            addBox(result, 0, 0, 0, 1, top, 1, texture,
                water ? TexturedQuad.TINT_WATER : TexturedQuad.TINT_NONE, water ? 170 : 255);
        }

        private static void addCollisionBoxes(List<TexturedQuad> result, BlockState state) {
            try {
                int color = 0x808080;
                var mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (mapColor != null) color = mapColor.col;
                var texture = TexturePixels.solid(color);
                for (var box : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
                    addBox(result, (float) box.minX, (float) box.minY, (float) box.minZ,
                        (float) box.maxX, (float) box.maxY, (float) box.maxZ, texture, TexturedQuad.TINT_NONE, 255);
                }
            } catch (Exception ignored) {
                // No collision shape without a level; leave invisible.
            }
        }

        private static void addBox(List<TexturedQuad> result, float x1, float y1, float z1,
                                   float x2, float y2, float z2, TexturePixels texture, int tintIndex, int alpha) {
            var u = new float[]{0, 1, 1, 0};
            var v = new float[]{0, 0, 1, 1};
            result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y1, y1, y1, y1}, new float[]{z1, z1, z2, z2}, u, v, texture, tintIndex, alpha));
            result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y2, y2, y2, y2}, new float[]{z1, z1, z2, z2}, u, v, texture, tintIndex, alpha));
            result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y1, y1, y2, y2}, new float[]{z1, z1, z1, z1}, u, v, texture, tintIndex, alpha));
            result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y1, y1, y2, y2}, new float[]{z2, z2, z2, z2}, u, v, texture, tintIndex, alpha));
            result.add(TexturedQuad.of(new float[]{x1, x1, x1, x1}, new float[]{y1, y1, y2, y2}, new float[]{z1, z2, z2, z1}, u, v, texture, tintIndex, alpha));
            result.add(TexturedQuad.of(new float[]{x2, x2, x2, x2}, new float[]{y1, y1, y2, y2}, new float[]{z1, z2, z2, z1}, u, v, texture, tintIndex, alpha));
        }

        @Override
        public Vec3 offset(BlockState state, ServerLevel level, BlockPos pos) {
            return state.getOffset(level, pos);
        }

        @Override
        public int tint(BlockState state, ServerLevel level, BlockPos pos, int tintIndex) {
            var biome = level.getBiome(pos).value();
            if (tintIndex == TexturedQuad.TINT_WATER) return biome.getWaterColor();
            float temperature = Mth.clamp(biome.getBaseTemperature(), 0, 1);
            float downfall = Mth.clamp(biome.getModifiedClimateSettings().downfall(), 0, 1);
            if (state.is(BlockTags.LEAVES) || state.is(net.minecraft.world.level.block.Blocks.VINE)) {
                if (state.is(net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES)) return 0x619961;
                if (state.is(net.minecraft.world.level.block.Blocks.BIRCH_LEAVES)) return 0x80A755;
                return sample(foliageColormap, temperature, downfall, 0x48B518);
            }
            if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)) return 0xB00000;
            return sample(grassColormap, temperature, downfall, 0x7CBD6B);
        }

        private static int sample(int[] colormap, float temperature, float downfall, int fallback) {
            if (colormap == null) return fallback;
            int x = (int) ((1 - temperature) * 255);
            int y = (int) ((1 - downfall * temperature) * 255);
            int index = y * 256 + x;
            if (index < 0 || index >= colormap.length) return fallback;
            return colormap[index] & 0xFFFFFF;
        }

        @Override
        @Nullable
        public List<TexturedQuad> capture(net.minecraft.world.entity.Entity entity) {
            if (entity instanceof LivingEntity living) return entities.capture(living, this::quads);
            var vehicle = gg.lakehouse.cctv.camera.VehicleAppearances.capture(entity, baker::texture, this::quads);
            if (vehicle != null) return vehicle;
            return gg.lakehouse.cctv.camera.DecorAppearances.capture(entity, baker::texture, this::quads);
        }

        @Override
        public List<TexturedQuad> dynamicQuads(BlockState state, ServerLevel level, BlockPos pos) {
            if (state.getBlock() instanceof gg.lakehouse.cctv.camera.CameraBlock
                && level.getBlockEntity(pos) instanceof gg.lakehouse.cctv.camera.CameraBlockEntity camera) {
                return gg.lakehouse.cctv.camera.CameraRigAppearances.build(state, camera, this::modelQuads);
            }
            if (!gg.lakehouse.cctv.camera.BlockEntityAppearances.isDynamic(state)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
                return List.of();
            }
            return gg.lakehouse.cctv.camera.BlockEntityAppearances.dynamic(state, level, pos,
                baker::texture, this::quads);
        }

        private List<TexturedQuad> modelQuads(String name) {
            return modelQuadCache.computeIfAbsent(name, baker::bakeModel);
        }

        @Override
        public TexturePixels texture(String name) {
            return baker.texture(name);
        }
    }
}
