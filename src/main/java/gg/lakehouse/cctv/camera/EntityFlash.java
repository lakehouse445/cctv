package gg.lakehouse.cctv.camera;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;

import java.util.List;

/**
 * Vanilla's damage overlays for captured entities: the red hurt flash while
 * hurtTime runs, and a creeper's white strobe as it swells. Applied in
 * place over a captured quad list, before held items are added — vanilla
 * leaves those unflashed.
 */
public final class EntityFlash {
    private static final TexturePixels WHITE = TexturePixels.solid(0xFFFFFF);
    private static final int HURT_TINT = 0xFF6666;

    private EntityFlash() {
    }

    public static void apply(List<TexturedQuad> quads, LivingEntity entity) {
        if (entity instanceof Creeper creeper) {
            float swell = creeper.getSwelling(1);
            if (swell > 0 && (int) (swell * 10) % 2 != 0) {
                for (int i = 0; i < quads.size(); i++) {
                    var quad = quads.get(i);
                    quads.set(i, new TexturedQuad(quad.xs(), quad.ys(), quad.zs(), quad.us(), quad.vs(),
                        WHITE, TexturedQuad.TINT_NONE, 255, 0xFFFFFF, 0,
                        quad.nx(), quad.ny(), quad.nz()));
                }
                return;
            }
        }
        if (entity.hurtTime > 0) {
            for (int i = 0; i < quads.size(); i++) {
                var quad = quads.get(i);
                quads.set(i, new TexturedQuad(quad.xs(), quad.ys(), quad.zs(), quad.us(), quad.vs(),
                    quad.texture(), quad.tintIndex(), quad.alphaOverride(),
                    mul(quad.colorMul(), HURT_TINT), quad.texelDensity(),
                    quad.nx(), quad.ny(), quad.nz()));
            }
        }
    }

    private static int mul(int a, int b) {
        int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
        int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
        int bl = (a & 0xFF) * (b & 0xFF) / 255;
        return (r << 16) | (g << 8) | bl;
    }
}
