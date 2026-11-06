package dev.watdafawx.foxstweaks.emi;

import java.util.List;

import dev.emi.emi.api.stack.EmiStack;

/** Added to TagEmiIngredient by {@code EmiSlimTagIngredientMixin}. */
public interface TagOwner {
    List<EmiStack> foxstweaks$getStacks();

    void foxstweaks$setStacks(List<EmiStack> stacks);
}
