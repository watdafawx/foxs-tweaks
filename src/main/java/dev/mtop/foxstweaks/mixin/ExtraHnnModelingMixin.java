package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.HnnSimulations;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ExtraHNN's Simulation Modeling machine trains a model by simulated kills, like HNN's own chamber
 * in training mode (which {@link SimChamberMixin} counts too). Its {@code result} calls
 * {@code setIters} once per finished run - {@code DataModelItem}'s for a plain model,
 * {@code ExtraDataModelItem}'s for a merged one; the name-only target matches both.
 * Against ExtraHNN 2.2.5 - re-check on update.
 */
@Mixin(targets = "net.lmor.extrahnn.common.tile.SimulationModelingTileEntity", remap = false)
public abstract class ExtraHnnModelingMixin {
    @ModifyArg(method = "result", at = @At(value = "INVOKE", target = "setIters"), index = 0)
    private ItemStack foxstweaks$feedSourcelink(ItemStack model) {
        if (Config.EXTRA_HNN_SOURCE.get())
            HnnSimulations.finished((BlockEntity) (Object) this, model);
        return model;
    }
}
