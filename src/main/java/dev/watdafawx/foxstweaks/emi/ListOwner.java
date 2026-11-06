package dev.watdafawx.foxstweaks.emi;

import java.util.List;

import dev.emi.emi.api.stack.EmiIngredient;

/** Added to ListEmiIngredient by {@code EmiSlimListIngredientMixin}. */
public interface ListOwner {
    List<? extends EmiIngredient> foxstweaks$getIngredients();
}
