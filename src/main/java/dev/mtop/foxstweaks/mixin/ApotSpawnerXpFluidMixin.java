package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

/**
 * With Create Enchantment Industry installed, ApotSpawner's spawner exports CEI's experience fluid
 * (1 mB per XP) instead of its own (20 mB per XP). CEI's fluid is not in {@code c:experience}, so
 * Just Dire Things' Experience Holder refuses it - and tagging it would not help, since the holder
 * always reads 20 mB as 1 XP and would keep only 1/20 of it.
 *
 * <p>Keeping ApotSpawner on its own fluid fixes both: it is already in {@code c:experience}, and
 * {@code millibucketsPerXp()} derives the rate from this same method, so it becomes 20 too. The
 * spawner stores XP as points, not fluid, so nothing already stored is lost by switching. Against
 * ApotSpawner 1.2.0 - re-check on update.
 */
@Mixin(targets = "com.beyondtheorder.apotspawner.compat.ExperienceFluidCompat", remap = false)
public abstract class ApotSpawnerXpFluidMixin {
    private static final ResourceLocation OWN_FLUID = ResourceLocation.fromNamespaceAndPath("apotspawner", "liquid_experience");

    @Inject(method = "exportedFluid", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$keepOwnFluid(CallbackInfoReturnable<Fluid> cir) {
        if (Config.APOTSPAWNER_OWN_XP_FLUID.get())
            cir.setReturnValue(BuiltInRegistries.FLUID.get(OWN_FLUID));
    }
}
