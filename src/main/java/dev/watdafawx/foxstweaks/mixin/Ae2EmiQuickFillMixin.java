package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.client.compat.EmiCraftableScan;

/**
 * AE2's EMI handler only runs its own transfer check for the "+" fill button; EMI's craft binds
 * (CRAFTABLE) fall back to EMI's plain inventory check, so shift-click does nothing in a pattern
 * encoder or for network-only ingredients. Treat CRAFTABLE as FILL_BUTTON - except during EMI's
 * craftables rebuild, which asks this for every recipe touching any stack on the network, on every
 * network update. Running AE2's full transfer there is what froze the "AE2: EMI Quick Fill" mod.
 */
@Mixin(targets = "appeng.integration.modules.emi.AbstractRecipeHandler", remap = false)
public abstract class Ae2EmiQuickFillMixin {
    @ModifyExpressionValue(
            method = "canCraft",
            at = @At(value = "INVOKE",
                    target = "Ldev/emi/emi/api/recipe/handler/EmiCraftContext;getType()Ldev/emi/emi/api/recipe/handler/EmiCraftContext$Type;"))
    private EmiCraftContext.Type foxstweaks$fillOnCraftBind(EmiCraftContext.Type type) {
        return type == EmiCraftContext.Type.CRAFTABLE && !EmiCraftableScan.active && Config.AE2_EMI_QUICK_FILL.get()
                ? EmiCraftContext.Type.FILL_BUTTON
                : type;
    }
}
