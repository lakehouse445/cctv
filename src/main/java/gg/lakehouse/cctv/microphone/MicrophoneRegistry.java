package gg.lakehouse.cctv.microphone;

import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** All loaded microphones, queried by the voice plugin from the voice network thread. */
public final class MicrophoneRegistry {
    private static final Set<MicrophoneBlockEntity> MICROPHONES = ConcurrentHashMap.newKeySet();

    private MicrophoneRegistry() {
    }

    static void add(MicrophoneBlockEntity microphone) {
        MICROPHONES.add(microphone);
    }

    static void remove(MicrophoneBlockEntity microphone) {
        MICROPHONES.remove(microphone);
    }

    /** Listening microphones in a level, for the ambient simulator. */
    public static List<MicrophoneBlockEntity> listening(Level level) {
        var result = new ArrayList<MicrophoneBlockEntity>();
        for (var microphone : MICROPHONES) {
            if (microphone.getLevel() == level && !microphone.isRemoved() && microphone.isListening()) {
                result.add(microphone);
            }
        }
        return result;
    }

    public static List<MicrophoneBlockEntity> near(Level level, double x, double y, double z, double range) {
        var result = new ArrayList<MicrophoneBlockEntity>();
        for (var microphone : MICROPHONES) {
            if (microphone.getLevel() != level || microphone.isRemoved()) continue;
            var pos = microphone.getBlockPos();
            double dx = pos.getX() + 0.5 - x;
            double dy = pos.getY() + 0.5 - y;
            double dz = pos.getZ() + 0.5 - z;
            if (dx * dx + dy * dy + dz * dz <= range * range) result.add(microphone);
        }
        return result;
    }
}
