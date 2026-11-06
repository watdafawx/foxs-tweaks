package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.registry.EmiStackList;
import dev.watdafawx.foxstweaks.emi.EmiSlim;

/** EMI Slim: EmiStackList.clear() runs at the start of every EMI reload (and on leaving a world). */
@Mixin(value = EmiStackList.class, remap = false)
public abstract class EmiSlimStackListMixin {
    @Inject(method = "clear", at = @At("HEAD"))
    private static void foxstweaks$reset(CallbackInfo ci) {
        EmiSlim.latch();
    }
}
