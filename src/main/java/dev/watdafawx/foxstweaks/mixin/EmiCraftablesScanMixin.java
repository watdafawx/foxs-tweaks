package dev.watdafawx.foxstweaks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.watdafawx.foxstweaks.client.compat.EmiCraftableScan;

/** Marks the span of EMI's craftables rebuild for {@link Ae2EmiQuickFillMixin}. */
@Mixin(targets = "dev.emi.emi.api.recipe.EmiPlayerInventory", remap = false)
public abstract class EmiCraftablesScanMixin {
    @WrapMethod(method = "getCraftables")
    private List<?> foxstweaks$markScan(Operation<List<?>> original) {
        boolean outer = EmiCraftableScan.active;
        EmiCraftableScan.active = true;
        try {
            return original.call();
        } finally {
            EmiCraftableScan.active = outer;
        }
    }
}
