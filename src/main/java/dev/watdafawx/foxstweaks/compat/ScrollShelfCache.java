package dev.watdafawx.foxstweaks.compat;

import dev.watdafawx.foxstweaks.Config;

import java.util.List;
import java.util.WeakHashMap;

/**
 * Caches {@code ScrollShelfBlockEntity$1#getAvailableScrolls()} per block entity until the shelf
 * mutates. Without this, {@code ItemHandlerHelper.insertItemStacked} (used by Logistics Networks,
 * pipes, etc.) calls {@code getStackInSlot(i)} for every slot and rebuilds the entire scroll list
 * each time — O(N^2), causing 300ms-1.25s server stalls.
 */
public final class ScrollShelfCache {
    private static final WeakHashMap<Object, List<?>> CACHE = new WeakHashMap<>();

    private ScrollShelfCache() {
    }

    public static boolean enabled() {
        return Config.enabled(Config.SCROLL_SHELF_CACHE);
    }

    public static synchronized List<?> get(Object shelf) {
        return shelf == null ? null : CACHE.get(shelf);
    }

    public static synchronized void put(Object shelf, List<?> scrolls) {
        if (shelf != null && scrolls != null) {
            CACHE.put(shelf, scrolls);
        }
    }

    public static synchronized void invalidate(Object shelf) {
        if (shelf != null) {
            CACHE.remove(shelf);
        }
    }
}
