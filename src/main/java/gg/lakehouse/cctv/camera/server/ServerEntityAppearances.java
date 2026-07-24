package gg.lakehouse.cctv.camera.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.camera.TexturePixels;
import gg.lakehouse.cctv.camera.TexturedQuad;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

/**
 * Faithful entity rendering without client code: model geometry comes from
 * the exported layer pack (assets/cctv/entity_geometry.json.gz, produced by
 * the dev client), textures from the asset zips, and player skins straight
 * from the session service. Poses are driven generically from data the
 * server owns — body and head rotation, walk animation, sheep color, baby
 * scale — using vanilla's limb swing math keyed by part names.
 */
final class ServerEntityAppearances {
    private record Layer(String key, Function<LivingEntity, String> texture,
                         Function<LivingEntity, Integer> colorMul,
                         Function<LivingEntity, Boolean> visible) {
        static Layer plain(String key, String texture) {
            return new Layer(key, entity -> texture, entity -> 0xFFFFFF, entity -> true);
        }
    }

    private final ModelBaker baker;
    private final Map<String, gg.lakehouse.cctv.camera.GeometryPack.Part> layers;
    private final Map<EntityType<?>, List<Layer>> mapping = new HashMap<>();
    private final Map<UUID, TexturePixels> playerSkins = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerSlim = new ConcurrentHashMap<>();

    ServerEntityAppearances(AssetSources sources, ModelBaker baker) {
        this.baker = baker;
        this.layers = gg.lakehouse.cctv.camera.GeometryPack.layers();
        buildMapping();
    }

