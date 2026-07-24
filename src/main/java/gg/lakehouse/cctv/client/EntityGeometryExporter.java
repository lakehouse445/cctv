package gg.lakehouse.cctv.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gg.lakehouse.cctv.CCTV;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelPart;

import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Dev-time exporter: bakes every entity model layer and writes the polygon
 * geometry (part trees, default poses, per-vertex UVs) to a gzipped JSON the
 * dedicated server ships and loads — the server has no client model code, so
 * this file is how it knows what a zombie is shaped like. Runs in the dev
 * client only; the output is committed as a mod resource.
 */
public final class EntityGeometryExporter {
    private EntityGeometryExporter() {
    }

    /** Vanilla layers only — the build-time pack bundled with the mod. */
    public static void export(Path target) {
        exportEntries(target, LayerDefinitions.createRoots().entrySet());
    }

    /**
     * Every layer the running client knows, modded ones included, pulled from
     * the live model set. The written file can be dropped into a dedicated
     * server's cctv-assets folder so its cameras learn modded entity shapes.
     */
    @SuppressWarnings("unchecked")
    public static void exportAll(Path target) {
        try {
            var modelSet = net.minecraft.client.Minecraft.getInstance().getEntityModels();
            var roots = (Map<net.minecraft.client.model.geom.ModelLayerLocation,
                net.minecraft.client.model.geom.builders.LayerDefinition>) field(modelSet, "roots");
            exportEntries(target, roots.entrySet());
        } catch (Exception e) {
            CCTV.LOGGER.error("Entity geometry export failed", e);
        }
    }

    private static void exportEntries(Path target,
                                      Iterable<? extends Map.Entry<net.minecraft.client.model.geom.ModelLayerLocation,
                                          net.minecraft.client.model.geom.builders.LayerDefinition>> entries) {
        try {
            var layers = new JsonObject();
            for (var entry : entries) {
                var location = entry.getKey();
                var root = entry.getValue().bakeRoot();
                layers.add(location.getModel() + "#" + location.getLayer(), exportPart(root));
            }
            var out = new JsonObject();
            out.add("layers", layers);
            try (var writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(target)), StandardCharsets.UTF_8)) {
                writer.write(out.toString());
            }
            CCTV.LOGGER.info("Exported entity geometry for {} layers to {}", layers.size(), target);
        } catch (Exception e) {
            CCTV.LOGGER.error("Entity geometry export failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static JsonObject exportPart(ModelPart part) throws Exception {
        var json = new JsonObject();
        json.addProperty("x", part.x);
        json.addProperty("y", part.y);
        json.addProperty("z", part.z);
        json.addProperty("xr", part.xRot);
        json.addProperty("yr", part.yRot);
        json.addProperty("zr", part.zRot);

        // Polygon and Vertex are package-private in ModelPart: reflection all the way down.
        var quads = new JsonArray();
        for (var cube : (List<?>) field(part, "cubes")) {
            for (var polygon : (Object[]) field(cube, "polygons")) {
                var quad = new JsonArray();
                for (var vertex : (Object[]) field(polygon, "vertices")) {
                    var pos = (org.joml.Vector3f) field(vertex, "pos");
                    var values = new JsonArray();
                    values.add(round(pos.x));
                    values.add(round(pos.y));
                    values.add(round(pos.z));
                    values.add(round((Float) field(vertex, "u")));
                    values.add(round((Float) field(vertex, "v")));
                    quad.add(values);
                }
                quads.add(quad);
            }
        }
        if (!quads.isEmpty()) json.add("quads", quads);

        var children = new JsonObject();
        for (var entry : ((Map<String, ModelPart>) field(part, "children")).entrySet()) {
            children.add(entry.getKey(), exportPart(entry.getValue()));
        }
        if (children.size() > 0) json.add("children", children);
        return json;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static float round(float value) {
        return Math.round(value * 1000) / 1000f;
    }
}
