package dev.watdafawx.foxstweaks.mixin;

import java.util.Map;
import java.util.WeakHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.world.entity.LivingEntity;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Curios Performance Fix keeps a per-entity snapshot of equipped curios, but {@code getFromHandler} throws it away
 * whenever the {@code ICuriosItemHandler} passed in is not the very instance it was built from. Curios creates a
 * new {@code CurioInventoryCapability} wrapper on every capability lookup, so callers almost never pass the same
 * instance twice and the snapshot was rebuilt on most calls (the radar's invisibility check alone did it per
 * entity per frame).
 *
 * <p>Every wrapper for one entity reads the same curios data attachment, so this hands the cache one wrapper per
 * entity. It is replaced after a second, matching the cache's own {@code SAFETY_REFRESH_NANOS}, so a wrapper made
 * stale by a replaced attachment cannot outlive the cache's normal refresh.
 */
@Mixin(targets = "com.curiosperformancefix.client.ClientCuriosEquipmentCache", remap = false)
public abstract class CuriosCacheHandlerMixin {
    @Unique private static final long foxstweaks$REUSE_NANOS = 1_000_000_000L;
    @Unique private static final Map<LivingEntity, Object[]> foxstweaks$handlers = new WeakHashMap<>();

    @ModifyVariable(method = "getFromHandler", at = @At("HEAD"), argsOnly = true)
    private static ICuriosItemHandler foxstweaks$stableHandler(ICuriosItemHandler handler) {
        if (handler == null || !Config.enabled(Config.CURIOS_CACHE_HANDLER_FIX)) {
            return handler;
        }

        LivingEntity wearer = handler.getWearer();
        if (wearer == null) {
            return handler;
        }

        long now = System.nanoTime();
        synchronized (foxstweaks$handlers) {
            Object[] entry = foxstweaks$handlers.get(wearer);
            if (entry != null && now - (long) entry[1] < foxstweaks$REUSE_NANOS) {
                return (ICuriosItemHandler) entry[0];
            }
            foxstweaks$handlers.put(wearer, new Object[] {handler, now});
        }
        return handler;
    }
}
