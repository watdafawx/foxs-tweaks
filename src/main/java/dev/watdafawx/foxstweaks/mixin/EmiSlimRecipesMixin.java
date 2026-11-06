package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.registry.EmiRecipes;
import dev.watdafawx.foxstweaks.emi.EmiSlim;

/** EMI Slim: logs how much was shared once EMI's recipes are baked. */
@Mixin(value = EmiRecipes.class, remap = false)
public abstract class EmiSlimRecipesMixin {
    @Inject(method = "bake", at = @At("TAIL"))
    private static void foxstweaks$logStats(CallbackInfo ci) {
        EmiSlim.logStats();
    }
}
