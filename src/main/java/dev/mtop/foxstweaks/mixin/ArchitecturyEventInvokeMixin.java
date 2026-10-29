package dev.mtop.foxstweaks.mixin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.Config;

/**
 * Architectury's event proxies call every listener through {@code EventFactory.invokeMethod}, which does
 * {@code MethodHandles.lookup().unreflect(method).bindTo(listener).invokeWithArguments(args)} - a fresh method
 * handle lookup, a bound handle and the slowest invocation path, per listener, per event fire. In a spark
 * profile that was a few percent of both frame time and server tick time.
 *
 * <p>{@code Method#invoke} does the same call far cheaper (JDK 18+ backs it with cached method handles). The
 * listener's exception is unwrapped from {@code InvocationTargetException} and rethrown as-is, matching what
 * {@code invokeWithArguments} threw. If reflection is denied access, the original path runs instead.
 */
@Mixin(targets = "dev.architectury.event.EventFactory", remap = false)
public abstract class ArchitecturyEventInvokeMixin {
    @Inject(method = "invokeMethod", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$directInvoke(Object listener, Method method, Object[] args,
                                                CallbackInfoReturnable<Object> cir) {
        if (!Config.enabled(Config.ARCHITECTURY_FAST_EVENTS)) {
            return;
        }

        Object result;
        try {
            result = method.invoke(listener, args);
        } catch (IllegalAccessException e) {
            return;
        } catch (InvocationTargetException e) {
            throw foxstweaks$sneaky(e.getCause());
        }
        cir.setReturnValue(result);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException foxstweaks$sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
