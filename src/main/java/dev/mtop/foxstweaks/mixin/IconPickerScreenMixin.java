package dev.mtop.foxstweaks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.client.compat.IconNameCache;
import net.minecraft.world.item.Item;

/**
 * EZActions' icon picker keeps its item index for the whole session, but builds it at 320 items and
 * resolves names at 64 items per tick. With a thousand mods that is tens of seconds of "Indexing" /
 * "Preparing names" after every launch, and none of it survives a restart.
 *
 * <p>Targeted by name, not class, so EZActions is never a compile dependency; a pack without it
 * simply never applies this mixin. All private-member injections are looked up by name only, which
 * is why a mismatch after an EZActions update fails soft (the picker just goes back to being slow).
 */
@Mixin(targets = "org.z2six.ezactions.gui.editor.IconPickerScreen", remap = false)
public abstract class IconPickerScreenMixin {
    /** Building the whole index is a few tens of ms even for 60k items; the stock budget is 1.5 ms. */
    private static final long INDEX_BUDGET_NS = 25_000_000L;

    @Shadow
    private int vanillaHydratedCount;

    @Shadow
    private List<?> localVanilla;

    @ModifyVariable(method = "pumpVanillaBuild", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int foxstweaks$unlimitedIndexItems(int maxItems) {
        return Config.ICON_PICKER_CACHE.get() ? Integer.MAX_VALUE : maxItems;
    }

    @ModifyVariable(method = "pumpVanillaBuild", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static long foxstweaks$longerIndexBudget(long budgetNs) {
        return Config.ICON_PICKER_CACHE.get() ? Math.max(budgetNs, INDEX_BUDGET_NS) : budgetNs;
    }

    /** 64 per tick / 18 per frame is sized for uncached name lookups; cached ones are nearly free. */
    @ModifyVariable(method = "processHydrationBudget", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int foxstweaks$moreNamesPerPass(int maxEntries) {
        return Config.ICON_PICKER_CACHE.get() ? maxEntries * 64 : maxEntries;
    }

    @ModifyVariable(method = "processHydrationBudget", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long foxstweaks$longerNameBudget(long budgetNs) {
        return Config.ICON_PICKER_CACHE.get() ? budgetNs * 3 : budgetNs;
    }

    /**
     * Once every name is resolved, {@code nextBackgroundHydrationEntry} still walks the entire list
     * looking for one that isn't - twice a frame, for as long as the picker is open.
     */
    @Inject(method = "nextBackgroundHydrationEntry", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$skipRescan(CallbackInfoReturnable<Object> cir) {
        if (Config.ICON_PICKER_CACHE.get() && vanillaHydratedCount >= localVanilla.size()) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "safeName", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$cachedName(Item item, CallbackInfoReturnable<String> cir) {
        if (!Config.ICON_PICKER_CACHE.get() || item == null) {
            return;
        }

        var cached = IconNameCache.get(item);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "safeName", at = @At("RETURN"))
    private static void foxstweaks$rememberName(Item item, CallbackInfoReturnable<String> cir) {
        if (Config.ICON_PICKER_CACHE.get() && item != null) {
            IconNameCache.put(item, cir.getReturnValue());
        }
    }

    @Inject(method = "onClose", at = @At("HEAD"))
    private void foxstweaks$saveNames(CallbackInfo ci) {
        if (Config.ICON_PICKER_CACHE.get()) {
            IconNameCache.saveIfDirty();
        }
    }
}
