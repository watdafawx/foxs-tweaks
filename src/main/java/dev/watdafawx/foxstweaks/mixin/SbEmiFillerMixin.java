package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.watdafawx.foxstweaks.client.compat.SbEmiFill;

/**
 * EMI's fill entry points (the "+" button's click and EMI's craft keybinds both end here): swap
 * Sophisticated Core's split recipe for a full 3x3 copy. See {@link SbEmiFill}.
 */
@Mixin(targets = "dev.emi.emi.registry.EmiRecipeFiller", remap = false)
public abstract class SbEmiFillerMixin {
    @ModifyVariable(method = "getFirstValidHandler", at = @At("HEAD"), argsOnly = true, require = 0)
    private static EmiRecipe foxstweaks$fullRecipeForHandler(EmiRecipe recipe) {
        return SbEmiFill.forFill(recipe);
    }

    @ModifyVariable(method = "performFill", at = @At("HEAD"), argsOnly = true, require = 0)
    private static EmiRecipe foxstweaks$fullRecipeForFill(EmiRecipe recipe) {
        return SbEmiFill.forFill(recipe);
    }
}
