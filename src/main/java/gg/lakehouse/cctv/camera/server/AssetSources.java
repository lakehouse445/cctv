package gg.lakehouse.cctv.camera.server;

import com.google.gson.JsonParser;
import gg.lakehouse.cctv.CCTV;
import net.minecraftforge.fml.loading.LoadingModList;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Asset files for the server-side camera renderer: the vanilla client jar
 * (fetched once from Mojang's official download CDN, like map mods do) plus
 * every mod jar on this server, searched in order. Dedicated servers ship no
 * models or textures of their own — this is where they come from.
 */
final class AssetSources implements AutoCloseable {
    private static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String MC_VERSION = "1.20.1";

    private final List<ZipFile> zips = new ArrayList<>();

    AssetSources(Path cacheDir) throws Exception {
        var clientJar = obtainClientJar(cacheDir);
        zips.add(new ZipFile(clientJar.toFile()));
        for (var mod : LoadingModList.get().getModFiles()) {
            var path = mod.getFile().getFilePath();
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                try {
                    zips.add(new ZipFile(path.toFile()));
                } catch (IOException ignored) {
                    // Not a plain zip (e.g. exploded dev classpath); skip.
                }
            }
        }
    }

    /** First match across the vanilla jar and all mod jars, or null. */
    @Nullable
    byte[] read(String path) {
        for (var zip : zips) {
            var entry = zip.getEntry(path);
            if (entry == null) continue;
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static Path obtainClientJar(Path cacheDir) throws Exception {
        Files.createDirectories(cacheDir);
        var jar = cacheDir.resolve("client-" + MC_VERSION + ".jar");
        if (Files.isRegularFile(jar) && Files.size(jar) > 1_000_000) return jar;

        CCTV.LOGGER.info("Fetching the Minecraft {} client assets from Mojang (one time, ~25 MB)...", MC_VERSION);
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

        var client = JsonParser.parseString(fetchText(versionUrl)).getAsJsonObject()
            .getAsJsonObject("downloads").getAsJsonObject("client");
        var data = fetchBytes(client.get("url").getAsString());
        var sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        if (!sha1.equalsIgnoreCase(client.get("sha1").getAsString())) {
            throw new IOException("Client jar checksum mismatch");
        }
        Files.write(jar, data);
        CCTV.LOGGER.info("Client assets cached at {}", jar);
        return jar;
    }

    private static String fetchText(String url) throws IOException {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) throws IOException {
        try (InputStream in = new URL(url).openStream()) {
            return in.readAllBytes();
        }
    }

    @Override
    public void close() {
        for (var zip : zips) {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
        }
    }
}
