package dev.watdafawx.foxstweaks.emi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.emi.emi.runtime.EmiTagKey;

/**
 * One stack list per tag, shared by every TagEmiIngredient of that tag.
 *
 * <p>EMI and its plugins sometimes change stacks they got from an ingredient ({@code EmiShapedRecipe.setRemainders},
 * catalyst remainders, setAmount on a picked stack). Unshared, every ingredient had its own stacks, so a change only
 * reached that one recipe. To keep that, right before a shared stack S is changed:
 * <ol>
 * <li>the ingredient S was reached through (last getEmiStacks() call on this thread) gets its own list that still
 * holds S, and</li>
 * <li>the shared list gets an unchanged copy of S in S's slot.</li>
 * </ol>
 * Then the change runs on S as normal. Whoever holds S (the caller, the owning ingredient) sees the change; every
 * other user of the tag keeps the old stack.
 */
public final class TagShare {
    private static final Map<EmiTagKey<?>, SharedTagList> CACHE = new ConcurrentHashMap<>();
    /** Last ingredient whose getEmiStacks() ran on this thread. */
    private static final ThreadLocal<Object> LAST = new ThreadLocal<>();
    /** Bumped on every slot swap; ListEmiIngredients rebuild their flat list when it moved. */
    public static volatile int epoch;

    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong built = new AtomicLong();
    private static final AtomicLong swaps = new AtomicLong();
    private static final AtomicLong unknownOwner = new AtomicLong();

    public static boolean enabled() {
        return EmiSlim.shareTags;
    }

    public static void track(Object ingredient) {
        if (EmiSlim.shareTags) {
            LAST.set(ingredient);
        }
    }

    public static List<EmiStack> get(EmiTagKey<?> key, Supplier<List<EmiStack>> original) {
        var list = CACHE.get(key);
        if (list != null) {
            hits.incrementAndGet();
            return list;
        }

        var fresh = original.get();
        if (fresh.isEmpty()) {
            return fresh; // tags not loaded yet: don't cache
        }

        list = new SharedTagList(fresh);
        var prev = CACHE.putIfAbsent(key, list);
        if (prev != null) {
            hits.incrementAndGet();
            return prev;
        }

        built.incrementAndGet();
        return list;
    }

    public static void clear() {
        CACHE.clear();
    }

    /** Called right before a stack that lives in a shared list is changed. */
    public static synchronized void beforeChange(EmiStack stack) {
        var ss = (SharedStack) stack;
        var home = ss.foxstweaks$getHome();
        if (home == null) {
            return;
        }

        var idx = home.indexOfIdentity(stack);
        ss.foxstweaks$setHome(null);
        if (idx < 0) {
            return;
        }

        var before = home.snapshot();
        var owned = giveOwnerPrivateList(LAST.get(), home, before, stack);
        var replacement = stack.copy(); // copy() only touches the new object
        ((SharedStack) replacement).foxstweaks$setHome(home);
        home.swap(idx, replacement);
        epoch++;
        swaps.incrementAndGet();
        if (!owned && unknownOwner.getAndIncrement() < 3) {
            var o = LAST.get();
            EmiSlim.LOG.info("[foxstweaks] EMI Slim: shared tag stack {} changed; owner not found (last ingredient {}). Change stays on that stack only.",
                    stack, o == null ? "none" : o.getClass().getSimpleName());
        }
    }

    /** Returns true if some ingredient now holds {@code stack} in its own list. */
    private static boolean giveOwnerPrivateList(Object owner, SharedTagList home, EmiStack[] before, EmiStack stack) {
        if (owner instanceof TagEmiIngredient tag) {
            return privatize((TagOwner) tag, home, before, stack);
        }

        if (owner instanceof ListEmiIngredient list) {
            var any = false;
            for (var ing : ((ListOwner) list).foxstweaks$getIngredients()) {
                if (ing instanceof TagEmiIngredient tag) {
                    any |= privatize((TagOwner) tag, home, before, stack);
                }
            }

            return any;
        }

        return false;
    }

    private static boolean privatize(TagOwner tag, SharedTagList home, EmiStack[] before, EmiStack stack) {
        var cur = tag.foxstweaks$getStacks();
        if (cur == home) {
            tag.foxstweaks$setStacks(List.of(before));
            return true;
        }

        // Already has its own list (an earlier change): it holds the stack if it came from here.
        for (var s : cur) {
            if (s == stack) {
                return true;
            }
        }

        return false;
    }

    static String stats() {
        return "tag lists built " + built.get() + ", reused " + hits.get() + ", stacks split off on change " + swaps.get()
                + " (owner not found " + unknownOwner.get() + ")";
    }

    private TagShare() {
    }
}
