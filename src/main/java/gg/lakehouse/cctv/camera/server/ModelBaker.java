package gg.lakehouse.cctv.camera.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.lakehouse.cctv.camera.TexturePixels;
import gg.lakehouse.cctv.camera.TexturedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side block model baker: blockstate JSON to variant selection to the
 * model parent chain to element quads, with textures decoded straight from
 * the asset zips. A deliberate subset of vanilla's baker — element geometry,
 * face UVs and rotations, variant X/Y rotation — which covers practically
 * every vanilla and JSON-modelled modded block. No uvlock, no random
 * variants (the first entry wins), because the camera only needs one stable
 * appearance per state.
 */
final class ModelBaker {
    private static final TexturePixels MISSING = TexturePixels.solid(0x888888);

    private final AssetSources sources;
    private final Map<String, JsonObject> modelCache = new HashMap<>();
    private final Map<String, TexturePixels> textureCache = new ConcurrentHashMap<>();

    ModelBaker(AssetSources sources) {
        this.sources = sources;
    }

    List<TexturedQuad> bake(BlockState state) {
        var result = new ArrayList<TexturedQuad>();
        var name = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (name == null) return result;
        var json = readJson("assets/" + name.getNamespace() + "/blockstates/" + name.getPath() + ".json");
        if (json == null) return result;

        if (json.has("variants")) {
            var variant = selectVariant(json.getAsJsonObject("variants"), state);
            if (variant != null) bakeVariant(result, variant);
        } else if (json.has("multipart")) {
            for (var element : json.getAsJsonArray("multipart")) {
                var part = element.getAsJsonObject();
                if (!part.has("when") || matches(part.get("when").getAsJsonObject(), state)) {
                    bakeVariant(result, firstOf(part.get("apply")));
                }
            }
        }
        return result;
    }

    /** Bakes a standalone model by name, in model space with no blockstate rotation. */
    List<TexturedQuad> bakeModel(String modelName) {
        var result = new ArrayList<TexturedQuad>();
        var textures = new HashMap<String, String>();
        var elements = collectModel(modelName, textures);
        if (elements == null) return result;
        for (var element : elements) {
            bakeElement(result, element.getAsJsonObject(), textures, 0, 0);
        }
        return result;
    }

    // === Blockstate selection ===

    @Nullable
    private static JsonObject selectVariant(JsonObject variants, BlockState state) {
        for (var entry : variants.entrySet()) {
            if (keyMatches(entry.getKey(), state)) return firstOf(entry.getValue());
        }
        return null;
    }

    private static JsonObject firstOf(JsonElement value) {
        if (value.isJsonArray()) return value.getAsJsonArray().get(0).getAsJsonObject();
        return value.getAsJsonObject();
    }

    private static boolean keyMatches(String key, BlockState state) {
        if (key.isEmpty()) return true;
        for (var pair : key.split(",")) {
            var split = pair.split("=", 2);
            if (split.length != 2 || !hasValue(state, split[0], split[1])) return false;
        }
        return true;
    }

    private static boolean matches(JsonObject when, BlockState state) {
        if (when.has("OR")) {
            for (var option : when.getAsJsonArray("OR")) {
                if (matches(option.getAsJsonObject(), state)) return true;
            }
            return false;
        }
        if (when.has("AND")) {
            for (var option : when.getAsJsonArray("AND")) {
                if (!matches(option.getAsJsonObject(), state)) return false;
            }
            return true;
        }
        for (var entry : when.entrySet()) {
            var allowed = entry.getValue().getAsString().split("\\|");
            boolean any = false;
            for (var value : allowed) any = any || hasValue(state, entry.getKey(), value);
            if (!any) return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean hasValue(BlockState state, String propertyName, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return ((Property) property).getName(state.getValue(property)).equals(value);
            }
        }
        return false;
    }

    // === Model chain ===

    private void bakeVariant(List<TexturedQuad> result, JsonObject variant) {
        var modelName = variant.get("model").getAsString();
        int rotateX = variant.has("x") ? variant.get("x").getAsInt() : 0;
        int rotateY = variant.has("y") ? variant.get("y").getAsInt() : 0;

        var textures = new HashMap<String, String>();
        var elements = collectModel(modelName, textures);
        if (elements == null) return;
        for (var element : elements) {
            bakeElement(result, element.getAsJsonObject(), textures, rotateX, rotateY);
        }
    }

