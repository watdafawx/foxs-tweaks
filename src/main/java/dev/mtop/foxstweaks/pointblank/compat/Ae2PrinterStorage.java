package dev.mtop.foxstweaks.pointblank.compat;

import java.util.Map;
import java.util.function.Predicate;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import dev.mtop.foxstweaks.pointblank.PrinterStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Reads Applied Energistics 2 storage for the printer. Only loaded once AE2 is known to be present.
 *
 * <p>An ME Interface has two views: its item handler shows only its stocked slots, but its
 * {@code ME_STORAGE} capability is the whole network when the interface has no config set (AE2's
 * {@code InterfaceLogic#getInventory}), or its stock when it does. Asking for the second is what
 * lets an unconfigured interface serve anything the network holds. Addons that build on AE2's
 * interface logic (ExtendedAE, Advanced AE, ...) expose the same capability, so they need nothing extra.
 */
public final class Ae2PrinterStorage {
    private Ae2PrinterStorage() {
    }

    /** The ME storage at {@code pos}, or {@code null} if it has none. Cable parts answer per side. */
    public static PrinterStorage.Source at(Level level, BlockPos pos) {
        var storage = level.getCapability(AECapabilities.ME_STORAGE, pos, null);
        if (storage == null) {
            for (var side : Direction.values()) {
                storage = level.getCapability(AECapabilities.ME_STORAGE, pos, side);
                if (storage != null)
                    break;
            }
        }

        return storage == null ? null : new MeSource(storage);
    }

    private record MeSource(MEStorage storage) implements PrinterStorage.Source {
        @Override
        public int available(Predicate<ItemStack> match, int limit) {
            long found = 0;
            for (var entry : storage.getAvailableStacks()) {
                if (entry.getKey() instanceof AEItemKey item && match.test(item.toStack()))
                    found += entry.getLongValue();
            }

            return (int) Math.min(found, Integer.MAX_VALUE);
        }

        @Override
        public int take(Predicate<ItemStack> match, int amount) {
            long remaining = amount;
            for (var entry : storage.getAvailableStacks()) {
                if (remaining <= 0)
                    break;
                if (entry.getKey() instanceof AEItemKey item && match.test(item.toStack()))
                    remaining -= storage.extract(item, Math.min(remaining, entry.getLongValue()), Actionable.MODULATE, IActionSource.empty());
            }

            return (int) (amount - remaining);
        }

        @Override
        public void tally(Predicate<Item> wanted, Map<Item, Integer> out) {
            for (var entry : storage.getAvailableStacks()) {
                if (entry.getKey() instanceof AEItemKey item && wanted.test(item.getItem()))
                    out.merge(item.getItem(), (int) Math.min(entry.getLongValue(), Integer.MAX_VALUE), PrinterStorage::add);
            }
        }
    }
}
