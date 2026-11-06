package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.api.stack.ItemEmiStack;
import dev.watdafawx.foxstweaks.emi.PatchInterner;
import net.minecraft.core.component.DataComponentPatch;

/** EMI Slim: identical component patches share one object (the stack itself stays its own object). */
@Mixin(value = ItemEmiStack.class, remap = false)
public abstract class EmiSlimItemStackMixin {
    @Shadow
    @Final
    @Mutable
    private DataComponentPatch componentChanges;

    @Inject(method = "<init>(Lnet/minecraft/world/item/Item;Lnet/minecraft/core/component/DataComponentPatch;J)V", at = @At("RETURN"))
    private void foxstweaks$internPatch(CallbackInfo ci) {
        componentChanges = PatchInterner.intern(componentChanges);
    }
}
