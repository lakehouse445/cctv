package gg.lakehouse.cctv.microphone.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.microphone.MicrophoneBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.LoadingModList;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Sound waveforms for the microphones. The server only ever sees sound EVENT
 * ids - the actual oggs live client-side - so vanilla files come lazily from
 * Mojang's asset objects CDN (cached on disk, the same idea as the camera's
 * client jar) and modded files come out of the mod jars on this server.
 * Decoded clips are resampled to the microphone rate and kept in a small LRU.
 */
public final class SoundAssets {
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String OBJECTS_CDN = "https://resources.download.minecraft.net/";
    private static final String MC_VERSION = "1.20.1";
    /** Longest clip worth mixing; protects the cache from jukebox-length files. */
    private static final int MAX_CLIP_SECONDS = 30;
    private static final int CACHE_CLIPS = 128;

    private record Variant(String namespace, String name, float volume, float pitch, int weight, boolean redirect,
                           boolean stream) {
    }

    /** A decoded clip: 8-bit signed mono PCM at the microphone rate, plus the variant's own trims. */
    public record Clip(byte[] pcm, float volume, float pitch) {
    }

    private static SoundAssets instance;
    private static boolean failed;

    private final Path cacheDir;
    private final Map<String, List<Variant>> registry = new HashMap<>();
    private final Map<String, String> vanillaObjects = new HashMap<>();
    private final List<ZipFile> modJars = new ArrayList<>();
    private final Map<String, byte[]> pcmCache = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > CACHE_CLIPS;
        }
    };
    private final ExecutorService loader = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "CC:TV sound loader");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random = new Random();

    /** Null when the asset chain is unavailable (offline server, bad manifest). */
    @Nullable
    public static synchronized SoundAssets get(MinecraftServer server) {
        if (instance != null) return instance;
        if (failed) return null;
        try {
            instance = new SoundAssets(server.getServerDirectory().toPath().resolve("cctv-assets"));
            return instance;
        } catch (Exception e) {
            failed = true;
            CCTV.LOGGER.warn("Sound assets unavailable; microphones will only hear voices", e);
            return null;
        }
    }

    private SoundAssets(Path cacheDir) throws Exception {
        this.cacheDir = cacheDir.resolve("sounds");
        Files.createDirectories(this.cacheDir);
        loadVanillaIndex();
        loadModRegistries();
        CCTV.LOGGER.info("Microphone sound pickup ready: {} sound events known", registry.size());
    }

    // === Lookup ===

    /** Resolves, downloads, and decodes off-thread; the future completes on the loader thread. */
    public CompletableFuture<Clip> fetch(ResourceLocation sound) {
        return CompletableFuture.supplyAsync(() -> load(sound), loader);
    }

    @Nullable
    private Clip load(ResourceLocation sound) {
        var variant = pick(sound.getNamespace() + ":" + sound.getPath(), 0);
        if (variant == null) return null;
        var key = variant.namespace() + ":" + variant.name();
        byte[] pcm;
        synchronized (pcmCache) {
            pcm = pcmCache.get(key);
        }
        if (pcm == null) {
            pcm = decodeVariant(variant);
            if (pcm == null) return null;
            synchronized (pcmCache) {
                pcmCache.put(key, pcm);
            }
        }
        return new Clip(pcm, variant.volume(), variant.pitch());
    }

    @Nullable
    private Variant pick(String eventId, int depth) {
        if (depth > 8) return null;
        var variants = registry.get(eventId);
        if (variants == null || variants.isEmpty()) return null;
        int total = 0;
        for (var variant : variants) total += variant.weight();
        int roll = random.nextInt(Math.max(1, total));
        Variant chosen = variants.get(variants.size() - 1);
        for (var variant : variants) {
            roll -= variant.weight();
            if (roll < 0) {
                chosen = variant;
                break;
            }
        }
        if (chosen.redirect()) {
            var target = pick(chosen.namespace() + ":" + chosen.name(), depth + 1);
            if (target == null) return null;
            return new Variant(target.namespace(), target.name(), target.volume() * chosen.volume(),
                target.pitch() * chosen.pitch(), 1, false, target.stream());
        }
        return chosen;
    }

    @Nullable
    private byte[] decodeVariant(Variant variant) {
        if (variant.stream()) return null; // music discs and ambience loops
        var ogg = readOgg(variant.namespace(), variant.name());
        if (ogg == null) return null;
        var decoded = OggDecoder.decode(ogg);
        if (decoded == null || decoded.samples().length == 0) return null;
        return resample(decoded);
    }

    @Nullable
    private byte[] readOgg(String namespace, String name) {
        var jarPath = "assets/" + namespace + "/sounds/" + name + ".ogg";
        for (var zip : modJars) {
            var entry = zip.getEntry(jarPath);
            if (entry == null) continue;
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (IOException ignored) {
            }
        }
        var hash = vanillaObjects.get(namespace + "/sounds/" + name + ".ogg");
        if (hash == null) return null;
        try {
            var cached = cacheDir.resolve(hash + ".ogg");
            if (Files.isRegularFile(cached)) return Files.readAllBytes(cached);
            var data = fetchBytes(OBJECTS_CDN + hash.substring(0, 2) + "/" + hash);
            Files.write(cached, data);
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    /** Mono 8-bit at the microphone rate, linearly interpolated, capped in length. */
    private static byte[] resample(OggDecoder.Decoded decoded) {
        int rate = decoded.sampleRate();
        var samples = decoded.samples();
        long outLength = Math.min((long) samples.length * MicrophoneBlockEntity.SAMPLE_RATE / rate,
            (long) MAX_CLIP_SECONDS * MicrophoneBlockEntity.SAMPLE_RATE);
        var out = new byte[(int) outLength];
        double step = rate / (double) MicrophoneBlockEntity.SAMPLE_RATE;
        for (int i = 0; i < out.length; i++) {
            double position = i * step;
            int index = (int) position;
            double fraction = position - index;
            int next = Math.min(index + 1, samples.length - 1);
            double value = samples[index] * (1 - fraction) + samples[next] * fraction;
            out[i] = (byte) Math.max(-127, Math.min(127, (int) Math.round(value / 256)));
        }
        return out;
    }

    // === Registries ===

    private void loadVanillaIndex() throws Exception {
        var indexFile = cacheDir.resolve("asset-index-" + MC_VERSION + ".json");
        String indexJson;
        if (Files.isRegularFile(indexFile)) {
            indexJson = Files.readString(indexFile, StandardCharsets.UTF_8);
        } else {
            CCTV.LOGGER.info("Fetching the Minecraft {} sound index from Mojang (one time)...", MC_VERSION);
            var manifest = JsonParser.parseString(fetchText(VERSION_MANIFEST)).getAsJsonObject();
            String versionUrl = null;
            for (var version : manifest.getAsJsonArray("versions")) {
                var object = version.getAsJsonObject();
                if (MC_VERSION.equals(object.get("id").getAsString())) {
                    versionUrl = object.get("url").getAsString();
                    break;
                }
            }
            if (versionUrl == null) throw new IOException("Version " + MC_VERSION + " not in Mojang's manifest");
            var indexUrl = JsonParser.parseString(fetchText(versionUrl)).getAsJsonObject()
                .getAsJsonObject("assetIndex").get("url").getAsString();
            indexJson = fetchText(indexUrl);
            Files.writeString(indexFile, indexJson, StandardCharsets.UTF_8);
        }
        var objects = JsonParser.parseString(indexJson).getAsJsonObject().getAsJsonObject("objects");
        for (var entry : objects.entrySet()) {
            vanillaObjects.put(entry.getKey(), entry.getValue().getAsJsonObject().get("hash").getAsString());
        }
        var soundsHash = vanillaObjects.get("minecraft/sounds.json");
        if (soundsHash == null) throw new IOException("No sounds.json in the asset index");
        var soundsFile = cacheDir.resolve(soundsHash + ".json");
        String soundsJson;
        if (Files.isRegularFile(soundsFile)) {
            soundsJson = Files.readString(soundsFile, StandardCharsets.UTF_8);
        } else {
            soundsJson = fetchText(OBJECTS_CDN + soundsHash.substring(0, 2) + "/" + soundsHash);
            Files.writeString(soundsFile, soundsJson, StandardCharsets.UTF_8);
        }
        parseSoundsJson("minecraft", soundsJson);
    }

    private void loadModRegistries() {
        for (var mod : LoadingModList.get().getModFiles()) {
            var path = mod.getFile().getFilePath();
            if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) continue;
            try {
                var zip = new ZipFile(path.toFile());
                boolean sounds = false;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var match = entry.getName();
                    if (!match.startsWith("assets/") || !match.endsWith("/sounds.json")) continue;
                    var parts = match.split("/");
                    if (parts.length != 3) continue;
                    try (var in = zip.getInputStream(entry)) {
                        parseSoundsJson(parts[1], new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        sounds = true;
                    }
                }
                if (sounds) modJars.add(zip);
                else zip.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void parseSoundsJson(String namespace, String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        for (var entry : root.entrySet()) {
            var definition = entry.getValue().getAsJsonObject();
            if (!definition.has("sounds")) continue;
            var variants = new ArrayList<Variant>();
            for (JsonElement element : definition.getAsJsonArray("sounds")) {
                if (element.isJsonPrimitive()) {
                    variants.add(variant(namespace, element.getAsString(), 1, 1, 1, false, false));
                } else {
                    JsonObject object = element.getAsJsonObject();
                    variants.add(variant(namespace,
                        object.get("name").getAsString(),
                        object.has("volume") ? object.get("volume").getAsFloat() : 1,
                        object.has("pitch") ? object.get("pitch").getAsFloat() : 1,
                        object.has("weight") ? object.get("weight").getAsInt() : 1,
                        object.has("type") && "event".equals(object.get("type").getAsString()),
                        object.has("stream") && object.get("stream").getAsBoolean()));
                }
            }
            if (!variants.isEmpty()) registry.put(namespace + ":" + entry.getKey(), variants);
        }
    }

    /** Sound names may carry their own namespace ("minecraft:block/..."). */
    private static Variant variant(String namespace, String name, float volume, float pitch, int weight,
                                   boolean redirect, boolean stream) {
        int colon = name.indexOf(':');
        if (colon >= 0) {
            namespace = name.substring(0, colon);
            name = name.substring(colon + 1);
        }
        return new Variant(namespace, name, volume, pitch, weight, redirect, stream);
    }

    private static String fetchText(String url) throws IOException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) throws IOException {
        try (InputStream in = new URL(url).openStream()) {
            return in.readAllBytes();
        }
    }
}
