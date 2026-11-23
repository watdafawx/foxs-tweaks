package dev.watdafawx.foxstweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.watdafawx.foxstweaks.relics.RelicParticles;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Brackets every Curios {@code ICurioItem#curioTick} call with {@link RelicParticles#push}/{@code pop}
 * when the ticked stack has its "Show relic particles" option switched off.
 */
@Pseudo
@Mixin(targets = "top.theillusivec4.curios.common.capability.ItemizedCurioCapability", remap = false)
public class CurioParticleSuppressMixin {
    @WrapOperation(
            method = "curioTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ltop/theillusivec4/curios/api/type/capability/ICurioItem;curioTick(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/world/item/ItemStack;)V"),
            require = 0)
    private void foxstweaks$suppressCurioParticles(Object curioItem, Object slotContext, ItemStack stack,
                                                   Operation<Void> original) {
        if (!RelicParticles.shouldSuppressFor(stack)) {
            original.call(curioItem, slotContext, stack);
            return;
        }

        RelicParticles.push();
        try {
            original.call(curioItem, slotContext, stack);
        } finally {
            RelicParticles.pop();
        }
    }
}
