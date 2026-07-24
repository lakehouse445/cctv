package gg.lakehouse.cctv.camera;

/**
 * One textured quad for the camera raycaster: four vertices (block-local for
 * blocks, entity-relative for entities), per-vertex texture coordinates and
 * the pixels to sample. tintIndex >= 0 asks the block color handlers;
 * TINT_WATER is biome water color. alphaOverride forces texel alpha (-1 keeps
 * the texture's own). colorMul is a baked RGB multiplier (vertex color, dye,
 * item tint); 0xFFFFFF means none. texelDensity (texels per world unit) picks
 * the mip level when sampled at a distance.
 */
public record TexturedQuad(float[] xs, float[] ys, float[] zs, float[] us, float[] vs,
                           TexturePixels texture, int tintIndex, int alphaOverride, int colorMul,
                           float texelDensity, float nx, float ny, float nz) {
    public static final int TINT_NONE = -1;
    public static final int TINT_WATER = -2;

    public static TexturedQuad of(float[] xs, float[] ys, float[] zs, float[] us, float[] vs,
                                  TexturePixels texture, int tintIndex, int alphaOverride) {
        return ofColored(xs, ys, zs, us, vs, texture, tintIndex, alphaOverride, 0xFFFFFF);
    }

    public static TexturedQuad ofColored(float[] xs, float[] ys, float[] zs, float[] us, float[] vs,
                                         TexturePixels texture, int tintIndex, int alphaOverride, int colorMul) {
        float ax = xs[1] - xs[0];
        float ay = ys[1] - ys[0];
        float az = zs[1] - zs[0];
        float bx = xs[2] - xs[0];
        float by = ys[2] - ys[0];
        float bz = zs[2] - zs[0];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1e-6f) {
            nx /= length;
            ny /= length;
            nz /= length;
        }

        int texW = texture.width();
        int texH = Math.min(texture.width(), texture.height());
        float density = Math.max(
            edgeDensity(ax, ay, az, (us[1] - us[0]) * texW, (vs[1] - vs[0]) * texH),
            edgeDensity(bx, by, bz, (us[2] - us[0]) * texW, (vs[2] - vs[0]) * texH));
        return new TexturedQuad(xs, ys, zs, us, vs, texture, tintIndex, alphaOverride, colorMul, density, nx, ny, nz);
    }

    private static float edgeDensity(float wx, float wy, float wz, float du, float dv) {
        float world = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (world < 1e-5f) return 0;
        return (float) Math.sqrt(du * du + dv * dv) / world;
    }
}
