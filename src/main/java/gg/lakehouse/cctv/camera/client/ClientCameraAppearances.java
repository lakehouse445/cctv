package gg.lakehouse.cctv.camera.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.camera.BlockAppearanceProvider;
import gg.lakehouse.cctv.camera.EntityAppearanceProvider;
import gg.lakehouse.cctv.camera.TexturePixels;
import gg.lakehouse.cctv.camera.TexturedQuad;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real block and entity appearance for the camera, backed by the client's
 * baked models, texture PNGs and entity model code. Covers render layers
 * (wool, armor, saddles...), real player skins (downloaded off-thread from
 * the profile), and held items posed on the hand. Loaded only on the client
 * dist; the integrated server thread reads these caches.
 */
public final class ClientCameraAppearances {
    /** Concurrent: the skin loader touches this from a background thread. */
    private static final Map<ResourceLocation, TexturePixels> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<BlockState, List<TexturedQuad>> BLOCK_QUADS = new HashMap<>();
    private static final Map<String, List<TexturedQuad>> MODEL_QUADS = new HashMap<>();
    private static final Map<RenderType, TexturePixels> LAYER_TEXTURES = new HashMap<>();
    private static final Map<UUID, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    private static final Set<UUID> SKINS_PENDING = ConcurrentHashMap.newKeySet();
    private static final TexturePixels MISSING = TexturePixels.solid(0xF800F8);
    private static final ResourceLocation STEVE = new ResourceLocation("textures/entity/player/wide/steve.png");
    private static final Direction[] QUAD_SIDES = {
        Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };
    private static PlayerModel<LivingEntity> widePlayerModel;
    private static PlayerModel<LivingEntity> slimPlayerModel;
    private static HumanoidModel<LivingEntity> innerArmorModel;
    private static HumanoidModel<LivingEntity> outerArmorModel;
    private static Field layersField;
    private static boolean layersFieldFailed;

    private record PlayerSkin(TexturePixels pixels, boolean slim) {
    }

    private ClientCameraAppearances() {
    }

    public static BlockAppearanceProvider blockProvider() {
        return new BlockProvider();
    }

    public static EntityAppearanceProvider entityProvider() {
        return new EntityProvider();
    }

    // === Blocks ===

    private static final class BlockProvider implements BlockAppearanceProvider {
        @Override
        public List<TexturedQuad> quads(BlockState state) {
            return BLOCK_QUADS.computeIfAbsent(state, ClientCameraAppearances::buildBlockQuads);
        }

        @Override
        public Vec3 offset(BlockState state, ServerLevel level, BlockPos pos) {
            return state.getOffset(level, pos);
        }

        @Override
        public TexturePixels texture(String name) {
            return shortTexture(name);
        }

        @Override
        public int tint(BlockState state, ServerLevel level, BlockPos pos, int tintIndex) {
            if (tintIndex == TexturedQuad.TINT_WATER) return level.getBiome(pos).value().getWaterColor();
            int color = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, tintIndex);
            return color == -1 ? 0xFFFFFF : color;
        }

