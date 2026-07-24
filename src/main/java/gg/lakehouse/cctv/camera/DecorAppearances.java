package gg.lakehouse.cctv.camera;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Decoration entities: paintings (their real canvases), item frames (board,
 * the framed item, and filled maps drawn from live map data), and dropped
 * items (block items as miniature blocks, the rest as crossed sprites).
 * Shared by both camera pipelines.
 */
public final class DecorAppearances {
    private static final Map<Integer, TexturePixels> MAP_CACHE = new ConcurrentHashMap<>();

    private DecorAppearances() {
    }

    @Nullable
    public static List<TexturedQuad> capture(Entity entity, Function<String, TexturePixels> textures,
                                             @Nullable Function<BlockState, List<TexturedQuad>> blockQuads) {
        if (entity instanceof Painting painting) return capturePainting(painting, textures);
        if (entity instanceof ItemFrame frame) return captureFrame(frame, textures, blockQuads);
        if (entity instanceof ItemEntity item) return captureItem(item.getItem(), textures, blockQuads);
        if (entity instanceof net.minecraft.world.entity.ExperienceOrb) {
            var out = new ArrayList<TexturedQuad>();
            crossQuad(out, textures.apply("minecraft:entity/experience_orb"), 45);
            crossQuad(out, textures.apply("minecraft:entity/experience_orb"), 135);
            return out;
        }
        if (entity instanceof net.minecraft.world.entity.decoration.LeashFenceKnotEntity) {
            return packEntity("minecraft:leash_knot#main", "minecraft:entity/lead_knot", textures);
        }
        if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) {
            return packEntity("minecraft:end_crystal#main", "minecraft:entity/end_crystal/end_crystal", textures);
        }
        if (entity instanceof net.minecraft.world.entity.projectile.AbstractArrow) {
            var out = new ArrayList<TexturedQuad>();
            var texture = textures.apply(entity instanceof net.minecraft.world.entity.projectile.ThrownTrident
                ? "minecraft:entity/trident" : "minecraft:entity/projectiles/arrow");
            crossQuad(out, texture, entity.getYRot() + 45);
            crossQuad(out, texture, entity.getYRot() + 135);
            return out;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.ThrowableItemProjectile throwable) {
            return captureItem(throwable.getItem(), textures, null);
        }
        if (entity instanceof net.minecraft.world.entity.projectile.FishingHook) {
            var out = new ArrayList<TexturedQuad>();
            crossQuad(out, textures.apply("minecraft:entity/fishing_hook"), 45);
            crossQuad(out, textures.apply("minecraft:entity/fishing_hook"), 135);
            return out;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile) {
            var out = new ArrayList<TexturedQuad>();
            crossQuad(out, textures.apply("minecraft:item/fire_charge"), 45);
            crossQuad(out, textures.apply("minecraft:item/fire_charge"), 135);
            return out;
        }
        return null;
    }

    /** A geometry-pack entity layer, flipped to world orientation and seated on the entity origin. */
    @Nullable
    private static List<TexturedQuad> packEntity(String layer, String texture,
                                                 Function<String, TexturePixels> textures) {
        var part = GeometryPack.layers().get(layer);
        if (part == null) return null;
        var out = new ArrayList<TexturedQuad>();
        GeometryPack.emit(out, part, new Matrix4f().scale(1, -1, -1), textures.apply(texture), 0xFFFFFF);
        float minY = Float.MAX_VALUE;
        for (var quad : out) {
            for (int i = 0; i < 4; i++) minY = Math.min(minY, quad.ys()[i]);
        }
        for (var quad : out) {
            for (int i = 0; i < 4; i++) quad.ys()[i] -= minY;
        }
        return out;
    }

    /** Item geometry centered on the origin — shared with held items and frames. */
    @Nullable
    public static List<TexturedQuad> itemQuads(ItemStack stack, Function<String, TexturePixels> textures,
                                               @Nullable Function<BlockState, List<TexturedQuad>> blockQuads) {
        return captureItem(stack, textures, blockQuads);
    }

    // === Paintings ===

    private static List<TexturedQuad> capturePainting(Painting painting, Function<String, TexturePixels> textures) {
        var variant = painting.getVariant().value();
        var key = BuiltInRegistries.PAINTING_VARIANT.getKey(variant);
        if (key == null) return null;
        float width = variant.getWidth() / 16f;
        float height = variant.getHeight() / 16f;
        var texture = textures.apply(key.getNamespace() + ":painting/" + key.getPath());
        return facingQuad(painting.getDirection(), width, height, 0.02f, texture);
    }

    // === Item frames ===

    private static List<TexturedQuad> captureFrame(ItemFrame frame, Function<String, TexturePixels> textures,
                                                   @Nullable Function<BlockState, List<TexturedQuad>> blockQuads) {
        var out = new ArrayList<TexturedQuad>();
        var direction = frame.getDirection();
        var board = textures.apply("minecraft:block/birch_planks");
        out.addAll(facingQuad(direction, 0.75f, 0.75f, 0.02f, board));

        var stack = frame.getItem();
        if (!stack.isEmpty()) {
            if (stack.getItem() instanceof MapItem && frame.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                var mapTexture = mapTexture(stack, serverLevel);
                if (mapTexture != null) out.addAll(facingQuad(direction, 1, 1, 0.05f, mapTexture));
            } else {
                var content = captureItem(stack, textures, blockQuads);
                if (content != null) {
                    // Seat the item onto the frame face at 40% size.
                    var basis = frameBasis(direction);
                    for (var quad : content) {
                        for (int i = 0; i < 4; i++) {
                            float x = quad.xs()[i] * 0.4f;
                            float y = (quad.ys()[i] - 0.2f) * 0.4f;
                            float z = quad.zs()[i] * 0.4f;
                            var placed = new Vector3f(basis.right.x * x + basis.up.x * y + basis.out.x * (z + 0.06f),
                                basis.right.y * x + basis.up.y * y + basis.out.y * (z + 0.06f),
                                basis.right.z * x + basis.up.z * y + basis.out.z * (z + 0.06f));
                            quad.xs()[i] = placed.x;
                            quad.ys()[i] = placed.y;
                            quad.zs()[i] = placed.z;
                        }
                    }
                    out.addAll(content);
                }
            }
        }
        return out;
    }

    @Nullable
    private static TexturePixels mapTexture(ItemStack stack, net.minecraft.server.level.ServerLevel level) {
        var id = MapItem.getMapId(stack);
        if (id == null) return null;
        return MAP_CACHE.computeIfAbsent(id, mapId -> {
            var data = MapItem.getSavedData(stack, level);
            if (data == null) return null;
            var argb = new int[128 * 128];
            for (int i = 0; i < argb.length; i++) {
                int abgr = MapColor.getColorFromPackedId(data.colors[i]);
                argb[i] = 0xFF000000 | ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
            }
            return new TexturePixels(argb, 128, 128);
        });
    }

    // === Dropped items ===

    @Nullable
    private static List<TexturedQuad> captureItem(ItemStack stack, Function<String, TexturePixels> textures,
                                                  @Nullable Function<BlockState, List<TexturedQuad>> blockQuads) {
        if (stack.isEmpty()) return null;
        var out = new ArrayList<TexturedQuad>();
        if (stack.getItem() instanceof BlockItem blockItem && blockQuads != null) {
            var quads = blockQuads.apply(blockItem.getBlock().defaultBlockState());
            if (quads != null && !quads.isEmpty()) {
                for (var quad : quads) {
                    var xs = new float[4];
                    var ys = new float[4];
                    var zs = new float[4];
                    for (int i = 0; i < 4; i++) {
                        xs[i] = (quad.xs()[i] - 0.5f) * 0.25f;
                        ys[i] = quad.ys()[i] * 0.25f + 0.02f;
                        zs[i] = (quad.zs()[i] - 0.5f) * 0.25f;
                    }
                    out.add(TexturedQuad.ofColored(xs, ys, zs, quad.us().clone(), quad.vs().clone(),
                        quad.texture(), TexturedQuad.TINT_NONE, quad.alphaOverride(), quad.colorMul()));
                }
                return out;
            }
        }
        var name = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (name == null) return null;
        var texture = textures.apply(name.getNamespace() + ":item/" + name.getPath());
        // Crossed sprites read well from every camera angle, like plants do.
        crossQuad(out, texture, 45);
        crossQuad(out, texture, 135);
        return out;
    }

    private static void crossQuad(List<TexturedQuad> out, TexturePixels texture, float yawDegrees) {
        float half = 0.2f;
        var matrix = new Matrix4f().rotateY((float) Math.toRadians(yawDegrees));
        var xs = new float[4];
        var ys = new float[4];
        var zs = new float[4];
        var corners = new float[][]{{-half, 0.45f, 0}, {half, 0.45f, 0}, {half, 0.05f, 0}, {-half, 0.05f, 0}};
        var position = new Vector3f();
        for (int i = 0; i < 4; i++) {
            position.set(corners[i][0], corners[i][1], corners[i][2]);
            matrix.transformPosition(position);
            xs[i] = position.x;
            ys[i] = position.y;
            zs[i] = position.z;
        }
        out.add(TexturedQuad.of(xs, ys, zs, new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1},
            texture, TexturedQuad.TINT_NONE, -1));
    }

    // === Facing helpers ===

    private record Basis(Vector3f right, Vector3f up, Vector3f out) {
    }

    private static Basis frameBasis(Direction direction) {
        var out = new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        Vector3f up;
        Vector3f right;
        if (direction == Direction.UP || direction == Direction.DOWN) {
            up = new Vector3f(0, 0, direction == Direction.UP ? 1 : -1);
            right = new Vector3f(1, 0, 0);
        } else {
            up = new Vector3f(0, 1, 0);
            var clockwise = direction.getCounterClockWise();
            right = new Vector3f(clockwise.getStepX(), 0, clockwise.getStepZ());
        }
        return new Basis(right, up, out);
    }

    /** One quad centered on the entity, facing along its direction. */
    private static List<TexturedQuad> facingQuad(Direction direction, float width, float height,
                                                 float offset, TexturePixels texture) {
        var basis = frameBasis(direction);
        var xs = new float[4];
        var ys = new float[4];
        var zs = new float[4];
        var corners = new float[][]{{-width / 2, height / 2}, {width / 2, height / 2},
            {width / 2, -height / 2}, {-width / 2, -height / 2}};
        for (int i = 0; i < 4; i++) {
            float r = corners[i][0];
            float u = corners[i][1];
            xs[i] = basis.right.x * r + basis.up.x * u + basis.out.x * offset;
            ys[i] = basis.right.y * r + basis.up.y * u + basis.out.y * offset;
            zs[i] = basis.right.z * r + basis.up.z * u + basis.out.z * offset;
        }
        var out = new ArrayList<TexturedQuad>(1);
        out.add(TexturedQuad.of(xs, ys, zs, new float[]{1, 0, 0, 1}, new float[]{0, 0, 1, 1},
            texture, TexturedQuad.TINT_NONE, -1));
        return out;
    }
}
