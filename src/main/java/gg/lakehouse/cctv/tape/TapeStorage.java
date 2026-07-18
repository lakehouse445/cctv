package gg.lakehouse.cctv.tape;

import gg.lakehouse.cctv.CCTV;
import gg.lakehouse.cctv.media.TermFrame;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Server-side tape contents, stored per tape id in the world folder
 * (world/cctv/tapes/&lt;uuid&gt;/rec_NNN.bin) so recordings survive restarts —
 * the same idea as CC:T's computer/disk folders.
 */
public final class TapeStorage {
    private static final Pattern RECORDING_NAME = Pattern.compile("rec_(\\d{3})\\.bin");

    public record RecordingInfo(String name, long bytes, int fps, int frames) {
    }

    private TapeStorage() {
    }

    private static Path tapeDir(MinecraftServer server, UUID tapeId) {
        return server.getWorldPath(LevelResource.ROOT).resolve("cctv").resolve("tapes").resolve(tapeId.toString());
    }

    public static long usedBytes(MinecraftServer server, UUID tapeId) {
        var dir = tapeDir(server, tapeId);
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(TapeStorage::isRecording).mapToLong(TapeStorage::size).sum();
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to measure tape {}", tapeId, e);
            return 0;
        }
    }

    public static List<RecordingInfo> list(MinecraftServer server, UUID tapeId) {
        var dir = tapeDir(server, tapeId);
        var result = new ArrayList<RecordingInfo>();
        if (!Files.isDirectory(dir)) return result;
        try (Stream<Path> files = Files.list(dir)) {
            for (var file : files.filter(TapeStorage::isRecording).sorted(Comparator.comparing(Path::getFileName)).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    var header = TermFrame.readHeader(in);
                    var name = file.getFileName().toString();
                    result.add(new RecordingInfo(name.substring(0, name.length() - 4), size(file), header.fps(), header.frames()));
                } catch (IOException e) {
                    CCTV.LOGGER.error("Skipping unreadable recording {}", file, e);
                }
            }
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to list tape {}", tapeId, e);
        }
        return result;
    }

    /** @return the new recording's name, or null if the tape is full. */
    @Nullable
    public static String save(MinecraftServer server, UUID tapeId, byte[] data) throws IOException {
        if (usedBytes(server, tapeId) + data.length > TapeItem.CAPACITY_BYTES) return null;
        var dir = tapeDir(server, tapeId);
        Files.createDirectories(dir);
        int next = 1;
        for (var info : list(server, tapeId)) {
            var matcher = RECORDING_NAME.matcher(info.name() + ".bin");
            if (matcher.matches()) next = Math.max(next, Integer.parseInt(matcher.group(1)) + 1);
        }
        var name = String.format("rec_%03d", next);
        Files.write(dir.resolve(name + ".bin"), data);
        return name;
    }

    @Nullable
    public static byte[] read(MinecraftServer server, UUID tapeId, String name) {
        if (!name.matches("rec_\\d{3}")) return null;
        var file = tapeDir(server, tapeId).resolve(name + ".bin");
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to read recording {} from tape {}", name, tapeId, e);
            return null;
        }
    }

    public static boolean delete(MinecraftServer server, UUID tapeId, String name) {
        if (!name.matches("rec_\\d{3}")) return false;
        try {
            return Files.deleteIfExists(tapeDir(server, tapeId).resolve(name + ".bin"));
        } catch (IOException e) {
            CCTV.LOGGER.error("Failed to delete recording {} from tape {}", name, tapeId, e);
            return false;
        }
    }

    private static boolean isRecording(Path path) {
        return RECORDING_NAME.matcher(path.getFileName().toString()).matches();
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
