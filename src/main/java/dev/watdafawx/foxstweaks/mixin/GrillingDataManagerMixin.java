package dev.watdafawx.foxstweaks.mixin;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.Config;

/**
 * Kaleidoscope Grilling's {@code skewersForDisplay()} merges its three skewer maps into a new {@code HashMap} and
 * copies that into an immutable map on every call. Its {@code SkewerRecipes} helpers call it for every lookup, and
 * one of those runs inside {@code ItemRenderer.getModel} for every item drawn, so a wall of storage drawers paid for
 * the copy hundreds of times a frame (6-13% of frame time in a spark profile).
 *
 * <p>The datapack map ({@code SKEWERS}) and the script/synced map ({@code SCRIPT_SKEWERS}) are only ever replaced
 * whole with new immutable maps, and the built-in map is a constant, so the merged result stays valid while both
 * fields still hold the same instances. The result is the same immutable map the original would build.
 */
@Mixin(targets = "cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager", remap = false)
public abstract class GrillingDataManagerMixin {
    @Shadow @Final private static Map<String, Object> BUILT_IN_SKEWERS;
    @Shadow private static volatile Map<String, Object> SKEWERS;
    @Shadow private static volatile Map<String, Object> SCRIPT_SKEWERS;

    /** {datapack map, script map, merged result}, replaced whole. */
    @Unique private static volatile Object[] foxstweaks$display;

    @Inject(method = "skewersForDisplay", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$cachedDisplay(CallbackInfoReturnable<Map<String, Object>> cir) {
        if (!Config.GRILLING_SKEWER_CACHE.get()) {
            return;
        }
        Map<String, Object> skewers = SKEWERS;
        Map<String, Object> script = SCRIPT_SKEWERS;
        Object[] cached = foxstweaks$display;
        if (cached != null && cached[0] == skewers && cached[1] == script) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) cached[2];
            cir.setReturnValue(result);
            return;
        }
        Map<String, Object> merged = new HashMap<>(BUILT_IN_SKEWERS);
        merged.putAll(skewers);
        merged.putAll(script);
        Map<String, Object> result = Map.copyOf(merged);
        foxstweaks$display = new Object[] {skewers, script, result};
        cir.setReturnValue(result);
    }
}
