package dev.watdafawx.foxstweaks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.watdafawx.foxstweaks.emi.TagOwner;
import dev.watdafawx.foxstweaks.emi.TagShare;

@Mixin(value = TagEmiIngredient.class, remap = false)
public abstract class EmiSlimTagIngredientMixin implements TagOwner {
    @Shadow
    private List<EmiStack> stacks;

    /** EMI Slim: remember which ingredient handed out its stacks, so a later change can find its owner. */
    @Inject(method = "getEmiStacks", at = @At("HEAD"))
    private void foxstweaks$trackOwner(CallbackInfoReturnable<List<EmiStack>> cir) {
        TagShare.track(this);
    }

    @Override
    public List<EmiStack> foxstweaks$getStacks() {
        return stacks;
    }

    @Override
    public void foxstweaks$setStacks(List<EmiStack> list) {
        stacks = list;
    }
}
