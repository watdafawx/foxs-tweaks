package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.HnnSimulations;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Hostile Neural Industrialization's Modern Industrialization sim chambers (single-block and
 * multiblock) - see {@link SimChamberMixin}. Each calls {@code HNISimChamber#getUpdatedModel} once
 * per finished recipe from {@code onCraft} and gets the updated model stack back, so hooking that
 * return value needs no Modern Industrialization type. Against HNI 1.0.16 - re-check on update.
 */
@Mixin(targets = {
        "me.luligabi.hostile_neural_industrialization.common.block.machine.sim_chamber.electric.ElectricSimChamberBlockEntity",
        "me.luligabi.hostile_neural_industrialization.common.block.machine.sim_chamber.large.LargeSimChamberBlockEntity"
}, remap = false)
public abstract class HniSimChamberMixin {
    @ModifyExpressionValue(method = "onCraft", at = @At(value = "INVOKE", target = "getUpdatedModel"))
    private ItemStack foxstweaks$feedSourcelink(ItemStack model) {
        if (Config.HNI_SOURCE.get())
            HnnSimulations.finished((BlockEntity) (Object) this, model);
        return model;
    }
}
