package gg.lakehouse.cctv.camera;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Supplies real model geometry and textures for blocks. Only available where
 * client assets exist (singleplayer / LAN); on a dedicated server the camera
 * falls back to map-color rendering.
 */
public interface BlockAppearanceProvider {
    /** Cached, block-local quads for this state; empty means nothing to render. */
    List<TexturedQuad> quads(BlockState state);

    /** The state's random position offset (flowers, grass). */
    Vec3 offset(BlockState state, ServerLevel level, BlockPos pos);

    /** Resolves a quad's tint index to an RGB multiplier at this position. */
    int tint(BlockState state, ServerLevel level, BlockPos pos, int tintIndex);

    /**
     * Position-dependent geometry that can't live in the per-state cache:
     * chest lids that stand open while in use, sign text. Empty by default.
     */
    default List<TexturedQuad> dynamicQuads(BlockState state, ServerLevel level, BlockPos pos) {
        return List.of();
    }

    /** Loads a texture by short name ("minecraft:font/ascii"), or null when unavailable. */
    @javax.annotation.Nullable
    default TexturePixels texture(String name) {
        return null;
    }
}
