package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;

/**
 * Modern Chickens' Mekanism radiation compat scans every container in every loaded chunk once a second
 * ({@code MekanismRadiationCompat.onLevelTick} -> {@code RadioactiveContentHelper.hasRadioactiveItems}) and reads
 * each slot with {@code getItem}. On a structure chest that still has its loot table, {@code getItem} rolls the
 * loot into it, so freshly generated village/dungeon chests got filled seconds after generation - before Lootr
 * could convert them into per-player chests, which it only does while the loot table is still attached.
 *
 * <p>A container with an unrolled loot table holds nothing yet, so it cannot be radioactive: answer false
 * without reading it. Found with {@code debugLogLootRolls}. Against Modern Chickens 2.7.8.
 */
@Mixin(targets = "strhercules.chickens.integration.mekanism.RadioactiveContentHelper", remap = false)
public abstract class ChickensRadiationLootMixin {
    @Inject(method = "hasRadioactiveItems", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$skipUnrolledLoot(Container container, CallbackInfoReturnable<Boolean> cir) {
        if (container instanceof RandomizableContainer lootable && lootable.getLootTable() != null
                && Config.enabled(Config.CHICKENS_RADIATION_KEEP_LOOT)) {
            cir.setReturnValue(false);
        }
    }
}