    private void buildMapping() {
        map(EntityType.CAVE_SPIDER, 0.7f, Layer.plain("minecraft:cave_spider#main", "minecraft:entity/spider/cave_spider"));
        map(EntityType.WITHER_SKELETON, 1.2f, Layer.plain("minecraft:wither_skeleton#main", "minecraft:entity/skeleton/wither_skeleton"));
        map(EntityType.ELDER_GUARDIAN, 2.35f, Layer.plain("minecraft:elder_guardian#main", "minecraft:entity/guardian_elder"));
        map(EntityType.ZOMBIFIED_PIGLIN, Layer.plain("minecraft:zombified_piglin#main", "minecraft:entity/piglin/zombified_piglin"));
        map(EntityType.PIGLIN, Layer.plain("minecraft:piglin#main", "minecraft:entity/piglin/piglin"));
        map(EntityType.PIGLIN_BRUTE, Layer.plain("minecraft:piglin_brute#main", "minecraft:entity/piglin/piglin_brute"));
        map(EntityType.BLAZE, Layer.plain("minecraft:blaze#main", "minecraft:entity/blaze"));
        map(EntityType.SLIME, Layer.plain("minecraft:slime#main", "minecraft:entity/slime/slime"));
        map(EntityType.MAGMA_CUBE, Layer.plain("minecraft:magma_cube#main", "minecraft:entity/slime/magmacube"));
        map(EntityType.SILVERFISH, Layer.plain("minecraft:silverfish#main", "minecraft:entity/silverfish"));
        map(EntityType.ENDERMITE, Layer.plain("minecraft:endermite#main", "minecraft:entity/endermite"));
        map(EntityType.BAT, Layer.plain("minecraft:bat#main", "minecraft:entity/bat"));
        map(EntityType.SQUID, Layer.plain("minecraft:squid#main", "minecraft:entity/squid/squid"));
        map(EntityType.GLOW_SQUID, Layer.plain("minecraft:glow_squid#main", "minecraft:entity/squid/glow_squid"));
        map(EntityType.COD, Layer.plain("minecraft:cod#main", "minecraft:entity/fish/cod"));
        map(EntityType.SALMON, Layer.plain("minecraft:salmon#main", "minecraft:entity/fish/salmon"));
        map(EntityType.RABBIT, variant("minecraft:rabbit#main", entity -> {
            var kind = ((net.minecraft.world.entity.animal.Rabbit) entity).getVariant();
            return "minecraft:entity/rabbit/" + (kind == net.minecraft.world.entity.animal.Rabbit.Variant.EVIL
                ? "caerbannog" : kind.name().toLowerCase(java.util.Locale.ROOT));
        }));
        map(EntityType.FOX, variant("minecraft:fox#main",
            entity -> ((net.minecraft.world.entity.animal.Fox) entity).getVariant() == net.minecraft.world.entity.animal.Fox.Type.SNOW
                ? "minecraft:entity/fox/snow_fox" : "minecraft:entity/fox/fox"));
        map(EntityType.PANDA, Layer.plain("minecraft:panda#main", "minecraft:entity/panda/panda"));
        map(EntityType.POLAR_BEAR, Layer.plain("minecraft:polar_bear#main", "minecraft:entity/bear/polarbear"));
        map(EntityType.GOAT, Layer.plain("minecraft:goat#main", "minecraft:entity/goat/goat"));
        map(EntityType.CAMEL, Layer.plain("minecraft:camel#main", "minecraft:entity/camel/camel"));
        map(EntityType.SNIFFER, Layer.plain("minecraft:sniffer#main", "minecraft:entity/sniffer/sniffer"));
        map(EntityType.MOOSHROOM, variant("minecraft:mooshroom#main",
            entity -> ((net.minecraft.world.entity.animal.MushroomCow) entity).getVariant()
                == net.minecraft.world.entity.animal.MushroomCow.MushroomType.BROWN
                ? "minecraft:entity/cow/brown_mooshroom" : "minecraft:entity/cow/red_mooshroom"));
        map(EntityType.DONKEY, Layer.plain("minecraft:donkey#main", "minecraft:entity/horse/donkey"));
        map(EntityType.MULE, Layer.plain("minecraft:mule#main", "minecraft:entity/horse/mule"));
        map(EntityType.LLAMA,
            variant("minecraft:llama#main", entity -> "minecraft:entity/llama/"
                + ((net.minecraft.world.entity.animal.horse.Llama) entity).getVariant().name().toLowerCase(java.util.Locale.ROOT)),
            new Layer("minecraft:llama#decor", ServerEntityAppearances::llamaDecorTexture,
                entity -> 0xFFFFFF,
                entity -> ((net.minecraft.world.entity.animal.horse.Llama) entity).getSwag() != null));
        map(EntityType.TRADER_LLAMA,
            variant("minecraft:trader_llama#main", entity -> "minecraft:entity/llama/"
                + ((net.minecraft.world.entity.animal.horse.Llama) entity).getVariant().name().toLowerCase(java.util.Locale.ROOT)),
            // Vanilla bakes a single decor rig; both llama renderers share it.
            new Layer("minecraft:llama#decor", ServerEntityAppearances::llamaDecorTexture,
                entity -> 0xFFFFFF, entity -> true));
        map(EntityType.SNOW_GOLEM, Layer.plain("minecraft:snow_golem#main", "minecraft:entity/snow_golem"));
        map(EntityType.GUARDIAN, Layer.plain("minecraft:guardian#main", "minecraft:entity/guardian"));
        map(EntityType.VINDICATOR, Layer.plain("minecraft:vindicator#main", "minecraft:entity/illager/vindicator"));
        map(EntityType.EVOKER, Layer.plain("minecraft:evoker#main", "minecraft:entity/illager/evoker"));
        map(EntityType.RAVAGER, Layer.plain("minecraft:ravager#main", "minecraft:entity/illager/ravager"));
        map(EntityType.VEX, Layer.plain("minecraft:vex#main", "minecraft:entity/illager/vex"));
        map(EntityType.ALLAY, Layer.plain("minecraft:allay#main", "minecraft:entity/allay/allay"));
        map(EntityType.AXOLOTL, variant("minecraft:axolotl#main", entity -> "minecraft:entity/axolotl/axolotl_"
            + ((net.minecraft.world.entity.animal.axolotl.Axolotl) entity).getVariant().getName()));
        map(EntityType.BEE, Layer.plain("minecraft:bee#main", "minecraft:entity/bee/bee"));
        map(EntityType.DOLPHIN, Layer.plain("minecraft:dolphin#main", "minecraft:entity/dolphin"));
        map(EntityType.TURTLE, Layer.plain("minecraft:turtle#main", "minecraft:entity/turtle/big_sea_turtle"));
        map(EntityType.OCELOT, Layer.plain("minecraft:ocelot#main", "minecraft:entity/cat/ocelot"));
        map(EntityType.PARROT, variant("minecraft:parrot#main", entity -> {
            var kind = ((net.minecraft.world.entity.animal.Parrot) entity).getVariant();
            return "minecraft:entity/parrot/parrot_" + (kind == net.minecraft.world.entity.animal.Parrot.Variant.GRAY
                ? "grey" : kind.name().toLowerCase(java.util.Locale.ROOT));
        }));
        map(EntityType.PHANTOM, Layer.plain("minecraft:phantom#main", "minecraft:entity/phantom"));
        map(EntityType.GHAST, Layer.plain("minecraft:ghast#main", "minecraft:entity/ghast/ghast"));
        map(EntityType.HOGLIN, Layer.plain("minecraft:hoglin#main", "minecraft:entity/hoglin/hoglin"));
        map(EntityType.ZOGLIN, Layer.plain("minecraft:zoglin#main", "minecraft:entity/hoglin/zoglin"));
        map(EntityType.STRIDER, Layer.plain("minecraft:strider#main", "minecraft:entity/strider/strider"));
        map(EntityType.WANDERING_TRADER, Layer.plain("minecraft:wandering_trader#main", "minecraft:entity/wandering_trader"));
        map(EntityType.VILLAGER, villagerLayers("minecraft:villager#main", "villager"));
        map(EntityType.ZOMBIE_VILLAGER, villagerLayers("minecraft:zombie_villager#main", "zombie_villager"));
        map(EntityType.WARDEN, Layer.plain("minecraft:warden#main", "minecraft:entity/warden/warden"));
        map(EntityType.WITHER, Layer.plain("minecraft:wither#main", "minecraft:entity/wither/wither"));
        map(EntityType.FROG, new Layer("minecraft:frog#main",
            entity -> vanillaTexture(((net.minecraft.world.entity.animal.frog.Frog) entity).getVariant().texture()),
            entity -> 0xFFFFFF, entity -> true));
        map(EntityType.ZOMBIE, Layer.plain("minecraft:zombie#main", "minecraft:entity/zombie/zombie"));
        map(EntityType.HUSK, Layer.plain("minecraft:husk#main", "minecraft:entity/zombie/husk"));
        map(EntityType.SKELETON, Layer.plain("minecraft:skeleton#main", "minecraft:entity/skeleton/skeleton"));
        map(EntityType.STRAY, Layer.plain("minecraft:stray#main", "minecraft:entity/skeleton/stray"));
        map(EntityType.CREEPER, Layer.plain("minecraft:creeper#main", "minecraft:entity/creeper/creeper"));
        map(EntityType.SPIDER, Layer.plain("minecraft:spider#main", "minecraft:entity/spider/spider"));
        map(EntityType.COW, Layer.plain("minecraft:cow#main", "minecraft:entity/cow/cow"));
        map(EntityType.PIG, Layer.plain("minecraft:pig#main", "minecraft:entity/pig/pig"));
        map(EntityType.CHICKEN, Layer.plain("minecraft:chicken#main", "minecraft:entity/chicken"));
        map(EntityType.WOLF, Layer.plain("minecraft:wolf#main", "minecraft:entity/wolf/wolf"));
        map(EntityType.CAT, variant("minecraft:cat#main",
            entity -> vanillaTexture(((net.minecraft.world.entity.animal.Cat) entity).getVariant().texture())));
        map(EntityType.HORSE, variant("minecraft:horse#main", entity -> "minecraft:entity/horse/horse_"
            + ((net.minecraft.world.entity.animal.horse.Horse) entity).getVariant().name()
            .toLowerCase(java.util.Locale.ROOT).replace("_", "")));
        map(EntityType.IRON_GOLEM, Layer.plain("minecraft:iron_golem#main", "minecraft:entity/iron_golem/iron_golem"));
        map(EntityType.ENDERMAN, Layer.plain("minecraft:enderman#main", "minecraft:entity/enderman/enderman"));
        map(EntityType.WITCH, Layer.plain("minecraft:witch#main", "minecraft:entity/witch"));
        map(EntityType.PILLAGER, Layer.plain("minecraft:pillager#main", "minecraft:entity/illager/pillager"));
        map(EntityType.DROWNED, Layer.plain("minecraft:drowned#main", "minecraft:entity/zombie/drowned"));
        map(EntityType.ARMOR_STAND, Layer.plain("minecraft:armor_stand#main", "minecraft:entity/armorstand/wood"));
        map(EntityType.SHEEP,
            Layer.plain("minecraft:sheep#main", "minecraft:entity/sheep/sheep"),
            new Layer("minecraft:sheep#fur", entity -> "minecraft:entity/sheep/sheep_fur",
                entity -> {
                    var colors = ((Sheep) entity).getColor().getTextureDiffuseColors();
                    return ((int) (colors[0] * 255) << 16) | ((int) (colors[1] * 255) << 8) | (int) (colors[2] * 255);
                },
                entity -> !((Sheep) entity).isSheared()));
    }

