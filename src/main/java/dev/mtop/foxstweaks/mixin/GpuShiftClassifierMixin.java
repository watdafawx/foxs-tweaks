package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.Config;

/**
 * GPUShift classifies every particle as it is added: {@code ParticleCategoryClassifier.classify} walks the
 * particle's class hierarchy, lower-casing each class name to look for "rain"/"snow"/"block"-like tokens and
 * checking two mods' packages. For a given particle class (with mods fixed after startup) the answer never
 * changes, yet it ran per spawn - about 2% of frame time under heavy particle load.
 *
 * <p>The result is cached per class in a {@link ClassValue}. Calls without a {@code ModPresence} are left alone,
 * since that argument changes the answer.
 */
@Mixin(targets = "com.orf.gpushift.core.ParticleCategoryClassifier", remap = false)
public abstract class GpuShiftClassifierMixin {
    @Unique private static final ClassValue<Object[]> foxstweaks$cache = new ClassValue<>() {
        @Override
        protected Object[] computeValue(Class<?> type) {
            return new Object[1];
        }
    };

    @Inject(method = "classify", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$cached(Class<?> particleClass, @Coerce Object presence,
                                          CallbackInfoReturnable<Object> cir) {
        if (particleClass == null || presence == null || !Config.enabled(Config.GPUSHIFT_CLASSIFIER_CACHE)) {
            return;
        }

        Object cached = foxstweaks$cache.get(particleClass)[0];
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "classify", at = @At("RETURN"))
    private static void foxstweaks$store(Class<?> particleClass, @Coerce Object presence,
                                         CallbackInfoReturnable<Object> cir) {
        if (particleClass != null && presence != null && Config.enabled(Config.GPUSHIFT_CLASSIFIER_CACHE)) {
            foxstweaks$cache.get(particleClass)[0] = cir.getReturnValue();
        }
    }
}
