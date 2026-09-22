package dev.mtop.foxstweaks.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import dev.mtop.foxstweaks.storage.compat.Ae2NearbyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Finds and drains the inventories around a crafting station while it works, so a recipe can be paid
 * for out of nearby storage and not just the player's own pockets. Used by both the Point Blank
 * weapon printer ({@code pointblank/mixin/*}) and TACZ's gunsmith table
 * ({@code tacz/mixin/*} - shared by every gun pack's workbench, since they are all the same TACZ
 * block entity with a different {@code BlockId}).
 *
 * <p>Same idea as Ars Nouveau's scribe's table: scan a box around a point and pull from whatever
 * holds items. Anything exposing an item handler counts (chests, barrels, machines). Applied
 * Energistics 2 needs its own path, because an ME Interface's item handler is only its stocked slots
 * while the network hangs off a separate capability - see {@link Ae2NearbyStorage}.
 *
 * <p>A scan is centred on whatever {@link #begin} was last called with, so callers bracket the whole
 * span of a craft with {@link #begin} / {@link #end}; {@link #active} tells other code whether a scan
 * is currently in scope. Never touches Point Blank or TACZ - their mixins own those dependencies.
 */
public final class NearbyStorage {
    /** One place items can be counted in and taken from. */
    public interface Source {
        /** How many matching items this could give, stopping early once {@code limit} is reached. */
        int available(Predicate<ItemStack> match, int limit);

        /** Removes up to {@code amount} matching items; returns how many it got. */
        int take(Predicate<ItemStack> match, int amount);

        /** Adds what this holds of the {@code wanted} items into {@code out}. */
        void tally(Predicate<Item> wanted, Map<Item, Integer> out);
    }

    /** Adds without overflowing: an ME network can hold more than an int. */
    public static int add(int a, int b) {
        return (int) Math.min((long) a + b, Integer.MAX_VALUE);
    }

    private static final int VERTICAL_RANGE = 2;

    private static Level level;
    private static BlockPos origin;
    private static int range;
    private static Boolean ae2Loaded;

    /** Set while a recursive call re-enters the patched method, so the patch does not apply twice. */
    private static boolean busy;

    private NearbyStorage() {
    }

    /** {@code searchRange}: how far to scan, in blocks - each caller's own config value. */
    public static void begin(Level craftingLevel, BlockPos craftingPos, int searchRange) {
        level = craftingLevel;
        origin = craftingPos;
        range = searchRange;
    }

    public static void end() {
        level = null;
        origin = null;
    }

    /**
     * True only on the server, while a craft that called {@link #begin} is in progress. Callers gate
     * this behind their own config toggle themselves - {@code begin} is only ever invoked when a
     * feature's own toggle is on, so this needs no toggle of its own.
     */
    public static boolean active() {
        return !busy && level != null && !level.isClientSide;
    }

    public static void enter() {
        busy = true;
    }

    public static void exit() {
        busy = false;
    }

    /**
     * How many matching items nearby storage could give. Plain inventories add up; ME sources do not,
     * because two interfaces on one network each show the whole network and summing them would count
     * it twice - so only the best one counts, and {@link #take} then draws from whichever has it.
     */
    public static int count(Predicate<ItemStack> match, int limit) {
        var scan = scan(level, origin);

        int found = 0;
        for (var source : scan.inventories) {
            if (found >= limit)
                return found;
            found += source.available(match, limit - found);
        }

        int best = 0;
        for (var source : scan.networks)
            best = Math.max(best, source.available(match, limit - found));

        return found + best;
    }

    /** Removes up to {@code amount} matching items from nearby storage; returns how many it got. */
    public static int take(Predicate<ItemStack> match, int amount) {
        var scan = scan(level, origin);

        int remaining = amount;
        for (var source : scan.inventories)
            remaining -= remaining > 0 ? source.take(match, remaining) : 0;
        for (var source : scan.networks)
            remaining -= remaining > 0 ? source.take(match, remaining) : 0;

        return amount - remaining;
    }

    /**
     * What storage around {@code pos} holds of the {@code wanted} items - {@code item -> true} for
     * everything. For a player looking at the crafting station ({@code PrinterNetwork}), or for
     * building a one-off composite {@link IItemHandler} over a single synchronous craft
     * ({@code tacz.NearbyBackedItemHandler}). Combined the way {@link #count} does: inventories add
     * up, ME networks are not summed.
     */
    public static Map<Item, Integer> tally(Level craftingLevel, BlockPos pos, int searchRange, Predicate<Item> wanted) {
        var scan = scan(craftingLevel, pos, searchRange);

        var total = new HashMap<Item, Integer>();
        for (var source : scan.inventories)
            source.tally(wanted, total);

        var best = new HashMap<Item, Integer>();
        for (var source : scan.networks) {
            var one = new HashMap<Item, Integer>();
            source.tally(wanted, one);
            one.forEach((item, count) -> best.merge(item, count, Math::max));
        }
        best.forEach((item, count) -> total.merge(item, count, NearbyStorage::add));

        return total;
    }

    private record Scan(List<Source> inventories, List<Source> networks) {
    }

    /**
     * A double chest is reached through both halves and so counted twice; at worst a craft that was
     * exactly on the edge is under-charged. A block that shows up as ME storage is not also read as
     * an inventory: an interface's stocked slots are part of what its ME view already offers.
     */
    private static Scan scan(Level level, BlockPos center) {
        return scan(level, center, range <= 0 ? 6 : range);
    }

    /** {@code tally} passes its own range explicitly, since it never calls {@link #begin}. */
    private static Scan scan(Level level, BlockPos center, int range) {
        var scan = new Scan(new ArrayList<>(), new ArrayList<>());

        for (var pos : BlockPos.betweenClosed(center.offset(-range, -VERTICAL_RANGE, -range), center.offset(range, VERTICAL_RANGE, range))) {
            if (pos.equals(center) || !level.isLoaded(pos))
                continue;

            if (isAe2Loaded()) {
                var network = Ae2NearbyStorage.at(level, pos);
                if (network != null) {
                    scan.networks.add(network);
                    continue;
                }
            }

            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler != null) {
                scan.inventories.add(new HandlerSource(handler));
                continue;
            }

            // No handler on the unsided face: ask side by side, which is how a part on a cable answers.
            for (var side : Direction.values()) {
                handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
                if (handler != null)
                    scan.inventories.add(new HandlerSource(handler));
            }
        }

        return scan;
    }

    private static boolean isAe2Loaded() {
        if (ae2Loaded == null)
            ae2Loaded = ModList.get().isLoaded("ae2");

        return ae2Loaded;
    }

    private record HandlerSource(IItemHandler handler) implements Source {
        @Override
        public int available(Predicate<ItemStack> match, int limit) {
            int found = 0;
            for (int slot = 0; slot < handler.getSlots() && found < limit; slot++) {
                var stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && match.test(stack))
                    found += handler.extractItem(slot, stack.getCount(), true).getCount();
            }

            return found;
        }

        @Override
        public int take(Predicate<ItemStack> match, int amount) {
            int remaining = amount;
            for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
                var stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && match.test(stack))
                    remaining -= handler.extractItem(slot, Math.min(remaining, stack.getCount()), false).getCount();
            }

            return amount - remaining;
        }

        @Override
        public void tally(Predicate<Item> wanted, Map<Item, Integer> out) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && wanted.test(stack.getItem()))
                    out.merge(stack.getItem(), stack.getCount(), NearbyStorage::add);
            }
        }
    }
}