        @Override
        public List<TexturedQuad> dynamicQuads(BlockState state, ServerLevel level, BlockPos pos) {
            if (state.getBlock() instanceof gg.lakehouse.cctv.camera.CameraBlock
                && level.getBlockEntity(pos) instanceof gg.lakehouse.cctv.camera.CameraBlockEntity camera) {
                return gg.lakehouse.cctv.camera.CameraRigAppearances.build(state, camera,
                    ClientCameraAppearances::standaloneModelQuads);
            }
            if (!gg.lakehouse.cctv.camera.BlockEntityAppearances.isDynamic(state)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.SignBlock)) {
                return List.of();
            }
            return gg.lakehouse.cctv.camera.BlockEntityAppearances.dynamic(state, level, pos,
                ClientCameraAppearances::shortTexture,
                dynamicState -> BLOCK_QUADS.computeIfAbsent(dynamicState, ClientCameraAppearances::buildBlockQuads));
        }
    }

    /** Standalone models registered with the model manager (the camera's arm and head). */
    private static List<TexturedQuad> standaloneModelQuads(String name) {
        return MODEL_QUADS.computeIfAbsent(name, key -> {
            var result = new ArrayList<TexturedQuad>();
            try {
                var model = Minecraft.getInstance().getModelManager().getModel(new ResourceLocation(key));
                var random = RandomSource.create(42);
                for (var side : QUAD_SIDES) {
                    for (var quad : model.getQuads(null, side, random)) {
                        result.add(convert(quad, null, 0xFFFFFF));
                    }
                }
            } catch (Exception e) {
                CCTV.LOGGER.warn("Camera failed to bake model {}", key, e);
            }
            return result;
        });
    }

    private static List<TexturedQuad> buildBlockQuads(BlockState state) {
        var result = new ArrayList<TexturedQuad>();
        // Rendered per-position through dynamicQuads instead.
        if (gg.lakehouse.cctv.camera.BlockEntityAppearances.isDynamic(state)) return result;
        try {
            var fluid = state.getFluidState();
            if (!(state.getBlock() instanceof LiquidBlock) && state.getRenderShape() == RenderShape.MODEL) {
                var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
                var random = RandomSource.create(42);
                for (var side : QUAD_SIDES) {
                    for (var quad : model.getQuads(state, side, random)) {
                        result.add(convert(quad, null, 0xFFFFFF));
                    }
                }
            }
            if (gg.lakehouse.cctv.camera.MonitorAppearances.isMonitor(state)) {
                gg.lakehouse.cctv.camera.MonitorAppearances.apply(result, state);
            }
            if (!fluid.isEmpty()) addFluid(result, fluid);
            gg.lakehouse.cctv.camera.BlockEntityAppearances.appendExtras(result, state,
                ClientCameraAppearances::shortTexture);
            if (result.isEmpty()) {
                gg.lakehouse.cctv.camera.BlockEntityAppearances.build(result, state,
                    ClientCameraAppearances::shortTexture);
            }
            // INVISIBLE shapes we don't cover (barriers, markers) stay unseen.
            if (result.isEmpty() && state.getRenderShape() != RenderShape.INVISIBLE) {
                addCollisionBoxes(result, state);
            }
        } catch (Exception e) {
            CCTV.LOGGER.warn("Camera failed to build quads for {}", state, e);
            result.clear();
            addCollisionBoxes(result, state);
        }
        return result;
    }

    /** Short texture names ("minecraft:entity/chest/normal") to loaded pixels, for the shared BER geometry. */
    private static TexturePixels shortTexture(String name) {
        int colon = name.indexOf(':');
        var namespace = colon < 0 ? "minecraft" : name.substring(0, colon);
        var path = colon < 0 ? name : name.substring(colon + 1);
        return textureFor(new ResourceLocation(namespace, "textures/" + path + ".png"));
    }

    private static TexturedQuad convert(BakedQuad quad, @Nullable Matrix4f transform, int colorMul) {
        var vertices = quad.getVertices();
        var sprite = quad.getSprite();
        float u0 = sprite.getU0();
        float uSpan = sprite.getU1() - u0;
        float v0 = sprite.getV0();
        float vSpan = sprite.getV1() - v0;
        var xs = new float[4];
        var ys = new float[4];
        var zs = new float[4];
        var us = new float[4];
        var vs = new float[4];
        var position = new Vector3f();
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            position.set(
                Float.intBitsToFloat(vertices[base]),
                Float.intBitsToFloat(vertices[base + 1]),
                Float.intBitsToFloat(vertices[base + 2]));
            if (transform != null) transform.transformPosition(position);
            xs[i] = position.x;
            ys[i] = position.y;
            zs[i] = position.z;
            us[i] = uSpan > 0 ? (Float.intBitsToFloat(vertices[base + 4]) - u0) / uSpan : 0;
            vs[i] = vSpan > 0 ? (Float.intBitsToFloat(vertices[base + 5]) - v0) / vSpan : 0;
        }
        var name = sprite.contents().name();
        var texture = textureFor(new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + ".png"));
        return TexturedQuad.ofColored(xs, ys, zs, us, vs, texture, quad.getTintIndex(), -1, colorMul);
    }

    private static void addFluid(List<TexturedQuad> result, FluidState fluid) {
        boolean water = fluid.is(FluidTags.WATER);
        var texture = textureFor(new ResourceLocation(
            water ? "textures/block/water_still.png" : "textures/block/lava_still.png"));
        float top = fluid.isSource() ? 0.875f : Mth.clamp(fluid.getOwnHeight(), 0.1f, 0.875f);
        addBox(result, 0, 0, 0, 1, top, 1, texture,
            water ? TexturedQuad.TINT_WATER : TexturedQuad.TINT_NONE, water ? 170 : 255);
    }

    /** Fallback for renderer-drawn blocks (chests, signs): solid map-color boxes from the collision shape. */
    private static void addCollisionBoxes(List<TexturedQuad> result, BlockState state) {
        try {
            int color = 0x808080;
            var mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (mapColor != null) color = mapColor.col;
            var texture = TexturePixels.solid(color);
            for (var box : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
                addBox(result, (float) box.minX, (float) box.minY, (float) box.minZ,
                    (float) box.maxX, (float) box.maxY, (float) box.maxZ, texture, TexturedQuad.TINT_NONE, 255);
            }
        } catch (Exception ignored) {
            // No collision shape available without a level; leave invisible.
        }
    }

    private static void addBox(List<TexturedQuad> result, float x1, float y1, float z1, float x2, float y2, float z2,
                               TexturePixels texture, int tintIndex, int alpha) {
        var u = new float[]{0, 1, 1, 0};
        var v = new float[]{0, 0, 1, 1};
        result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y1, y1, y1, y1}, new float[]{z1, z1, z2, z2}, u, v, texture, tintIndex, alpha));
        result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y2, y2, y2, y2}, new float[]{z1, z1, z2, z2}, u, v, texture, tintIndex, alpha));
        result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y1, y1, y2, y2}, new float[]{z1, z1, z1, z1}, u, v, texture, tintIndex, alpha));
        result.add(TexturedQuad.of(new float[]{x1, x2, x2, x1}, new float[]{y1, y1, y2, y2}, new float[]{z2, z2, z2, z2}, u, v, texture, tintIndex, alpha));
        result.add(TexturedQuad.of(new float[]{x1, x1, x1, x1}, new float[]{y1, y1, y2, y2}, new float[]{z1, z2, z2, z1}, u, v, texture, tintIndex, alpha));
        result.add(TexturedQuad.of(new float[]{x2, x2, x2, x2}, new float[]{y1, y1, y2, y2}, new float[]{z1, z2, z2, z1}, u, v, texture, tintIndex, alpha));
    }

    // === Entities ===

    private static final class EntityProvider implements EntityAppearanceProvider {
        @Override
        @Nullable
        @SuppressWarnings({"unchecked", "rawtypes"})
        public List<TexturedQuad> capture(net.minecraft.world.entity.Entity anyEntity) {
            if (!(anyEntity instanceof LivingEntity entity)) {
                java.util.function.Function<net.minecraft.world.level.block.state.BlockState, List<TexturedQuad>> blocks =
                    state -> BLOCK_QUADS.computeIfAbsent(state, ClientCameraAppearances::buildBlockQuads);
                var vehicle = gg.lakehouse.cctv.camera.VehicleAppearances.capture(anyEntity,
                    ClientCameraAppearances::shortTexture, blocks);
                if (vehicle != null) return vehicle;
                return gg.lakehouse.cctv.camera.DecorAppearances.capture(anyEntity,
                    ClientCameraAppearances::shortTexture, blocks);
            }
            try {
                var out = new ArrayList<TexturedQuad>();
                EntityModel model;
                TexturePixels texture;
                LivingEntityRenderer<?, ?> renderer = null;
                if (entity instanceof Player player) {
                    var skin = skinFor(player);
                    model = skin.slim() ? slimPlayerModel() : widePlayerModel();
                    texture = skin.pixels();
                } else {
                    var candidate = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
                    if (!(candidate instanceof LivingEntityRenderer<?, ?> living)) return null;
                    renderer = living;
                    model = living.getModel();
                    texture = textureFor(((EntityRenderer) candidate).getTextureLocation(entity));
                }

                model.attackTime = 0;
                model.riding = entity.isPassenger();
                model.young = entity.isBaby();
                if (model instanceof HumanoidModel humanoid) humanoid.crouching = entity.isCrouching();
                float bodyYaw = entity.yBodyRot;
                float headYaw = Mth.wrapDegrees(entity.yHeadRot - bodyYaw);
                float limbPosition = entity.walkAnimation.position(1);
                float limbAmount = Math.min(1, entity.walkAnimation.speed(1));
                model.prepareMobModel(entity, limbPosition, limbAmount, 1);
                model.setupAnim(entity, limbPosition, limbAmount, entity.tickCount, headYaw, entity.getXRot());

                var pose = new PoseStack();
                pose.mulPose(Axis.YP.rotationDegrees(180 - bodyYaw));
                pose.scale(-1, -1, 1);
                pose.translate(0, -1.501, 0);
                model.renderToBuffer(pose, new QuadRecorder(texture, out),
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);

                if (renderer != null) {
                    var buffers = new CapturingBufferSource(out);
                    for (var layer : layersOf(renderer)) {
                        try {
                            ((RenderLayer) layer).render(pose, buffers, LightTexture.FULL_BRIGHT, entity,
                                limbPosition, limbAmount, 1, entity.tickCount, headYaw, entity.getXRot());
                        } catch (Exception ignored) {
                            // A modded layer that needs more context than we fake; skip it.
                        }
                    }
                }
                if (entity instanceof Player player && model instanceof PlayerModel playerModel) {
                    addPlayerArmor(out, player, playerModel, pose);
                }
                gg.lakehouse.cctv.camera.EntityFlash.apply(out, entity);
                if (model instanceof ArmedModel armed) {
                    var mainArm = entity.getMainArm();
                    addHeldItem(out, entity.getMainHandItem(), mainArm, armed, pose);
                    addHeldItem(out, entity.getOffhandItem(), mainArm.getOpposite(), armed, pose);
                }
                return out;
            } catch (Exception e) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void addPlayerArmor(List<TexturedQuad> out, Player player,
                                       PlayerModel<LivingEntity> base, PoseStack pose) {
        for (var slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;
            var stack = player.getItemBySlot(slot);
            if (!(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) continue;
            boolean inner = slot == EquipmentSlot.LEGS;
            var armorModel = inner ? innerArmorModel() : outerArmorModel();
            base.copyPropertiesTo(armorModel);
            setArmorVisibility(armorModel, slot);
            var materialName = armor.getMaterial().getName();
            var namespace = "minecraft";
            var path = materialName;
            int colon = materialName.indexOf(':');
            if (colon >= 0) {
                namespace = materialName.substring(0, colon);
                path = materialName.substring(colon + 1);
            }
            var texture = textureFor(new ResourceLocation(namespace,
                "textures/models/armor/" + path + "_layer_" + (inner ? 2 : 1) + ".png"));
            if (texture == MISSING) continue;
            float r = 1;
            float g = 1;
            float b = 1;
            if (stack.getItem() instanceof DyeableLeatherItem dyeable) {
                int color = dyeable.getColor(stack);
                r = ((color >> 16) & 0xFF) / 255f;
                g = ((color >> 8) & 0xFF) / 255f;
                b = (color & 0xFF) / 255f;
            }
            armorModel.renderToBuffer(pose, new QuadRecorder(texture, out),
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, r, g, b, 1);
        }
    }

    private static void setArmorVisibility(HumanoidModel<LivingEntity> model, EquipmentSlot slot) {
        model.setAllVisible(false);
        switch (slot) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
        }
    }

    private static void addHeldItem(List<TexturedQuad> out, ItemStack stack, HumanoidArm arm,
                                    ArmedModel armed, PoseStack pose) {
        if (stack.isEmpty()) return;
        var mc = Minecraft.getInstance();
        pose.pushPose();
        try {
            armed.translateToHand(arm, pose);
            pose.mulPose(Axis.XP.rotationDegrees(-90));
            pose.mulPose(Axis.YP.rotationDegrees(180));
            boolean left = arm == HumanoidArm.LEFT;
            pose.translate((left ? -1 : 1) / 16.0, 0.125, -0.625);
            var model = mc.getItemRenderer().getModel(stack, null, null, 0);
            model.getTransforms().getTransform(
                left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                .apply(left, pose);
            pose.translate(-0.5, -0.5, -0.5);
            var matrix = pose.last().pose();
            var random = RandomSource.create(42);
            for (var side : QUAD_SIDES) {
                for (var quad : model.getQuads(null, side, random)) {
                    int colorMul = 0xFFFFFF;
                    if (quad.getTintIndex() >= 0) {
                        int color = mc.getItemColors().getColor(stack, quad.getTintIndex());
                        if (color != -1) colorMul = color & 0xFFFFFF;
                    }
                    out.add(convert(quad, matrix, colorMul));
                }
            }
        } catch (Exception ignored) {
            // Item model refused to bake outside a level; skip the item.
        } finally {
            pose.popPose();
        }
    }

    // === Render layer capture ===

    private static List<?> layersOf(LivingEntityRenderer<?, ?> renderer) {
        if (layersFieldFailed) return List.of();
        try {
            if (layersField == null) {
                for (var field : LivingEntityRenderer.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        layersField = field;
                        break;
                    }
                }
                if (layersField == null) {
                    layersFieldFailed = true;
                    return List.of();
                }
            }
            return (List<?>) layersField.get(renderer);
        } catch (Exception e) {
            layersFieldFailed = true;
            return List.of();
        }
    }

    /** Buffer source for render layers: resolves each RenderType's texture; unresolvable types are discarded. */
    private static final class CapturingBufferSource implements MultiBufferSource {
        private final List<TexturedQuad> out;
        private final Map<TexturePixels, QuadRecorder> recorders = new HashMap<>();

        CapturingBufferSource(List<TexturedQuad> out) {
            this.out = out;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            var pixels = layerTexture(type);
            if (pixels == null || pixels == MISSING) return NOOP;
            return recorders.computeIfAbsent(pixels, p -> new QuadRecorder(p, out));
        }
    }

    @Nullable
    private static TexturePixels layerTexture(RenderType type) {
        if (LAYER_TEXTURES.containsKey(type)) return LAYER_TEXTURES.get(type);
        TexturePixels pixels = null;
        var location = renderTypeTexture(type);
        if (location != null
            && !location.getPath().startsWith("textures/atlas/")
            && !location.getPath().contains("misc/")) {
            pixels = textureFor(location);
        }
        LAYER_TEXTURES.put(type, pixels);
        return pixels;
    }

    /** Digs the texture out of a composite RenderType by field type, so it survives obfuscation. */
    @Nullable
    private static ResourceLocation renderTypeTexture(RenderType type) {
        try {
            var state = findValueOfType(type, RenderType.CompositeState.class);
            if (state == null) return null;
            var shard = findValueOfType(state, RenderStateShard.EmptyTextureStateShard.class);
            if (shard == null) return null;
            for (var holder = shard.getClass(); holder != null; holder = holder.getSuperclass()) {
                for (var field : holder.getDeclaredFields()) {
                    field.setAccessible(true);
                    var value = field.get(shard);
                    if (value instanceof ResourceLocation location) return location;
                    if (value instanceof Optional<?> optional && optional.isPresent()
                        && optional.get() instanceof ResourceLocation location) {
                        return location;
                    }
                }
            }
        } catch (Exception ignored) {
            // Reflection blocked or layout changed; layer stays invisible.
        }
        return null;
    }

    @Nullable
    private static Object findValueOfType(Object owner, Class<?> wanted) throws IllegalAccessException {
        for (var holder = owner.getClass(); holder != null; holder = holder.getSuperclass()) {
            for (var field : holder.getDeclaredFields()) {
                if (!wanted.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                var value = field.get(owner);
                if (value != null) return value;
            }
        }
        return null;
    }

    // === Player skins ===

    private static PlayerSkin skinFor(Player player) {
        var cached = SKINS.get(player.getUUID());
        if (cached != null) return cached;
        if (SKINS_PENDING.add(player.getUUID())) {
            var profile = player.getGameProfile();
            Util.backgroundExecutor().execute(() -> loadSkin(player.getUUID(), profile));
        }
        return new PlayerSkin(textureFor(STEVE), false);
    }

    private static void loadSkin(UUID id, GameProfile profile) {
        var fallback = new PlayerSkin(textureFor(STEVE), false);
        try {
            var mc = Minecraft.getInstance();
            var textures = mc.getSkinManager().getInsecureSkinInformation(profile);
            var skin = textures.get(MinecraftProfileTexture.Type.SKIN);
            if (skin == null) {
                var filled = mc.getMinecraftSessionService()
                    .fillProfileProperties(new GameProfile(id, profile.getName()), false);
                skin = mc.getSkinManager().getInsecureSkinInformation(filled)
                    .get(MinecraftProfileTexture.Type.SKIN);
            }
            if (skin == null) {
                SKINS.put(id, fallback);
                return;
            }
            boolean slim = "slim".equals(skin.getMetadata("model"));
            var connection = new URL(skin.getUrl().replace("http://", "https://")).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (var stream = connection.getInputStream(); var image = NativeImage.read(stream)) {
                var pixels = imageToPixels(image);
                if (pixels.height() == 32 && pixels.width() == 64) pixels = expandLegacySkin(pixels);
                SKINS.put(id, new PlayerSkin(pixels, slim));
            }
        } catch (Exception e) {
            CCTV.LOGGER.warn("Camera failed to load skin for {}", profile.getName());
            SKINS.put(id, fallback);
        }
    }

    /** Vanilla's 64x32 to 64x64 conversion: mirror the right limbs into the left limb slots. */
    private static TexturePixels expandLegacySkin(TexturePixels legacy) {
        var argb = new int[64 * 64];
        System.arraycopy(legacy.argb(), 0, argb, 0, 64 * 32);
        copyMirrored(argb, 4, 16, 16, 32, 4, 4);
        copyMirrored(argb, 8, 16, 16, 32, 4, 4);
        copyMirrored(argb, 0, 20, 24, 32, 4, 12);
        copyMirrored(argb, 4, 20, 16, 32, 4, 12);
        copyMirrored(argb, 8, 20, 8, 32, 4, 12);
        copyMirrored(argb, 12, 20, 16, 32, 4, 12);
        copyMirrored(argb, 44, 16, -8, 32, 4, 4);
        copyMirrored(argb, 48, 16, -8, 32, 4, 4);
        copyMirrored(argb, 40, 20, 0, 32, 4, 12);
        copyMirrored(argb, 44, 20, -8, 32, 4, 12);
        copyMirrored(argb, 48, 20, -16, 32, 4, 12);
        copyMirrored(argb, 52, 20, -8, 32, 4, 12);
        return new TexturePixels(argb, 64, 64);
    }

    private static void copyMirrored(int[] argb, int x, int y, int offsetX, int offsetY, int width, int height) {
        for (int yy = 0; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                argb[(y + offsetY + yy) * 64 + (x + offsetX + width - 1 - xx)] = argb[(y + yy) * 64 + (x + xx)];
            }
        }
    }

    // === Models ===

    private static PlayerModel<LivingEntity> widePlayerModel() {
        if (widePlayerModel == null) {
            widePlayerModel = new PlayerModel<>(
                Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return widePlayerModel;
    }

    private static PlayerModel<LivingEntity> slimPlayerModel() {
        if (slimPlayerModel == null) {
            slimPlayerModel = new PlayerModel<>(
                Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
        }
        return slimPlayerModel;
    }

    private static HumanoidModel<LivingEntity> innerArmorModel() {
        if (innerArmorModel == null) {
            innerArmorModel = new HumanoidModel<>(
                Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        }
        return innerArmorModel;
    }

    private static HumanoidModel<LivingEntity> outerArmorModel() {
        if (outerArmorModel == null) {
            outerArmorModel = new HumanoidModel<>(
                Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        }
        return outerArmorModel;
    }

    /** Records model geometry as textured quads instead of drawing it. */
    private static final class QuadRecorder implements VertexConsumer {
        private final List<TexturedQuad> out;
        private final TexturePixels texture;
        private final float[] xs = new float[4];
        private final float[] ys = new float[4];
        private final float[] zs = new float[4];
        private final float[] us = new float[4];
        private final float[] vs = new float[4];
        private int vertexColor = 0xFFFFFF;
        private int vertexAlpha = 255;
        private int count;

        QuadRecorder(TexturePixels texture, List<TexturedQuad> out) {
            this.texture = texture;
            this.out = out;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            xs[count] = (float) x;
            ys[count] = (float) y;
            zs[count] = (float) z;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            us[count] = u;
            vs[count] = v;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            vertexColor = (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
            vertexAlpha = a;
            return this;
        }

        @Override
        public void endVertex() {
            count++;
            if (count == 4) {
                count = 0;
                if (vertexAlpha >= 32) {
                    out.add(TexturedQuad.ofColored(xs.clone(), ys.clone(), zs.clone(), us.clone(), vs.clone(),
                        texture, TexturedQuad.TINT_NONE, -1, vertexColor));
                }
            }
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }

    /** Swallows geometry for render types whose texture we can't resolve. */
    private static final VertexConsumer NOOP = new VertexConsumer() {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    };

    // === Textures ===

    private static TexturePixels textureFor(ResourceLocation location) {
        return TEXTURES.computeIfAbsent(location, ClientCameraAppearances::loadTexture);
    }

    private static TexturePixels loadTexture(ResourceLocation location) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return MISSING;
        try (var stream = resource.get().open(); var image = NativeImage.read(stream)) {
            return imageToPixels(image);
        } catch (Exception e) {
            CCTV.LOGGER.warn("Camera failed to load texture {}", location, e);
            return MISSING;
        }
    }

    private static TexturePixels imageToPixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        var argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = image.getPixelRGBA(x, y);
                argb[y * width + x] = (abgr & 0xFF00FF00)
                    | ((abgr & 0xFF) << 16)
                    | ((abgr >> 16) & 0xFF);
            }
        }
        return new TexturePixels(argb, width, height);
    }
}
