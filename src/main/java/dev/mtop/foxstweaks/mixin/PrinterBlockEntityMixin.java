package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.pointblank.PointBlankRecipes;
import dev.mtop.foxstweaks.pointblank.PrinterNetwork;
import dev.mtop.foxstweaks.storage.NearbyStorage;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Point Blank checks and consumes a recipe's ingredients in {@code createCraftingItem}, once the
 * print finishes. Marking that span tells {@link PointBlankInventoryUtilsMixin} where the printer is,
 * which the ingredient helpers - given only a player - cannot know.
 *
 * <p>Targeted by name, so Point Blank is never loaded by this class; a pack without it never applies it.
 */
@Mixin(targets = "com.vicmatskiv.pointblank.block.entity.PrinterBlockEntity", remap = false)
public abstract class PrinterBlockEntityMixin {
    /**
     * Point Blank's Craft button is enabled by the client, from the player's own inventory, so while
     * someone has this printer open we tell their client what the storage around it holds too.
     */
    @Inject(method = "serverTick()V", at = @At("HEAD"))
    private void foxstweaks$reportNearby(CallbackInfo ci) {
        var printer = (BlockEntity) (Object) this;
        PrinterNetwork.tick(printer, () -> PointBlankRecipes.wantedItems(printer.getLevel()));
    }

    @Inject(method = "createCraftingItem", at = @At("HEAD"))
    private void foxstweaks$beginCraft(CallbackInfo ci) {
        if (!Config.PRINTER_NEARBY_STORAGE.get())
            return;

        var printer = (BlockEntity) (Object) this;
        NearbyStorage.begin(printer.getLevel(), printer.getBlockPos(), Config.PRINTER_STORAGE_RANGE.get());
    }

    /** The method swallows its own exceptions, so this always runs. */
    @Inject(method = "createCraftingItem", at = @At("RETURN"))
    private void foxstweaks$endCraft(CallbackInfo ci) {
        NearbyStorage.end();
    }
}
