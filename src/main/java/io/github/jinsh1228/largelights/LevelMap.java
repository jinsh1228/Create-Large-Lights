package io.github.jinsh1228.largelights;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import net.minecraft.world.level.Level;

/**
 * Per-level state, weakly keyed by identity. Light queries read it without a lock (there are only a few levels);
 * the rare changes take the lock and publish a new array.
 */
final class LevelMap<V> {
    private record Slot<V>(WeakReference<Level> level, V value) {
    }

    private final Map<Level, V> values = new WeakHashMap<>();
    @SuppressWarnings("unchecked")
    private volatile Slot<V>[] slots = new Slot[0];

    V get(Level level) {
        for (Slot<V> slot : slots) {
            if (slot.level.get() == level) return slot.value;
        }
        return null;
    }

    synchronized V computeIfAbsent(Level level, Supplier<V> create) {
        V found = values.get(level);
        if (found == null) {
            found = create.get();
            values.put(level, found);
            publish();
        }
        return found;
    }

    synchronized V remove(Level level) {
        V removed = values.remove(level);
        if (removed != null) publish();
        return removed;
    }

    /** The weak map has already dropped collected levels, so a republish also releases their values. */
    @SuppressWarnings("unchecked")
    private void publish() {
        Slot<V>[] next = new Slot[values.size()];
        int i = 0;
        for (var entry : values.entrySet()) {
            if (i == next.length) break;
            next[i++] = new Slot<>(new WeakReference<>(entry.getKey()), entry.getValue());
        }
        slots = i == next.length ? next : java.util.Arrays.copyOf(next, i);
    }
}
