package gg.lakehouse.cctv;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-stop cleanup hooks. Optional integrations (voice chat) register
 * here so CCTV can trigger their teardown without classloading them -
 * touching their classes directly would crash when the dependency is absent.
 */
public final class ServerCleanup {
    private static final List<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    private ServerCleanup() {
    }

    public static void register(Runnable hook) {
        HOOKS.add(hook);
    }

    public static void run() {
        for (var hook : HOOKS) {
            try {
                hook.run();
            } catch (Exception e) {
                CCTV.LOGGER.warn("Server-stop cleanup hook failed", e);
            }
        }
    }
}