    private final Map<EntityType<?>, Float> scales = new HashMap<>();

    private static Layer variant(String key, Function<LivingEntity, String> texture) {
        return new Layer(key, texture, entity -> 0xFFFFFF, entity -> true);
    }

    /** A worn carpet's rug, or the trader rug — vanilla's decor layer rules. */
    private static String llamaDecorTexture(LivingEntity entity) {
        var swag = ((net.minecraft.world.entity.animal.horse.Llama) entity).getSwag();
        return "minecraft:entity/llama/decor/" + (swag == null ? "trader_llama" : swag.getName());
    }

    /**
     * Vanilla's villager overlays on one model: biome type skin, profession
     * clothes, level badge. All layers share the model's geometry, so their
     * quads tie on depth — the painter keeps the first quad that claims a
     * pixel, so overlays list before the base to win where their texels
     * are opaque, and fall through to the base where transparent.
     */
    private static Layer[] villagerLayers(String modelKey, String prefix) {
        return new Layer[]{
            new Layer(modelKey, entity -> "minecraft:entity/villager/profession_level/"
                + BADGES[Mth.clamp(villagerData(entity).getLevel(), 1, 5) - 1],
                entity -> 0xFFFFFF, ServerEntityAppearances::hasProfession),
            new Layer(modelKey, entity -> "minecraft:entity/" + prefix + "/profession/"
                + pathOf(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
                    .getKey(villagerData(entity).getProfession())),
                entity -> 0xFFFFFF, ServerEntityAppearances::hasProfession),
            new Layer(modelKey, entity -> "minecraft:entity/" + prefix + "/type/"
                + pathOf(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_TYPE
                    .getKey(villagerData(entity).getType())),
                entity -> 0xFFFFFF, entity -> true),
            Layer.plain(modelKey, "minecraft:entity/" + prefix + "/" + prefix)
        };
    }

