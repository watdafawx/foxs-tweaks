package dev.mtop.foxstweaks.tacz;

import java.util.List;
import java.util.Map;

import dev.mtop.foxstweaks.storage.NearbyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Wraps a player's own {@link IItemHandler} with extra read-only virtual slots representing whatever
 * nearby storage (chests, AE2 ME networks, ...) holds, so TACZ's own gunsmith-table crafting logic -
 * which walks an {@link IItemHandler}'s slots directly by index, entirely unaware this class exists -
 * pays for a recipe out of storage the same way it already pays out of the player's pockets. See
 * {@code GunSmithTableMenuMixin}, which is the only place this is constructed.
 *
 * <p>One virtual slot per distinct item type found nearby; a slot's reported count can exceed the
 * item's max stack size, which is harmless since nothing here is ever rendered, only read by TACZ's
 * own crafting code. Extraction reads live nearby storage each time
 * ({@link NearbyStorage#take(java.util.function.Predicate, int)}), so it stays correct even if two
 * ingredients in one recipe happen to draw on the same item type (also true of TACZ's own handling
 * of two ingredients drawing on the same real inventory slot - not a case this patch changes).
 */
public final class NearbyBackedItemHandler implements IItemHandler {
    private final IItemHandler player;
    private final List<Map.Entry<Item, Integer>> extra;

    private NearbyBackedItemHandler(IItemHandler player, List<Map.Entry<Item, Integer>> extra) {
        this.player = player;
        this.extra = extra;
    }

    /** {@code player} unchanged if nothing nearby has anything - the common case, and the cheap path. */
    public static IItemHandler wrap(IItemHandler player, Level level, BlockPos pos, int range) {
        var tally = NearbyStorage.tally(level, pos, range, item -> true);
        return tally.isEmpty() ? player : new NearbyBackedItemHandler(player, List.copyOf(tally.entrySet()));
    }

    @Override
    public int getSlots() {
        return player.getSlots() + extra.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int n = player.getSlots();
        if (slot < n)
            return player.getStackInSlot(slot);

        var entry = extra.get(slot - n);
        return new ItemStack(entry.getKey(), entry.getValue());
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        // Virtual slots are read-only; TACZ's crafting never inserts a result back through this handler.
        return slot < player.getSlots() ? player.insertItem(slot, stack, simulate) : stack;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int n = player.getSlots();
        if (slot < n)
            return player.extractItem(slot, amount, simulate);

        var item = extra.get(slot - n).getKey();
        if (simulate)
            return new ItemStack(item, Math.min(amount, extra.get(slot - n).getValue()));

        int got = NearbyStorage.take(stack -> stack.is(item), amount);
        return got <= 0 ? ItemStack.EMPTY : new ItemStack(item, got);
    }

    @Override
    public int getSlotLimit(int slot) {
        return slot < player.getSlots() ? player.getSlotLimit(slot) : Integer.MAX_VALUE;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot < player.getSlots() && player.isItemValid(slot, stack);
    }
}
