package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.terraform.ExactBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enables exact 1x1x1 per-block biome overrides at biome changer borders so selections are not
 * limited to vanilla's 4x4x4 quart grid. When no overrides exist in the level, returns immediately
 * with a single empty-map check.
 */
@Mixin(BiomeManager.class)
public class BiomeManagerMixin {
    @Shadow
    @Final
    private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$exactBiome(BlockPos pos, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (this.noiseBiomeSource instanceof Level level) {
            Holder<Biome> override = ExactBiomes.getOverride(level, pos);
            if (override != null) {
                cir.setReturnValue(override);
            }
        }
    }
}