    private static final String[] BADGES = {"stone", "iron", "gold", "emerald", "diamond"};

    private static net.minecraft.world.entity.npc.VillagerData villagerData(LivingEntity entity) {
        return ((net.minecraft.world.entity.npc.VillagerDataHolder) entity).getVillagerData();
    }

    private static Boolean hasProfession(LivingEntity entity) {
        var profession = villagerData(entity).getProfession();
        return profession != net.minecraft.world.entity.npc.VillagerProfession.NONE
            && profession != net.minecraft.world.entity.npc.VillagerProfession.NITWIT;
    }

    private static String pathOf(@Nullable net.minecraft.resources.ResourceLocation name) {
        return name == null ? "plains" : name.getPath();
    }

    private void map(EntityType<?> type, Layer... entityLayers) {
        mapping.put(type, List.of(entityLayers));
    }

    private void map(EntityType<?> type, float scale, Layer... entityLayers) {
        map(type, entityLayers);
        scales.put(type, scale);
    }

    /** "minecraft:textures/entity/cat/tabby.png" (variant registries) → our short texture form. */
    private static String vanillaTexture(net.minecraft.resources.ResourceLocation location) {
        var path = location.getPath();
        if (path.startsWith("textures/")) path = path.substring("textures/".length());
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return location.getNamespace() + ":" + path;
    }

    // === Capture ===

