package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.ArsSourcelinks;
import dev.shadowsoffire.hostilenetworks.data.DataModelInstance;
import dev.shadowsoffire.hostilenetworks.data.EntityDataModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hostile Neural Networks' simulation chamber simulates a kill without any mob existing, so Ars'
 * Vitalic Sourcelink (which listens for {@code LivingDeathEvent}) never hears of it. Each finished
 * simulation is reported as one death at the chamber instead.
 *
 * <p>{@code DataModelItem.setIters} is called exactly once per finished simulation, in both
 * inference and training mode, so that is the hook. Against HNN 6.5.1 - re-check on update.
 */
@Mixin(targets = "dev.shadowsoffire.hostilenetworks.tile.SimChamberTileEntity", remap = false)
public abstract class SimChamberMixin {
    @Shadow
    protected DataModelInstance currentModel;

    @Inject(method = "serverTick", at = @At(value = "INVOKE",
            target = "Ldev/shadowsoffire/hostilenetworks/item/DataModelItem;setIters(Lnet/minecraft/world/item/ItemStack;I)V"))
    private void foxstweaks$feedSourcelink(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        // A block data model simulates mining a block, not a kill.
        if (Config.HOSTILE_NETWORKS_SOURCE.get() && ArsSourcelinks.loaded() && currentModel.getModel() instanceof EntityDataModel model)
            ArsSourcelinks.mobDied(level, pos, model.entity());
    }
}
