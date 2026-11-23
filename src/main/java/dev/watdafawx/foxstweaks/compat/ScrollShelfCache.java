package dev.watdafawx.foxstweaks.compat;

import dev.watdafawx.foxstweaks.Config;

import java.lang.reflect.Field;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Caches {@code ScrollShelfBlockEntity$1#getAvailableScrolls()} per block entity until the shelf
 * mutates. Without this, {@code ItemHandlerHelper.insertItemStacked} (used by Logistics Networks,
 * pipes, etc.) calls {@code getStackInSlot(i)} for every slot and rebuilds the entire scroll list
 * each time — O(N^2), causing 300ms-5s server stalls.
 */
public final class ScrollShelfCache {
    private static final WeakHashMap<Object, List<?>> CACHE = new WeakHashMap<>();
    private static volatile Field ownerField;
    private static volatile boolean ownerLookupFailed;

    private ScrollShelfCache() {
    }

    public static boolean enabled() {
        return Config.enabled(Config.SCROLL_SHELF_CACHE);
    }

    /** Resolves the enclosing {@code ScrollShelfBlockEntity} from its anonymous {@code IItemHandler}. */
    public static Object resolveOwner(Object handler) {
        if (handler == null || ownerLookupFailed)
            return null;
        Field f = ownerField;
        if (f == null) {
            try {
                f = handler.getClass().getDeclaredField("this$0");
                f.setAccessible(true);
                ownerField = f;
            } catch (Throwable t) {
                ownerLookupFailed = true;
                return null;
            }
        }
        try {
            return f.get(handler);
        } catch (Throwable t) {
            return null;
        }
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