    @Nullable
    List<TexturedQuad> capture(LivingEntity entity,
                               @Nullable Function<net.minecraft.world.level.block.state.BlockState, List<TexturedQuad>> blockQuads) {
        List<Layer> entityLayers;
        TexturePixels playerSkin = null;
        if (entity instanceof ServerPlayer player) {
            playerSkin = skinFor(player);
            var key = Boolean.TRUE.equals(playerSlim.get(player.getUUID()))
                ? "minecraft:player_slim#main" : "minecraft:player#main";
            entityLayers = List.of(Layer.plain(key, ""));
        } else {
            entityLayers = mapping.get(entity.getType());
            if (entityLayers == null) {
                // Unmapped (usually modded): try the conventional layer and
                // texture names. A gray model still beats a box.
                var name = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
                if (name != null && layers.containsKey(name.getNamespace() + ":" + name.getPath() + "#main")) {
                    entityLayers = List.of(Layer.plain(name.getNamespace() + ":" + name.getPath() + "#main",
                        name.getNamespace() + ":entity/" + name.getPath()));
                }
            }
        }
        if (entityLayers == null || layers.isEmpty()) return null;

        var result = new ArrayList<TexturedQuad>();
        var root = new Matrix4f();
        var pose = entity.getPose();
        if (pose == net.minecraft.world.entity.Pose.SLEEPING) {
            var bed = entity.getBedOrientation();
            float yaw = bed != null ? bed.toYRot() : entity.yBodyRot;
            root.translate(0, 0.4f, 0.9f)
                .rotateX((float) Math.toRadians(-90))
                .rotateY((float) Math.toRadians(180 - yaw));
        } else if (pose == net.minecraft.world.entity.Pose.SWIMMING) {
            root.translate(0, 0.5f, 0.4f)
                .rotateX((float) Math.toRadians(-80))
                .rotateY((float) Math.toRadians(180 - entity.yBodyRot));
        } else {
            root.rotateY((float) Math.toRadians(180 - entity.yBodyRot));
        }
        root.scale(-1, -1, 1);
        var typeScale = scales.get(entity.getType());
        if (typeScale != null) root.scale(typeScale);
        root.translate(0, -1.501f, 0);
        if (entity.isBaby()) {
            root.scale(0.5f).translate(0, 1.501f, 0);
        }

        var arms = new Matrix4f[2];
        for (var layer : entityLayers) {
            if (!layer.visible().apply(entity)) continue;
            var part = layers.get(layer.key());
            if (part == null) continue;
            var texture = playerSkin != null ? playerSkin : baker.texture(layer.texture().apply(entity));
            int colorMul = layer.colorMul().apply(entity);
            emitPart(result, part, "root", root, entity, texture, colorMul, null, arms);
        }
        if (result.isEmpty()) return null;

        emitArmor(result, entity, entityLayers.get(0).key(), root);
        gg.lakehouse.cctv.camera.EntityFlash.apply(result, entity);
        emitHeld(result, entity, entity.getMainHandItem(), arms[0], -1 / 16f, blockQuads);
        emitHeld(result, entity, entity.getOffhandItem(), arms[1], 1 / 16f, blockQuads);
        return result;
    }

