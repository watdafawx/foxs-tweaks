package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.HnnSimulations;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ExtraHNN's Ultimate Simulation Chamber - see {@link SimChamberMixin}. {@code setResult} runs once
 * per finished simulation and ends with {@code ExtraDataModelItem.setIters(model, n)}; a merged
 * model counts one death per mob in it. The tier's drop multiplier is not counted as extra deaths.
 * Against ExtraHNN 2.2.5 - re-check on update.
 */
@Mixin(targets = "net.lmor.extrahnn.common.tile.UltimateSimChamberTileEntity", remap = false)
public abstract class ExtraHnnSimMixin {
    @ModifyArg(method = "setResult", at = @At(value = "INVOKE", target = "setIters"), index = 0)
    private ItemStack foxstweaks$feedSourcelink(ItemStack model) {
        if (Config.EXTRA_HNN_SOURCE.get())
            HnnSimulations.finished((BlockEntity) (Object) this, model);
        return model;
    }
}
