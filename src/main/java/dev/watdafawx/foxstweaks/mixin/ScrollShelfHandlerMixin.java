package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.compat.ScrollShelfCache;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Caches {@code ScrollShelfBlockEntity$1#getAvailableScrolls()} so {@code getSlots()} and
 * {@code getStackInSlot(i)} become O(1) instead of rebuilding the entire scroll list on every slot
 * query.
 */
@Pseudo
@Mixin(targets = "com.mumu17.scrollshelf.shelf.ScrollShelfBlockEntity$1", remap = false)
public class ScrollShelfHandlerMixin {
    @Unique
    private Object foxstweaks$owner;

    @Unique
    private Object foxstweaks$getOwner() {
        Object owner = this.foxstweaks$owner;
        if (owner == null) {
            owner = ScrollShelfCache.resolveOwner(this);
            this.foxstweaks$owner = owner;
        }
        return owner;
    }

    @Inject(method = "getAvailableScrolls", at = @At("HEAD"), cancellable = true, require = 0)
    private void foxstweaks$getCachedScrolls(CallbackInfoReturnable<List<?>> cir) {
        if (!ScrollShelfCache.enabled())
            return;

        List<?> cached = ScrollShelfCache.get(foxstweaks$getOwner());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getAvailableScrolls", at = @At("RETURN"), require = 0)
    private void foxstweaks$cacheScrolls(CallbackInfoReturnable<List<?>> cir) {
        if (ScrollShelfCache.enabled()) {
            ScrollShelfCache.put(foxstweaks$getOwner(), cir.getReturnValue());
        }
    }

    @Inject(method = "insertItem", at = @At("RETURN"), require = 0)
    private void foxstweaks$invalidateAfterInsert(int slot, ItemStack stack, boolean simulate,
            CallbackInfoReturnable<ItemStack> cir) {
        if (!simulate) {
            ScrollShelfCache.invalidate(foxstweaks$getOwner());
        }
    }

    @Inject(method = "extractItem", at = @At("RETURN"), require = 0)
    private void foxstweaks$invalidateAfterExtract(int slot, int amount, boolean simulate,
            CallbackInfoReturnable<ItemStack> cir) {
        if (!simulate) {
            ScrollShelfCache.invalidate(foxstweaks$getOwner());
        }
    }
}
