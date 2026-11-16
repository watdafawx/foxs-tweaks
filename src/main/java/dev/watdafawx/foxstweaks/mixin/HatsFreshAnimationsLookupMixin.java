package dev.watdafawx.foxstweaks.mixin;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.Config;

/**
 * Hats' Fresh Animations compat reads a model part's name through {@code readString(Object, String)}, which walks
 * the part's class chain calling {@code Class.getDeclaredField(name)} and treats a missing field as "try the
 * superclass". With Fresh Animations absent that lookup fails for nearly every part on every draw, so each frame
 * builds and throws thousands of {@code NoSuchFieldException}s (stack trace included) while rendering any model
 * with a head - chests, entities, players.
 *
 * <p>This resolves the field once per (class, name) and caches the result, <em>including "no such field"</em>,
 * then only reads the live value. Same lookup order and result as the original (first String-typed declared field
 * of that name up the chain, null when none or unreadable), so hat behaviour is unchanged. Any unexpected failure
 * falls through to the original method.
 */
@Mixin(targets = "com.astryxion.hats.client.compat.FreshAnimationsHatCompat", remap = false)
public abstract class HatsFreshAnimationsLookupMixin {
    /** Marks a (class, name) with no usable String field. */
    @Unique private static final Object foxstweaks$NONE = new Object();
    @Unique private static final ClassValue<Map<String, Object>> foxstweaks$FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, Object> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @Unique
    private static Object foxstweaks$resolve(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // same as the original: move on to the superclass
            }
        }
        return foxstweaks$NONE;
    }

    @Inject(method = "readString", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$cachedRead(Object target, String name, CallbackInfoReturnable<String> cir) {
        if (!Config.HATS_FIELD_LOOKUP_CACHE.get() || target == null || name == null) {
            return;
        }
        try {
            Object entry = foxstweaks$FIELDS.get(target.getClass())
                    .computeIfAbsent(name, n -> foxstweaks$resolve(target.getClass(), n));
            if (entry == foxstweaks$NONE) {
                cir.setReturnValue(null);
                return;
            }
            Object value = ((Field) entry).get(target);
            cir.setReturnValue(value instanceof String s ? s : null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // let the original method handle it
        }
    }
}
