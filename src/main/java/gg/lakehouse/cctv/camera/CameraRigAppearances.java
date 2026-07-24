package gg.lakehouse.cctv.camera;

import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A camera's moving parts as seen by other cameras: the same arm and head
 * the block-entity renderer poses, placed with the live yaw and pitch. The
 * base bakes from the blockstate like any block; this supplies the rest.
 * Pivots match the model rig and must stay in step with the client renderer.
 */
public final class CameraRigAppearances {
    public static final String ARM_MODEL = "cctv:block/camera_arm";
    public static final String HEAD_MODEL = "cctv:block/camera_head";

    /** The pan post's vertical axis: the post geometry's true center. */
    private static final float PAN_X = 7.98075f / 16;
    private static final float PAN_Z = 7.81548f / 16;
    /** The tilt joint, from the model rig. */
    private static final float TILT_Y = 6.5f / 16;
    private static final float TILT_Z = 8.36548f / 16;
    /** Wall mount: moves the upright head to the tip of the horizontal post. */
    private static final float WALL_HEAD_Y = 1.8655f / 16;
    private static final float WALL_HEAD_Z = 1.1345f / 16;

    private CameraRigAppearances() {
    }

    /** Arm and head quads in block space; {@code models} bakes a model with no rotation applied. */
    public static List<TexturedQuad> build(BlockState state, CameraBlockEntity camera,
                                           Function<String, List<TexturedQuad>> models) {
        var out = new ArrayList<TexturedQuad>();
        if (!state.hasProperty(HorizontalDirectionalBlock.FACING) || !state.hasProperty(CameraBlock.FACE)) return out;
        int facingYaw = switch (state.getValue(HorizontalDirectionalBlock.FACING)) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
        var face = state.getValue(CameraBlock.FACE);
        boolean ceiling = face == AttachFace.CEILING;
        // The blockstate y rotation is clockwise from above.
        var facing = new Matrix4f()
            .translate(0.5f, 0, 0.5f)
            .rotateY((float) Math.toRadians(-facingYaw))
            .translate(-0.5f, 0, -0.5f);

        // Arm (the pan post): reoriented per mount, swiveling with yaw about
        // its own axis inside that space.
        var arm = new Matrix4f(facing);
        mount(arm, face);
        arm.translate(PAN_X, 0, PAN_Z)
            .rotateY((float) Math.toRadians(-camera.getYaw()))
            .translate(-PAN_X, 0, -PAN_Z);
        transformed(out, models.apply(ARM_MODEL), arm);

        // Head. Floor/wall: upright, at the post tip. Ceiling: the whole
        // unit hangs flipped like a pendant camera — the flip mirrors the
        // rig's axes, so yaw and pitch negate to keep the lens on the ray.
        var head = new Matrix4f(facing);
        if (ceiling) {
            mount(head, face);
        } else if (face == AttachFace.WALL) {
            head.translate(0, WALL_HEAD_Y, WALL_HEAD_Z);
        }
        head.translate(PAN_X, 0, PAN_Z)
            .rotateY((float) Math.toRadians(ceiling ? camera.getYaw() : -camera.getYaw()))
            .translate(-PAN_X, 0, -PAN_Z)
            .translate(PAN_X, TILT_Y, TILT_Z)
            .rotateX((float) Math.toRadians(ceiling ? -camera.getPitch() : camera.getPitch()))
            .translate(-PAN_X, -TILT_Y, -TILT_Z);
        transformed(out, models.apply(HEAD_MODEL), head);
        return out;
    }

    /** Reorients the rig per mount: flipped under the ceiling, out from the wall. */
    private static void mount(Matrix4f matrix, AttachFace face) {
        switch (face) {
            case CEILING -> matrix.translate(0.5f, 0.5f, 0.5f)
                .rotateX((float) Math.toRadians(180))
                .rotateY((float) Math.toRadians(180))
                .translate(-0.5f, -0.5f, -0.5f);
            case WALL -> matrix.translate(0.5f, 0.5f, 0.5f)
                .rotateX((float) Math.toRadians(-90))
                .translate(-0.5f, -0.5f, -0.5f);
            default -> {
            }
        }
    }

    private static void transformed(List<TexturedQuad> out, List<TexturedQuad> quads, Matrix4f matrix) {
        var position = new Vector3f();
        for (var quad : quads) {
            var xs = new float[4];
            var ys = new float[4];
            var zs = new float[4];
            for (int i = 0; i < 4; i++) {
                position.set(quad.xs()[i], quad.ys()[i], quad.zs()[i]);
                matrix.transformPosition(position);
                xs[i] = position.x;
                ys[i] = position.y;
                zs[i] = position.z;
            }
            out.add(TexturedQuad.ofColored(xs, ys, zs, quad.us().clone(), quad.vs().clone(),
                quad.texture(), quad.tintIndex(), quad.alphaOverride(), quad.colorMul()));
        }
    }
}
