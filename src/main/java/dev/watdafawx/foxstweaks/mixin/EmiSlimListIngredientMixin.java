package dev.watdafawx.foxstweaks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.watdafawx.foxstweaks.emi.ListOwner;
import dev.watdafawx.foxstweaks.emi.TagShare;

/**
 * EMI Slim: fullList is a flat copy of the child ingredients' stacks. When a shared tag list swaps a slot
 * ({@link TagShare#beforeChange}), the flat copy is rebuilt on next use so it matches the child lists again.
 */
@Mixin(value = ListEmiIngredient.class, remap = false)
public abstract class EmiSlimListIngredientMixin implements ListOwner {
    @Shadow
    @Final
    private List<? extends EmiIngredient> ingredients;

    @Shadow
    @Final
    @Mutable
    private List<EmiStack> fullList;

    @Unique
    private boolean foxstweaks$hasShared;
    @Unique
    private int foxstweaks$epoch;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void foxstweaks$init(List<? extends EmiIngredient> list, long amount, CallbackInfo ci) {
        foxstweaks$epoch = TagShare.epoch;
        for (var ing : ingredients) {
            if (ing instanceof TagEmiIngredient) { // may hold shared stacks (even after its own split)
                foxstweaks$hasShared = true;
                break;
            }
        }
    }

    @Inject(method = "getEmiStacks", at = @At("HEAD"))
    private void foxstweaks$beforeGet(CallbackInfoReturnable<List<EmiStack>> cir) {
        if (foxstweaks$hasShared && foxstweaks$epoch != TagShare.epoch) {
            foxstweaks$epoch = TagShare.epoch;
            fullList = ingredients.stream().flatMap(i -> i.getEmiStacks().stream()).toList();
        }

        TagShare.track(this);
    }

    @Override
    public List<? extends EmiIngredient> foxstweaks$getIngredients() {
        return ingredients;
    }
}
