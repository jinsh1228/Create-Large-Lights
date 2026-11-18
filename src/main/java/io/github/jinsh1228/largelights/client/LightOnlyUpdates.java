package io.github.jinsh1228.largelights.client;

/**
 * While vanilla applies received light data (mixin/LightOnlySectionsMixin), the sections it marks dirty changed only
 * their light. Shape caches (model snapshots, GPU meshes, beam reach and the sections each beam covers) need not be
 * rebuilt for them; the terrain's own rebuild is unaffected.
 */
public final class LightOnlyUpdates {
    /** Render thread only, where packets' light data is applied. */
    private static int depth;

    private LightOnlyUpdates() {
    }

    public static void run(Runnable update) {
        depth++;
        try {
            update.run();
        } finally {
            depth--;
        }
    }

    static boolean active() {
        return depth > 0;
    }
}
