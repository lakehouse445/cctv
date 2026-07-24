package gg.lakehouse.cctv.camera;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Skin pixels for arbitrary game profiles (player heads on skull blocks),
 * fetched from the profile's textures property and cached. The fallback is
 * the bundled default skin until the download lands.
 */
public final class ProfileSkins {
    private static final Map<String, TexturePixels> CACHE = new ConcurrentHashMap<>();

    private ProfileSkins() {
    }

    public static TexturePixels get(@Nullable GameProfile profile, Function<String, TexturePixels> textures) {
        var fallback = textures.apply("minecraft:entity/player/wide/steve");
        if (profile == null) return fallback;
        var key = profile.getId() != null ? profile.getId().toString() : profile.getName();
        if (key == null) return fallback;
        var cached = CACHE.get(key);
        if (cached != null) return cached;
        CACHE.put(key, fallback);
        net.minecraft.Util.backgroundExecutor().execute(() -> download(key, profile));
        return fallback;
    }

    private static void download(String key, GameProfile profile) {
        try {
            var textures = profile.getProperties().get("textures");
            if (textures.isEmpty()) return;
            var payload = JsonParser.parseString(new String(
                    Base64.getDecoder().decode(textures.iterator().next().getValue()), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("textures");
            if (payload == null || !payload.has("SKIN")) return;
            byte[] data;
            try (InputStream in = new URL(payload.getAsJsonObject("SKIN").get("url").getAsString()).openStream()) {
                data = in.readAllBytes();
            }
            var image = ImageIO.read(new ByteArrayInputStream(data));
            var argb = new int[image.getWidth() * image.getHeight()];
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());
            CACHE.put(key, new TexturePixels(argb, image.getWidth(), image.getHeight()));
        } catch (Exception ignored) {
            // Keep the fallback.
        }
    }
}
