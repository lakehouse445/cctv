package gg.lakehouse.cctv.camera;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, access-ordered cache for textures keyed by player-controlled
 * input (sign text, banner patterns, map contents). An unbounded map here
 * is a slow memory leak any player can drive with a stack of signs.
 */
final class TextureLru {
    private TextureLru() {
    }

    static <K, V> Map<K, V> create(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        });
    }
}
