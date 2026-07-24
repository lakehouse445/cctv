package gg.lakehouse.cctv.camera;

import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Captures an entity's real model, posed for this frame, as entity-relative
 * textured quads. Covers living entities plus vehicles (boats, minecarts).
 * Null when the entity has no capturable model; the camera then falls back
 * to the box figure.
 */
public interface EntityAppearanceProvider {
    @Nullable
    List<TexturedQuad> capture(Entity entity);
}
