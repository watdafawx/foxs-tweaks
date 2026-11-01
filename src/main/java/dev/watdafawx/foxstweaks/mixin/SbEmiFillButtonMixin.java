package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.watdafawx.foxstweaks.client.compat.SbEmiFill;

/**
 * The "+" button also asks the handler directly (supported? craftable? tooltip?) with the recipe it shows.
 * Ask with the full copy instead, so the button lights up. See {@link SbEmiFill}.
 */
@Mixin(targets = "dev.emi.emi.api.widget.RecipeFillButtonWidget", remap = false)
public abstract class SbEmiFillButtonMixin {
    @ModifyArg(method = "<init>", require = 0, at = @At(value = "INVOKE",
            target = "Ldev/emi/emi/api/recipe/handler/EmiRecipeHandler;supportsRecipe(Ldev/emi/emi/api/recipe/EmiRecipe;)Z"))
    private EmiRecipe foxstweaks$supports(EmiRecipe recipe) {
        return SbEmiFill.forFill(recipe);
    }

    @ModifyArg(method = "<init>", index = 0, require = 0, at = @At(value = "INVOKE",
            target = "Ldev/emi/emi/api/recipe/handler/EmiRecipeHandler;canCraft(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/EmiCraftContext;)Z"))
    private EmiRecipe foxstweaks$canCraft(EmiRecipe recipe) {
        return SbEmiFill.forFill(recipe);
    }

    @ModifyArg(method = "<init>", index = 0, require = 0, at = @At(value = "INVOKE",
            target = "Ldev/emi/emi/api/recipe/handler/EmiRecipeHandler;getTooltip(Ldev/emi/emi/api/recipe/EmiRecipe;Ldev/emi/emi/api/recipe/handler/EmiCraftContext;)Ljava/util/List;"))
    private EmiRecipe foxstweaks$tooltip(EmiRecipe recipe) {
        return SbEmiFill.forFill(recipe);
    }
}
