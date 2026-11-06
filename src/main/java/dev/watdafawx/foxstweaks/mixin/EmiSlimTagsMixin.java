package dev.watdafawx.foxstweaks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.runtime.EmiTagKey;
import dev.watdafawx.foxstweaks.emi.TagShare;

@Mixin(value = EmiTags.class, remap = false)
public abstract class EmiSlimTagsMixin {
    /** EMI Slim: one shared stack list per tag instead of a fresh list for every TagEmiIngredient. */
    @WrapMethod(method = "getValues")
    private static List<EmiStack> foxstweaks$shareValues(EmiTagKey<?> key, Operation<List<EmiStack>> original) {
        if (!TagShare.enabled()) {
            return original.call(key);
        }

        return TagShare.get(key, () -> original.call(key));
    }

    /** Tag contents change on reload: never share lists across it. */
    @Inject(method = "reload", at = @At("HEAD"))
    private static void foxstweaks$clearOnReload(CallbackInfo ci) {
        TagShare.clear();
    }
}
