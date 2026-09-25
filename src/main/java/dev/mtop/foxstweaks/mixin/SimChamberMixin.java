package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.HnnSimulations;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Hostile Neural Networks' simulation chamber simulates a kill without any mob existing, so Ars'
 * Vitalic Sourcelink (which listens for {@code LivingDeathEvent}) never hears of it. Each finished
 * simulation is reported as one death at the chamber instead.
 *
 * <p>{@code DataModelItem.setIters(model, n)} is called exactly once per finished simulation, in both
 * inference and training mode, and hands us the model stack. Against HNN 6.5.1 - re-check on update.
 */
@Mixin(targets = "dev.shadowsoffire.hostilenetworks.tile.SimChamberTileEntity", remap = false)
public abstract class SimChamberMixin {
    @ModifyArg(method = "serverTick", at = @At(value = "INVOKE", target = "setIters"), index = 0)
    private ItemStack foxstweaks$feedSourcelink(ItemStack model) {
        if (Config.HOSTILE_NETWORKS_SOURCE.get())
            HnnSimulations.finished((BlockEntity) (Object) this, model);
        return model;
    }
}
