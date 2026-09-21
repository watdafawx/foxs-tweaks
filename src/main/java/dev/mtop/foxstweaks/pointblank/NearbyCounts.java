package dev.mtop.foxstweaks.pointblank;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * The client's copy of {@link NearbyItemsPayload}. The server resends every half second while the
 * printer is open, so anything older than {@link #MAX_AGE_MS} means the screen is closed (or the
 * server does not have this mod) and the local player's inventory alone should decide again.
 *
 * <p>Plain data: nothing client-only, so it is safe to load on a dedicated server.
 */
public final class NearbyCounts {
    private static final long MAX_AGE_MS = 3000;

    private static volatile Map<Item, Integer> counts = Map.of();
    private static volatile long receivedAt;

    private NearbyCounts() {
    }

    public static void set(Map<net.minecraft.resources.ResourceLocation, Integer> byId) {
        var byItem = new HashMap<Item, Integer>();
        byId.forEach((id, count) -> BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> byItem.put(item, count)));

        counts = byItem;
        receivedAt = System.currentTimeMillis();
    }

    public static boolean fresh() {
        return System.currentTimeMillis() - receivedAt < MAX_AGE_MS;
    }

    public static int count(Predicate<ItemStack> match) {
        long total = 0;
        for (var entry : counts.entrySet()) {
            if (match.test(new ItemStack((ItemLike) entry.getKey())))
                total += entry.getValue();
        }

        return (int) Math.min(total, Integer.MAX_VALUE);
    }
}
