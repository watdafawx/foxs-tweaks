package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.compat.ScrollShelfCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
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
    @Shadow
    @Final
    private Object this$0;

    @Inject(method = "getAvailableScrolls", at = @At("HEAD"), cancellable = true, require = 0)
    private void foxstweaks$getCachedScrolls(CallbackInfoReturnable<List<?>> cir) {
        if (!ScrollShelfCache.enabled())
            return;

        List<?> cached = ScrollShelfCache.get(this.this$0);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getAvailableScrolls", at = @At("RETURN"), require = 0)
    private void foxstweaks$cacheScrolls(CallbackInfoReturnable<List<?>> cir) {
        if (ScrollShelfCache.enabled()) {
            ScrollShelfCache.put(this.this$0, cir.getReturnValue());
        }
    }

    @Inject(method = { "insertItem", "extractItem" }, at = @At("RETURN"), require = 0)
    private void foxstweaks$invalidateAfterTransfer(CallbackInfoReturnable<?> cir) {
        ScrollShelfCache.invalidate(this.this$0);
    }
}
