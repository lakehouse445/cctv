package gg.lakehouse.cctv.camera;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Boats (every wood, rafts, chest variants) and minecarts (every kind,
 * including the block displayed inside — chests, hoppers, TNT) from the
 * geometry pack, shared by both camera pipelines. Emitted entity-relative
 * and self-seated on the entity origin, so authoring conventions can't
 * misplace them.
 */
public final class VehicleAppearances {
    private VehicleAppearances() {
    }

    @Nullable
    public static List<TexturedQuad> capture(Entity entity, Function<String, TexturePixels> textures,
                                             @Nullable Function<BlockState, List<TexturedQuad>> blockQuads) {
        if (entity instanceof Boat boat) return captureBoat(boat, textures);
        if (entity instanceof AbstractMinecart minecart) return captureMinecart(minecart, textures, blockQuads);
        return null;
    }

    private static List<TexturedQuad> captureBoat(Boat boat, Function<String, TexturePixels> textures) {
        var wood = boat.getVariant().getName();
        boolean raft = boat.getVariant() == Boat.Type.BAMBOO;
        boolean chest = boat instanceof ChestBoat;
        var layer = "minecraft:" + (chest ? (raft ? "chest_raft/" : "chest_boat/") : (raft ? "raft/" : "boat/")) + wood + "#main";
        var texture = "minecraft:entity/" + (chest ? "chest_boat/" : "boat/") + wood;
        var part = GeometryPack.layers().get(layer);
        if (part == null) return null;
        var out = new ArrayList<TexturedQuad>();
        var matrix = new Matrix4f()
            .rotateY((float) Math.toRadians(180 - boat.getYRot()))
            .scale(-1, -1, 1);
        GeometryPack.emit(out, part, matrix, textures.apply(texture), 0xFFFFFF);
        seat(out);
        return out;
    }

    private static List<TexturedQuad> captureMinecart(AbstractMinecart minecart,
                                                      Function<String, TexturePixels> textures,
                                                      @Nullable Function<BlockState, List<TexturedQuad>> blockQuads) {
        var part = GeometryPack.layers().get("minecraft:minecart#main");
        if (part == null) return null;
        var out = new ArrayList<TexturedQuad>();
        float yaw = 180 - minecart.getYRot();
        var matrix = new Matrix4f()
            .rotateY((float) Math.toRadians(yaw))
            .scale(-1, -1, 1);
        GeometryPack.emit(out, part, matrix, textures.apply("minecraft:entity/minecart"), 0xFFFFFF);
        seat(out);

        var display = minecart.getDisplayBlockState();
        if (blockQuads != null && !display.isAir()) {
            var content = blockQuads.apply(display);
            if ((content == null || content.isEmpty()) && BlockEntityAppearances.isDynamic(display)) {
                // Chests moved to the per-position dynamic channel, which
                // cart cargo never passes through: build one directly.
                var chest = new ArrayList<TexturedQuad>();
                BlockEntityAppearances.buildChest(chest, display, false, textures);
                content = chest;
            }
            if (content != null && !content.isEmpty()) {
                float scale = 0.75f;
                float lift = minecart.getDisplayOffset() / 16f * scale;
                var carted = new Matrix4f()
                    .rotateY((float) Math.toRadians(yaw))
                    .translate(0, lift, 0)
                    .scale(scale)
                    .translate(-0.5f, 0, -0.5f);
                var position = new org.joml.Vector3f();
                for (var quad : content) {
                    var xs = new float[4];
                    var ys = new float[4];
                    var zs = new float[4];
                    for (int i = 0; i < 4; i++) {
                        position.set(quad.xs()[i], quad.ys()[i], quad.zs()[i]);
                        carted.transformPosition(position);
                        xs[i] = position.x;
                        ys[i] = position.y;
                        zs[i] = position.z;
                    }
                    out.add(TexturedQuad.ofColored(xs, ys, zs, quad.us().clone(), quad.vs().clone(),
                        quad.texture(), TexturedQuad.TINT_NONE, quad.alphaOverride(), quad.colorMul()));
                }
            }
        }
        return out;
    }

    /** Centers horizontally on the entity origin with the hull bottom at y 0. */
    private static void seat(List<TexturedQuad> quads) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (var quad : quads) {
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, quad.xs()[i]);
                maxX = Math.max(maxX, quad.xs()[i]);
                minY = Math.min(minY, quad.ys()[i]);
                minZ = Math.min(minZ, quad.zs()[i]);
                maxZ = Math.max(maxZ, quad.zs()[i]);
            }
        }
        float dx = -(minX + maxX) / 2;
        float dy = -minY;
        float dz = -(minZ + maxZ) / 2;
        for (var quad : quads) {
            for (int i = 0; i < 4; i++) {
                quad.xs()[i] += dx;
                quad.ys()[i] += dy;
                quad.zs()[i] += dz;
            }
        }
    }
}