    /** Armor from the humanoid armor layers, gated to the covered parts per slot. */
    private void emitArmor(List<TexturedQuad> result, LivingEntity entity, String mainLayerKey, Matrix4f root) {
        int hash = mainLayerKey.indexOf('#');
        var base = hash < 0 ? mainLayerKey : mainLayerKey.substring(0, hash);
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            if (slot.getType() != net.minecraft.world.entity.EquipmentSlot.Type.ARMOR) continue;
            var stack = entity.getItemBySlot(slot);
            if (!(stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor)) continue;
            boolean inner = slot == net.minecraft.world.entity.EquipmentSlot.LEGS;
            var part = layers.get(base + (inner ? "#inner_armor" : "#outer_armor"));
            if (part == null) continue;
            var material = armor.getMaterial().getName();
            int colon = material.indexOf(':');
            if (colon >= 0) material = material.substring(colon + 1);
            var texture = baker.texture("minecraft:models/armor/" + material + "_layer_" + (inner ? 2 : 1));
            int colorMul = stack.getItem() instanceof net.minecraft.world.item.DyeableLeatherItem dyeable
                ? dyeable.getColor(stack) & 0xFFFFFF : 0xFFFFFF;
            var visible = switch (slot) {
                case HEAD -> java.util.Set.of("head", "hat");
                case CHEST -> java.util.Set.of("body", "right_arm", "left_arm");
                case LEGS -> java.util.Set.of("body", "right_leg", "left_leg");
                default -> java.util.Set.of("right_leg", "left_leg");
            };
            emitPart(result, part, "root", root, entity, texture, colorMul, visible::contains, null);
        }
    }

    /** A held item at the hand, oriented like a carried tool. */
    private void emitHeld(List<TexturedQuad> result, LivingEntity entity, net.minecraft.world.item.ItemStack stack,
                          @Nullable Matrix4f arm, float sideOffset,
                          @Nullable Function<net.minecraft.world.level.block.state.BlockState, List<TexturedQuad>> blockQuads) {
        if (arm == null || stack.isEmpty()) return;
        var quads = gg.lakehouse.cctv.camera.DecorAppearances.itemQuads(stack, baker::texture, blockQuads);
        if (quads == null || quads.isEmpty()) return;
        var hand = new Matrix4f(arm)
            .translate(sideOffset, 10 / 16f, -2 / 16f)
            .rotateX((float) Math.toRadians(-90))
            .scale(0.55f);
        var position = new org.joml.Vector3f();
        for (var quad : quads) {
            for (int i = 0; i < 4; i++) {
                position.set(quad.xs()[i], quad.ys()[i] - 0.2f, quad.zs()[i]);
                hand.transformPosition(position);
                quad.xs()[i] = position.x;
                quad.ys()[i] = position.y;
                quad.zs()[i] = position.z;
            }
        }
        result.addAll(quads);
    }

    private void emitPart(List<TexturedQuad> result, gg.lakehouse.cctv.camera.GeometryPack.Part part, String name,
                          Matrix4f parent, LivingEntity entity, TexturePixels texture, int colorMul,
                          @Nullable java.util.function.Predicate<String> childFilter, @Nullable Matrix4f[] armOut) {
        var matrix = new Matrix4f(parent)
            .translate(part.x() / 16, part.y() / 16, part.z() / 16);
        // Vanilla's crouch offsets from HumanoidModel: torso and head sink,
        // legs step back. Model space is y-down, matching the part pivots.
        if (entity.isCrouching()) {
            switch (name) {
                case "head", "hat" -> matrix.translate(0, 4.2f / 16, 0);
                case "body" -> matrix.translate(0, 3.2f / 16, 0);
                case "right_arm", "left_arm" -> matrix.translate(0, 3.2f / 16, 0);
                case "right_leg", "left_leg" -> matrix.translate(0, 0.2f / 16, 3.9f / 16);
                default -> {
                }
            }
        }
        float xr = part.xr() + poseX(name, entity);
        float yr = part.yr() + poseY(name, entity);
        float zr = part.zr();
        if (zr != 0) matrix.rotateZ(zr);
        if (yr != 0) matrix.rotateY(yr);
        if (xr != 0) matrix.rotateX(xr);
        if (entity.isBaby() && (name.equals("head") || name.equals("hat"))) matrix.scale(1.4f);
        if (armOut != null) {
            if (name.equals("right_arm")) armOut[0] = new Matrix4f(matrix);
            else if (name.equals("left_arm")) armOut[1] = new Matrix4f(matrix);
        }

        var position = new Vector3f();
        for (var quad : part.quads()) {
            var xs = new float[4];
            var ys = new float[4];
            var zs = new float[4];
            var us = new float[4];
            var vs = new float[4];
            for (int i = 0; i < 4; i++) {
                position.set(quad[i][0] / 16, quad[i][1] / 16, quad[i][2] / 16);
                matrix.transformPosition(position);
                xs[i] = position.x;
                ys[i] = position.y;
                zs[i] = position.z;
                us[i] = quad[i][3];
                vs[i] = quad[i][4];
            }
            result.add(TexturedQuad.ofColored(xs, ys, zs, us, vs, texture, TexturedQuad.TINT_NONE, -1, colorMul));
        }
        for (var child : part.children().entrySet()) {
            if (childFilter != null && !childFilter.test(child.getKey())) continue;
            emitPart(result, child.getValue(), child.getKey(), matrix, entity, texture, colorMul, null, armOut);
        }
    }

    // === Generic pose: vanilla's limb math keyed by part names ===

    private static float poseX(String name, LivingEntity entity) {
        if (name.equals("head") || name.equals("hat")) {
            return (float) Math.toRadians(entity.getXRot());
        }
        boolean sitting = entity.isPassenger()
            || (entity instanceof net.minecraft.world.entity.TamableAnimal tamable && tamable.isInSittingPose());
        if (sitting && (name.equals("right_leg") || name.equals("left_leg"))) return -1.41f;
        // Crouch tilts the torso and raises the arms, on top of walk swing.
        float base = 0;
        if (entity.isCrouching()) {
            if (name.equals("body")) base = 0.5f;
            else if (name.equals("right_arm") || name.equals("left_arm")) base = 0.4f;
        }
        float swing = entity.walkAnimation.position();
        float amount = Math.min(1, entity.walkAnimation.speed());
        if (amount < 0.01f) return base;
        boolean right = name.contains("right");
        boolean front = name.contains("front");
        if (name.contains("leg")) {
            float phase = (right ^ front) ? 0 : Mth.PI;
            return base + Mth.cos(swing * 0.6662f + phase) * 1.4f * amount;
        }
        if (name.contains("arm")) {
            float phase = right ? Mth.PI : 0;
            return base + Mth.cos(swing * 0.6662f + phase) * amount;
        }
        return base;
    }

    private static float poseY(String name, LivingEntity entity) {
        if (name.equals("head") || name.equals("hat")) {
            return (float) Math.toRadians(entity.yHeadRot - entity.yBodyRot);
        }
        return 0;
    }

    // === Player skins from the session service ===

    private TexturePixels skinFor(ServerPlayer player) {
        var id = player.getUUID();
        var cached = playerSkins.get(id);
        if (cached != null) return cached;
        var steve = baker.texture("minecraft:entity/player/wide/steve");
        playerSkins.put(id, steve);
        net.minecraft.Util.backgroundExecutor().execute(() -> downloadSkin(player, steve));
        return steve;
    }

    private void downloadSkin(ServerPlayer player, TexturePixels fallback) {
        try {
            var textures = player.getGameProfile().getProperties().get("textures");
            if (textures.isEmpty()) return;
            var payload = JsonParser.parseString(new String(
                    Base64.getDecoder().decode(textures.iterator().next().getValue()), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("textures");
            if (payload == null || !payload.has("SKIN")) return;
            var skin = payload.getAsJsonObject("SKIN");
            boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
            byte[] data;
            try (InputStream in = new URL(skin.get("url").getAsString()).openStream()) {
                data = in.readAllBytes();
            }
            var image = ImageIO.read(new ByteArrayInputStream(data));
            if (image.getHeight() == 32) image = expandLegacySkin(image);
            var argb = new int[image.getWidth() * image.getHeight()];
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());
            playerSkins.put(player.getUUID(), new TexturePixels(argb, image.getWidth(), image.getHeight()));
            playerSlim.put(player.getUUID(), slim);
        } catch (Exception e) {
            CCTV.LOGGER.warn("Skin fetch failed for {}: {}", player.getGameProfile().getName(), e.toString());
            playerSkins.put(player.getUUID(), fallback);
        }
    }

    /** Old 64x32 skins: mirror the arm and leg onto the modern 64x64 layout. */
    private static BufferedImage expandLegacySkin(BufferedImage legacy) {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.drawImage(legacy, 0, 0, null);
        graphics.drawImage(legacy, 24, 48, 20, 52, 4, 16, 8, 20, null);
        graphics.drawImage(legacy, 28, 48, 24, 52, 8, 16, 12, 20, null);
        graphics.drawImage(legacy, 20, 52, 16, 64, 8, 20, 12, 32, null);
        graphics.drawImage(legacy, 24, 52, 20, 64, 4, 20, 8, 32, null);
        graphics.drawImage(legacy, 28, 52, 24, 64, 0, 20, 4, 32, null);
        graphics.drawImage(legacy, 32, 52, 28, 64, 12, 20, 16, 32, null);
        graphics.drawImage(legacy, 40, 48, 36, 52, 44, 16, 48, 20, null);
        graphics.drawImage(legacy, 44, 48, 40, 52, 48, 16, 52, 20, null);
        graphics.drawImage(legacy, 36, 52, 32, 64, 48, 20, 52, 32, null);
        graphics.drawImage(legacy, 40, 52, 36, 64, 44, 20, 48, 32, null);
        graphics.drawImage(legacy, 44, 52, 40, 64, 40, 20, 44, 32, null);
        graphics.drawImage(legacy, 48, 52, 44, 64, 52, 20, 56, 32, null);
        graphics.dispose();
        return image;
    }
}
