package gg.lakehouse.cctv.camera;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renderer-drawn blocks rendered from the geometry pack. Static shapes go
 * through {@link #build}; anything whose look depends on live block-entity
 * state — chest lids, shulker lids, banner patterns, player-head skins,
 * blocks mid-piston-push, sign text — goes through {@link #dynamic}.
 * Placement self-calibrates: emitted geometry is measured and seated in the
 * block, so authoring-space conventions cannot misplace a model.
 */
public final class BlockEntityAppearances {
    private static final Map<String, TexturePixels> BANNER_CACHE = TextureLru.create(256);

    private BlockEntityAppearances() {
    }

    /** Blocks whose camera geometry depends on live block-entity state. */
    public static boolean isDynamic(BlockState state) {
        var block = state.getBlock();
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST
            || block instanceof ShulkerBoxBlock
            || block instanceof BannerBlock || block instanceof WallBannerBlock
            || block == Blocks.MOVING_PISTON || block == Blocks.BELL
            || (block instanceof AbstractSkullBlock skull && skull.getType() == SkullBlock.Types.PLAYER);
    }

    /** The per-position channel: lids, patterns, skins, moving blocks, sign text. */
    public static List<TexturedQuad> dynamic(BlockState state, ServerLevel level, BlockPos pos,
                                             Function<String, TexturePixels> textures,
                                             Function<BlockState, List<TexturedQuad>> blockQuads) {
        var out = new ArrayList<TexturedQuad>();
        var block = state.getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            buildChest(out, state, ChestOpenness.isOpen(level, level.getBlockEntity(pos)), textures);
        } else if (block instanceof ShulkerBoxBlock shulkerBox) {
            float progress = level.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity shulker
                ? shulker.getProgress(1) : 0;
            buildShulker(out, shulkerBox, progress, textures);
        } else if (block instanceof BannerBlock || block instanceof WallBannerBlock) {
            buildBanner(out, state, level, pos, textures);
        } else if (block instanceof AbstractSkullBlock skullBlock && skullBlock.getType() == SkullBlock.Types.PLAYER) {
            var profile = level.getBlockEntity(pos) instanceof SkullBlockEntity skull ? skull.getOwnerProfile() : null;
            var skin = ProfileSkins.get(profile, textures);
            fitted(out, skullLayerFor(state), skin, 0xFFFFFF, flip(-skullYaw(state)), 0);
        } else if (block == Blocks.MOVING_PISTON) {
            buildMovingPiston(out, level, pos, blockQuads);
        } else if (block == Blocks.BELL) {
            // ticks and shaking are public fields kept up to date by the
            // server tick, so the swing reads without reflection.
            float swing = 0;
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BellBlockEntity bell
                && bell.shaking) {
                float time = bell.ticks;
                swing = (float) Math.sin(time / Math.PI) / (4 + time / 3);
            }
            var part = GeometryPack.layers().get("minecraft:bell#main");
            if (part != null) {
                var emitted = new ArrayList<TexturedQuad>();
                GeometryPack.emit(emitted, part, "root", new Matrix4f(),
                    textures.apply("minecraft:entity/bell/bell_body"), 0xFFFFFF, Map.of("bell_body", swing));
                fitToBlock(emitted, Float.NaN);
                out.addAll(emitted);
            }
        } else if (block instanceof SignBlock) {
            return SignTextAppearances.build(state, level, pos, textures, blockQuads.apply(state));
        }
        return out;
    }

    /** Static geometry for renderer-drawn blocks; false when this block isn't covered. */
    public static boolean build(List<TexturedQuad> out, BlockState state, Function<String, TexturePixels> textures) {
        var layers = GeometryPack.layers();
        if (layers.isEmpty()) return false;
        var block = state.getBlock();

        if (block == Blocks.ENDER_CHEST) {
            return buildChest(out, state, false, textures);
        }

        if (block instanceof BedBlock) {
            var color = bedColor(block);
            var head = state.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD;
            var yaw = state.getValue(BedBlock.FACING).toYRot();
            var matrix = new Matrix4f()
                .translate(0.5f, 0.5f, 0.5f)
                .rotateY((float) Math.toRadians(-yaw + 180))
                .rotateX((float) Math.toRadians(90))
                .translate(-0.5f, -0.5f, -0.5f);
            return fitted(out, head ? "minecraft:bed_head#main" : "minecraft:bed_foot#main",
                textures.apply("minecraft:entity/bed/" + (color == null ? "red" : color)), 0xFFFFFF, matrix, 0);
        }

        // Sign models are y-down entity-style layers (vanilla renders them
        // with a negative-y scale): flip, then seat by sign kind.
        if (block instanceof StandingSignBlock sign) {
            int rotation = state.getValue(BlockStateProperties.ROTATION_16);
            return fitted(out, "minecraft:sign/" + woodOf(sign) + "#main",
                textures.apply("minecraft:entity/signs/" + woodOf(sign)), 0xFFFFFF,
                flip(rotation * 22.5f).scale(2f / 3), 0);
        }
        if (block instanceof WallSignBlock sign) {
            // Wall signs share the standing model minus the stick part.
            var part = GeometryPack.layers().get("minecraft:sign/" + woodOf(sign) + "#main");
            var board = part == null ? null : part.children().get("sign");
            if (board == null) return false;
            var emitted = new ArrayList<TexturedQuad>();
            GeometryPack.emit(emitted, board, "sign",
                flip(state.getValue(WallSignBlock.FACING).toYRot()).scale(2f / 3),
                textures.apply("minecraft:entity/signs/" + woodOf(sign)), 0xFFFFFF, Map.of());
            if (emitted.isEmpty()) return false;
            fitToBlock(emitted, 0.28f);
            // Fitting centers the board mid-block: push it back against the
            // wall (the face opposite the sign's facing).
            var facing = state.getValue(WallSignBlock.FACING);
            for (var quad : emitted) {
                for (int i = 0; i < 4; i++) {
                    quad.xs()[i] -= facing.getStepX() * 0.448f;
                    quad.zs()[i] -= facing.getStepZ() * 0.448f;
                }
            }
            out.addAll(emitted);
            return true;
        }
        if (block instanceof CeilingHangingSignBlock sign) {
            int rotation = state.getValue(BlockStateProperties.ROTATION_16);
            return fitted(out, "minecraft:hanging_sign/" + woodOf(sign) + "#main",
                textures.apply("minecraft:entity/signs/hanging/" + woodOf(sign)), 0xFFFFFF,
                flip(rotation * 22.5f), Float.NaN);
        }
        if (block instanceof WallHangingSignBlock sign) {
            return fitted(out, "minecraft:hanging_sign/" + woodOf(sign) + "#main",
                textures.apply("minecraft:entity/signs/hanging/" + woodOf(sign)), 0xFFFFFF,
                flip(state.getValue(WallHangingSignBlock.FACING).toYRot()), Float.NaN);
        }

        if (block instanceof AbstractSkullBlock skull && skull.getType() instanceof SkullBlock.Types type) {
            var texture = skullTexture(type);
            if (texture == null) return false;
            return fitted(out, skullLayerFor(state), textures.apply(texture), 0xFFFFFF,
                flip(-skullYaw(state)), 0);
        }

        if (block == Blocks.CONDUIT) {
            return fitted(out, "minecraft:conduit#shell", textures.apply("minecraft:entity/conduit/base"),
                0xFFFFFF, flip(0), 0.25f);
        }
        if (block == Blocks.DECORATED_POT) {
            return fitted(out, "minecraft:decorated_pot_base#main",
                textures.apply("minecraft:entity/decorated_pot/decorated_pot_base"), 0xFFFFFF, new Matrix4f(), 0);
        }

        return false;
    }

    /** Geometry ADDED to blocks that also have baked models: the books. */
    public static void appendExtras(List<TexturedQuad> out, BlockState state, Function<String, TexturePixels> textures) {
        if (state.is(Blocks.ENCHANTING_TABLE)) {
            prop(out, "minecraft:book#main", textures.apply("minecraft:entity/enchanting_table_book"),
                0, 80, 0.75f, 0.5f, 0.78f, 0.5f);
        } else if (state.getBlock() instanceof LecternBlock && state.hasProperty(LecternBlock.HAS_BOOK)
            && state.getValue(LecternBlock.HAS_BOOK)) {
            prop(out, "minecraft:book#main", textures.apply("minecraft:entity/enchanting_table_book"),
                state.getValue(LecternBlock.FACING).toYRot(), 70, 0.6f,
                0.5f, 0.95f, 0.5f);
        }
    }

    // === Chest family ===

    /** Chests are authored in block space with facing rotation; lid and latch swing open on demand. */
    public static boolean buildChest(List<TexturedQuad> out, BlockState state, boolean open,
                                     Function<String, TexturePixels> textures) {
        var block = state.getBlock();
        var type = block == Blocks.ENDER_CHEST ? ChestType.SINGLE : state.getValue(ChestBlock.TYPE);
        var kind = block == Blocks.CHEST ? "normal" : block == Blocks.TRAPPED_CHEST ? "trapped" : "ender";
        var layer = switch (type) {
            case SINGLE -> "minecraft:chest#main";
            case LEFT -> "minecraft:double_chest_left#main";
            case RIGHT -> "minecraft:double_chest_right#main";
        };
        var texture = "minecraft:entity/chest/" + (type == ChestType.SINGLE ? kind
            : kind + (type == ChestType.LEFT ? "_left" : "_right"));
        var facing = block == Blocks.ENDER_CHEST
            ? state.getValue(EnderChestBlock.FACING) : state.getValue(ChestBlock.FACING);
        var part = GeometryPack.layers().get(layer);
        if (part == null) return false;
        var matrix = new Matrix4f()
            .translate(0.5f, 0.5f, 0.5f)
            .rotateY((float) Math.toRadians(-facing.toYRot()))
            .translate(-0.5f, -0.5f, -0.5f);
        var lidSwing = open ? (float) Math.toRadians(-80) : 0f;
        GeometryPack.emit(out, part, "root", matrix, textures.apply(texture), 0xFFFFFF,
            open ? Map.of("lid", lidSwing, "lock", lidSwing) : Map.of());
        return !out.isEmpty();
    }

    private static void buildShulker(List<TexturedQuad> out, ShulkerBoxBlock shulkerBox, float progress,
                                     Function<String, TexturePixels> textures) {
        var part = GeometryPack.layers().get("minecraft:shulker#main");
        if (part == null) return;
        var color = shulkerBox.getColor();
        var texture = textures.apply("minecraft:entity/shulker/shulker"
            + (color == null ? "" : "_" + color.getName()));
        var flip = new Matrix4f().scale(1, -1, -1);
        var emitted = new ArrayList<TexturedQuad>();
        for (var child : part.children().entrySet()) {
            var matrix = child.getKey().contains("lid") || child.getKey().contains("head")
                ? new Matrix4f().translate(0, 0.5f * progress, 0).mul(flip) : new Matrix4f(flip);
            GeometryPack.emit(emitted, child.getValue(), child.getKey(), matrix, texture, 0xFFFFFF, Map.of());
        }
        fitToBlock(emitted, 0);
        out.addAll(emitted);
    }

    // === Banners with composited patterns ===

    private static void buildBanner(List<TexturedQuad> out, BlockState state, ServerLevel level, BlockPos pos,
                                    Function<String, TexturePixels> textures) {
        if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity banner)) return;
        var composite = bannerComposite(banner, textures);
        boolean wall = state.getBlock() instanceof WallBannerBlock;
        float yaw = wall ? state.getValue(WallBannerBlock.FACING).toYRot()
            : state.getValue(BlockStateProperties.ROTATION_16) * 22.5f;
        // Banner models are y-down entity-style layers like signs.
        fitted(out, "minecraft:banner#main", composite, 0xFFFFFF,
            flip(yaw).scale(2f / 3), wall ? Float.NaN : 0);
    }

    private static TexturePixels bannerComposite(BannerBlockEntity banner, Function<String, TexturePixels> textures) {
        var patterns = banner.getPatterns();
        var keyBuilder = new StringBuilder();
        for (var pair : patterns) {
            pair.getFirst().unwrapKey().ifPresent(key -> keyBuilder.append(key.location()).append('/'));
            keyBuilder.append(pair.getSecond().getId()).append(';');
        }
        return BANNER_CACHE.computeIfAbsent(keyBuilder.toString(), key -> {
            var base = textures.apply("minecraft:entity/banner_base");
            var argb = base.argb().clone();
            for (var pair : patterns) {
                var location = pair.getFirst().unwrapKey().map(k -> k.location()).orElse(null);
                if (location == null) continue;
                var mask = textures.apply(location.getNamespace() + ":entity/banner/" + location.getPath());
                if (mask.width() != base.width() || mask.height() != base.height()) continue;
                var colors = pair.getSecond().getTextureDiffuseColors();
                for (int i = 0; i < argb.length; i++) {
                    int m = mask.argb()[i];
                    int alpha = m >>> 24;
                    if (alpha < 16) continue;
                    float brightness = ((m >> 16) & 0xFF) / 255f;
                    float factor = alpha / 255f;
                    int r = (int) (((argb[i] >> 16) & 0xFF) * (1 - factor) + colors[0] * 255 * brightness * factor);
                    int g = (int) (((argb[i] >> 8) & 0xFF) * (1 - factor) + colors[1] * 255 * brightness * factor);
                    int b = (int) ((argb[i] & 0xFF) * (1 - factor) + colors[2] * 255 * brightness * factor);
                    argb[i] = (argb[i] & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
            }
            return new TexturePixels(argb, base.width(), base.height());
        });
    }

    // === Moving pistons ===

    private static void buildMovingPiston(List<TexturedQuad> out, ServerLevel level, BlockPos pos,
                                          Function<BlockState, List<TexturedQuad>> blockQuads) {
        if (!(level.getBlockEntity(pos) instanceof PistonMovingBlockEntity piston)) return;
        var moved = piston.getMovedState();
        if (moved.isAir() || moved.is(Blocks.MOVING_PISTON)) return;
        var quads = blockQuads.apply(moved);
        if (quads == null || quads.isEmpty()) return;
        float progress = piston.getProgress(1);
        float factor = piston.isExtending() ? progress - 1 : 1 - progress;
        var direction = piston.getDirection();
        float dx = direction.getStepX() * factor;
        float dy = direction.getStepY() * factor;
        float dz = direction.getStepZ() * factor;
        for (var quad : quads) {
            var xs = new float[4];
            var ys = new float[4];
            var zs = new float[4];
            for (int i = 0; i < 4; i++) {
                xs[i] = quad.xs()[i] + dx;
                ys[i] = quad.ys()[i] + dy;
                zs[i] = quad.zs()[i] + dz;
            }
            out.add(TexturedQuad.ofColored(xs, ys, zs, quad.us().clone(), quad.vs().clone(),
                quad.texture(), quad.tintIndex(), quad.alphaOverride(), quad.colorMul()));
        }
    }

    // === Placement helpers ===

    /** Y rotation about the block center, block-space models. */
    private static Matrix4f spin(float degrees) {
        return new Matrix4f()
            .translate(0.5f, 0.5f, 0.5f)
            .rotateY((float) Math.toRadians(degrees))
            .translate(-0.5f, -0.5f, -0.5f);
    }

    /** Entity-style (y-down) layers: mirror to world orientation, then spin. */
    private static Matrix4f flip(float yawDegrees) {
        return new Matrix4f().rotateY((float) Math.toRadians(-yawDegrees)).scale(1, -1, -1);
    }

    private static boolean fitted(List<TexturedQuad> out, String layerKey, TexturePixels texture,
                                  int colorMul, Matrix4f matrix, float bottomY) {
        var part = GeometryPack.layers().get(layerKey);
        if (part == null) return false;
        var emitted = new ArrayList<TexturedQuad>();
        GeometryPack.emit(emitted, part, matrix, texture, colorMul);
        if (emitted.isEmpty()) return false;
        fitToBlock(emitted, bottomY);
        out.addAll(emitted);
        return true;
    }

    /** A prop placed inside a block: fitted to the floor, then scaled and moved to its spot. */
    private static void prop(List<TexturedQuad> out, String layerKey, TexturePixels texture,
                             float yawDegrees, float tiltDegrees, float scale, float x, float y, float z) {
        var part = GeometryPack.layers().get(layerKey);
        if (part == null) return;
        var emitted = new ArrayList<TexturedQuad>();
        var matrix = new Matrix4f()
            .rotateY((float) Math.toRadians(-yawDegrees))
            .rotateX((float) Math.toRadians(tiltDegrees))
            .scale(1, -1, -1);
        GeometryPack.emit(emitted, part, matrix, texture, 0xFFFFFF);
        if (emitted.isEmpty()) return;
        fitToBlock(emitted, 0);
        for (var quad : emitted) {
            for (int i = 0; i < 4; i++) {
                quad.xs()[i] = (quad.xs()[i] - 0.5f) * scale + x;
                quad.ys()[i] = quad.ys()[i] * scale + (y - 0.5f * scale);
                quad.zs()[i] = (quad.zs()[i] - 0.5f) * scale + z;
            }
        }
        out.addAll(emitted);
    }

    /** Seats emitted quads centered in the block: bottom at bottomY, or hung from the block top when NaN. */
    private static void fitToBlock(List<TexturedQuad> quads, float bottomY) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (var quad : quads) {
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, quad.xs()[i]);
                maxX = Math.max(maxX, quad.xs()[i]);
                minY = Math.min(minY, quad.ys()[i]);
                maxY = Math.max(maxY, quad.ys()[i]);
                minZ = Math.min(minZ, quad.zs()[i]);
                maxZ = Math.max(maxZ, quad.zs()[i]);
            }
        }
        float dx = 0.5f - (minX + maxX) / 2;
        float dy = Float.isNaN(bottomY) ? 1 - maxY : bottomY - minY;
        float dz = 0.5f - (minZ + maxZ) / 2;
        for (var quad : quads) {
            for (int i = 0; i < 4; i++) {
                quad.xs()[i] += dx;
                quad.ys()[i] += dy;
                quad.zs()[i] += dz;
            }
        }
    }

    // === Lookups ===

    private static float skullYaw(BlockState state) {
        return state.getBlock() instanceof WallSkullBlock
            ? state.getValue(WallSkullBlock.FACING).toYRot()
            : state.getValue(SkullBlock.ROTATION) * 22.5f;
    }

    private static String skullLayerFor(BlockState state) {
        if (state.getBlock() instanceof AbstractSkullBlock skull && skull.getType() instanceof SkullBlock.Types type) {
            return switch (type) {
                case SKELETON -> "minecraft:skeleton_skull#main";
                case WITHER_SKELETON -> "minecraft:wither_skeleton_skull#main";
                case ZOMBIE -> "minecraft:zombie_head#main";
                case CREEPER -> "minecraft:creeper_head#main";
                case PLAYER -> "minecraft:player_head#main";
                case DRAGON -> "minecraft:dragon_skull#main";
                case PIGLIN -> "minecraft:piglin_head#main";
            };
        }
        return "minecraft:skeleton_skull#main";
    }

    private static String woodOf(SignBlock sign) {
        return sign.type().name();
    }

    @javax.annotation.Nullable
    private static String bedColor(net.minecraft.world.level.block.Block block) {
        var name = ForgeRegistries.BLOCKS.getKey(block);
        if (name == null) return null;
        var path = name.getPath();
        return path.endsWith("_bed") ? path.substring(0, path.length() - 4) : null;
    }

    @javax.annotation.Nullable
    private static String skullTexture(SkullBlock.Types type) {
        return switch (type) {
            case SKELETON -> "minecraft:entity/skeleton/skeleton";
            case WITHER_SKELETON -> "minecraft:entity/skeleton/wither_skeleton";
            case ZOMBIE -> "minecraft:entity/zombie/zombie";
            case CREEPER -> "minecraft:entity/creeper/creeper";
            case PLAYER -> "minecraft:entity/player/wide/steve";
            case DRAGON -> "minecraft:entity/enderdragon/dragon";
            case PIGLIN -> "minecraft:entity/piglin/piglin";
        };
    }
}
