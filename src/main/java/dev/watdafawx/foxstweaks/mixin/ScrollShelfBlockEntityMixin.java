package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.compat.ScrollShelfCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidates {@link ScrollShelfCache} whenever a Scroll Shelf mutates or reloads. */
@Pseudo
@Mixin(targets = "com.mumu17.scrollshelf.shelf.ScrollShelfBlockEntity", remap = false)
public class ScrollShelfBlockEntityMixin {
    @Inject(method = { "setChanged", "loadScrollData", "extractScroll", "deleteScroll", "craftAndExtractScroll" },
            at = @At("HEAD"), require = 0)
    private void foxstweaks$invalidateScrollShelf(CallbackInfo ci) {
        ScrollShelfCache.invalidate(this);
    }

    @Inject(method = "depositScroll", at = @At("RETURN"), require = 0)
    private void foxstweaks$invalidateOnDeposit(CallbackInfoReturnable<Boolean> cir) {
        ScrollShelfCache.invalidate(this);
    }
}