    /** Walks the parent chain, merging textures; returns the deepest elements array. */
    @Nullable
    private JsonArray collectModel(String modelName, Map<String, String> textures) {
        JsonArray elements = null;
        var current = modelName;
        for (int depth = 0; depth < 16 && current != null; depth++) {
            if (current.startsWith("builtin/")) return elements;
            var json = readJson(assetPath(current, "models") + ".json");
            if (json == null) break;
            if (json.has("textures")) {
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (elements == null && json.has("elements")) elements = json.getAsJsonArray("elements");
            current = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        return elements;
    }

    private static String assetPath(String name, String kind) {
        int colon = name.indexOf(':');
        var namespace = colon < 0 ? "minecraft" : name.substring(0, colon);
        var path = colon < 0 ? name : name.substring(colon + 1);
        return "assets/" + namespace + "/" + kind + "/" + path;
    }

    // === Element baking ===

    private void bakeElement(List<TexturedQuad> result, JsonObject element, Map<String, String> textures,
                             int rotateX, int rotateY) {
        var from = vector(element.getAsJsonArray("from"));
        var to = vector(element.getAsJsonArray("to"));
        var faces = element.getAsJsonObject("faces");
        if (faces == null) return;

        for (var entry : faces.entrySet()) {
            var direction = Direction.byName(entry.getKey());
            if (direction == null) continue;
            var face = entry.getValue().getAsJsonObject();
            var texture = texture(resolveTexture(face.get("texture").getAsString(), textures));
            int tintIndex = face.has("tintindex") ? face.get("tintindex").getAsInt() : TexturedQuad.TINT_NONE;

            var xs = new float[4];
            var ys = new float[4];
            var zs = new float[4];
            var us = new float[4];
            var vs = new float[4];
            fillFace(direction, from, to, xs, ys, zs, us, vs);
            if (face.has("uv")) {
                overrideUv(face.getAsJsonArray("uv"), direction, us, vs);
            }
            if (face.has("rotation")) {
                rotateUv(us, vs, ((face.get("rotation").getAsInt() % 360) + 360) % 360 / 90);
            }

            if (element.has("rotation")) applyElementRotation(element.getAsJsonObject("rotation"), xs, ys, zs);
            if (rotateX != 0) rotateAroundCenter(xs, ys, zs, rotateX, true);
            if (rotateY != 0) rotateAroundCenter(xs, ys, zs, rotateY, false);

            result.add(TexturedQuad.of(xs, ys, zs, us, vs, texture, tintIndex, -1));
        }
    }

    private static float[] vector(JsonArray array) {
        return new float[]{array.get(0).getAsFloat() / 16, array.get(1).getAsFloat() / 16, array.get(2).getAsFloat() / 16};
    }

    /** Vertex order gives outward normals; default UVs follow vanilla's per-face rules. */
    private static void fillFace(Direction direction, float[] f, float[] t,
                                 float[] xs, float[] ys, float[] zs, float[] us, float[] vs) {
        switch (direction) {
            case UP -> {
                set(0, xs, ys, zs, us, vs, f[0], t[1], f[2], f[0], f[2]);
                set(1, xs, ys, zs, us, vs, f[0], t[1], t[2], f[0], t[2]);
                set(2, xs, ys, zs, us, vs, t[0], t[1], t[2], t[0], t[2]);
                set(3, xs, ys, zs, us, vs, t[0], t[1], f[2], t[0], f[2]);
            }
            case DOWN -> {
                set(0, xs, ys, zs, us, vs, f[0], f[1], t[2], f[0], 1 - t[2]);
                set(1, xs, ys, zs, us, vs, f[0], f[1], f[2], f[0], 1 - f[2]);
                set(2, xs, ys, zs, us, vs, t[0], f[1], f[2], t[0], 1 - f[2]);
                set(3, xs, ys, zs, us, vs, t[0], f[1], t[2], t[0], 1 - t[2]);
            }
            case NORTH -> {
                set(0, xs, ys, zs, us, vs, t[0], t[1], f[2], 1 - t[0], 1 - t[1]);
                set(1, xs, ys, zs, us, vs, t[0], f[1], f[2], 1 - t[0], 1 - f[1]);
                set(2, xs, ys, zs, us, vs, f[0], f[1], f[2], 1 - f[0], 1 - f[1]);
                set(3, xs, ys, zs, us, vs, f[0], t[1], f[2], 1 - f[0], 1 - t[1]);
            }
            case SOUTH -> {
                set(0, xs, ys, zs, us, vs, f[0], t[1], t[2], f[0], 1 - t[1]);
                set(1, xs, ys, zs, us, vs, f[0], f[1], t[2], f[0], 1 - f[1]);
                set(2, xs, ys, zs, us, vs, t[0], f[1], t[2], t[0], 1 - f[1]);
                set(3, xs, ys, zs, us, vs, t[0], t[1], t[2], t[0], 1 - t[1]);
            }
            case WEST -> {
                set(0, xs, ys, zs, us, vs, f[0], t[1], f[2], f[2], 1 - t[1]);
                set(1, xs, ys, zs, us, vs, f[0], f[1], f[2], f[2], 1 - f[1]);
                set(2, xs, ys, zs, us, vs, f[0], f[1], t[2], t[2], 1 - f[1]);
                set(3, xs, ys, zs, us, vs, f[0], t[1], t[2], t[2], 1 - t[1]);
            }
            case EAST -> {
                set(0, xs, ys, zs, us, vs, t[0], t[1], t[2], 1 - t[2], 1 - t[1]);
                set(1, xs, ys, zs, us, vs, t[0], f[1], t[2], 1 - t[2], 1 - f[1]);
                set(2, xs, ys, zs, us, vs, t[0], f[1], f[2], 1 - f[2], 1 - f[1]);
                set(3, xs, ys, zs, us, vs, t[0], t[1], f[2], 1 - f[2], 1 - t[1]);
            }
        }
    }

    private static void set(int i, float[] xs, float[] ys, float[] zs, float[] us, float[] vs,
                            float x, float y, float z, float u, float v) {
        xs[i] = x;
        ys[i] = y;
        zs[i] = z;
        us[i] = u;
        vs[i] = v;
    }

    /** Explicit uv [u1,v1,u2,v2] replaces the defaults, mapped over the same corner order. */
    private static void overrideUv(JsonArray uv, Direction direction, float[] us, float[] vs) {
        float u1 = uv.get(0).getAsFloat() / 16;
        float v1 = uv.get(1).getAsFloat() / 16;
        float u2 = uv.get(2).getAsFloat() / 16;
        float v2 = uv.get(3).getAsFloat() / 16;
        if (direction == Direction.UP || direction == Direction.DOWN) {
            us[0] = u1; vs[0] = v1;
            us[1] = u1; vs[1] = v2;
            us[2] = u2; vs[2] = v2;
            us[3] = u2; vs[3] = v1;
        } else {
            us[0] = u1; vs[0] = v1;
            us[1] = u1; vs[1] = v2;
            us[2] = u2; vs[2] = v2;
            us[3] = u2; vs[3] = v1;
        }
    }

    private static void rotateUv(float[] us, float[] vs, int quarterTurns) {
        for (int turn = 0; turn < quarterTurns; turn++) {
            float u = us[0];
            float v = vs[0];
            for (int i = 0; i < 3; i++) {
                us[i] = us[i + 1];
                vs[i] = vs[i + 1];
            }
            us[3] = u;
            vs[3] = v;
        }
    }

    private static void applyElementRotation(JsonObject rotation, float[] xs, float[] ys, float[] zs) {
        var origin = vector(rotation.getAsJsonArray("origin"));
        float angle = (float) Math.toRadians(rotation.get("angle").getAsFloat());
        var axis = rotation.get("axis").getAsString();
        float rescale = rotation.has("rescale") && rotation.get("rescale").getAsBoolean()
            ? 1f / (float) Math.cos(Math.toRadians(Math.abs(rotation.get("angle").getAsFloat()))) : 1;
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        for (int i = 0; i < 4; i++) {
            float x = xs[i] - origin[0];
            float y = ys[i] - origin[1];
            float z = zs[i] - origin[2];
            switch (axis) {
                case "x" -> {
                    float ny = y * cos - z * sin;
                    float nz = y * sin + z * cos;
                    y = ny * rescale;
                    z = nz * rescale;
                }
                case "y" -> {
                    float nx = x * cos + z * sin;
                    float nz = -x * sin + z * cos;
                    x = nx * rescale;
                    z = nz * rescale;
                }
                case "z" -> {
                    float nx = x * cos - y * sin;
                    float ny = x * sin + y * cos;
                    x = nx * rescale;
                    y = ny * rescale;
                }
            }
            xs[i] = x + origin[0];
            ys[i] = y + origin[1];
            zs[i] = z + origin[2];
        }
    }

    /** Blockstate x/y rotation: clockwise about the block center in 90-degree steps. */
    private static void rotateAroundCenter(float[] xs, float[] ys, float[] zs, int degrees, boolean aboutX) {
        float angle = (float) Math.toRadians(-degrees);
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        for (int i = 0; i < 4; i++) {
            float x = xs[i] - 0.5f;
            float y = ys[i] - 0.5f;
            float z = zs[i] - 0.5f;
            if (aboutX) {
                float ny = y * cos - z * sin;
                float nz = y * sin + z * cos;
                y = ny;
                z = nz;
            } else {
                float nx = x * cos + z * sin;
                float nz = -x * sin + z * cos;
                x = nx;
                z = nz;
            }
            xs[i] = x + 0.5f;
            ys[i] = y + 0.5f;
            zs[i] = z + 0.5f;
        }
    }

    // === Assets ===

    private String resolveTexture(String reference, Map<String, String> textures) {
        for (int depth = 0; depth < 16 && reference.startsWith("#"); depth++) {
            var next = textures.get(reference.substring(1));
            if (next == null) return "";
            reference = next;
        }
        return reference;
    }

    TexturePixels texture(String name) {
        if (name.isEmpty()) return MISSING;
        return textureCache.computeIfAbsent(name, key -> {
            var data = sources.read(assetPath(key, "textures") + ".png");
            if (data == null) return MISSING;
            try {
                var image = ImageIO.read(new ByteArrayInputStream(data));
                var argb = new int[image.getWidth() * image.getHeight()];
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());
                return new TexturePixels(argb, image.getWidth(), image.getHeight());
            } catch (Exception e) {
                return MISSING;
            }
        });
    }

    @Nullable
    JsonObject readJson(String path) {
        var cached = modelCache.get(path);
        if (cached != null) return cached;
        var data = sources.read(path);
        if (data == null) return null;
        try {
            var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            modelCache.put(path, json);
            return json;
        } catch (Exception e) {
            return null;
        }
    }
}
