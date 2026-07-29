package gg.lakehouse.cctv.camera;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * CC monitors on camera: their content is renderer-drawn and invisible to
 * the raycaster, so the camera shows the clean powered-off face whether the
 * monitor is on or not. Shared by the client and dedicated-server pipelines.
 */
public final class MonitorAppearances {
    private static final TexturePixels SCREEN = TexturePixels.solid(0x111111);

    private MonitorAppearances() {
    }

    public static boolean isMonitor(BlockState state) {
        var name = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return name != null && "computercraft".equals(name.getNamespace())
            && name.getPath().startsWith("monitor");
    }

    /**
     * Fixes both monitor problems at once. The model's textures are packed
     * sheets, so mips would blend bezel and screen regions into noise at a
     * distance — every quad samples at full resolution instead. And the
     * screen face is painted by the renderer even when blank, so the camera
     * would show bare casing — blinding white after night auto-gain; the
     * front face becomes the dark off screen, the bezel survives on the
     * other faces.
     */
    public static void apply(List<TexturedQuad> quads, BlockState state) {
        for (int i = 0; i < quads.size(); i++) quads.set(i, withoutMips(quads.get(i)));
        coverFace(quads, state);
    }

    /** Zero texel density keeps every sample at full resolution — no mip level is ever chosen. */
    private static TexturedQuad withoutMips(TexturedQuad quad) {
        return new TexturedQuad(quad.xs(), quad.ys(), quad.zs(), quad.us(), quad.vs(), quad.texture(),
            quad.tintIndex(), quad.alphaOverride(), quad.colorMul(), 0, quad.nx(), quad.ny(), quad.nz());
    }

    private static void coverFace(List<TexturedQuad> quads, BlockState state) {
        net.minecraft.core.Direction facing = null;
        for (var property : state.getProperties()) {
            if (property instanceof DirectionProperty direction && property.getName().equals("facing")) {
                facing = state.getValue(direction);
                break;
            }
        }
        if (facing == null) return;
        float fx = facing.getStepX();
        float fy = facing.getStepY();
        float fz = facing.getStepZ();
        quads.removeIf(quad -> quad.nx() * fx + quad.ny() * fy + quad.nz() * fz > 0.5f);
        var u = new float[]{0, 1, 1, 0};
        var v = new float[]{0, 0, 1, 1};
        switch (facing) {
            case NORTH -> quads.add(TexturedQuad.of(new float[]{0, 1, 1, 0}, new float[]{0, 0, 1, 1}, new float[]{0, 0, 0, 0}, u, v, SCREEN, TexturedQuad.TINT_NONE, 255));
            case SOUTH -> quads.add(TexturedQuad.of(new float[]{1, 0, 0, 1}, new float[]{0, 0, 1, 1}, new float[]{1, 1, 1, 1}, u, v, SCREEN, TexturedQuad.TINT_NONE, 255));
            case WEST -> quads.add(TexturedQuad.of(new float[]{0, 0, 0, 0}, new float[]{0, 0, 1, 1}, new float[]{1, 0, 0, 1}, u, v, SCREEN, TexturedQuad.TINT_NONE, 255));
            case EAST -> quads.add(TexturedQuad.of(new float[]{1, 1, 1, 1}, new float[]{0, 0, 1, 1}, new float[]{0, 1, 1, 0}, u, v, SCREEN, TexturedQuad.TINT_NONE, 255));
            case UP -> quads.add(TexturedQuad.of(new float[]{0, 1, 1, 0}, new float[]{1, 1, 1, 1}, new float[]{0, 0, 1, 1}, u, v, SCREEN, TexturedQuad.TINT_NONE, 255));
            case DOWN -> quads.add(TexturedQuad.of(new float[]{0, 1, 1, 0}, new float[]{0, 0, 0, 0}, new float[]{1, 1, 0, 0}, u, v, SCREEN, TexturedQuad.TINT_NONE, 255));
        }
    }
}
