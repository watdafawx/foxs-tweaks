package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Kaleidoscope Flora's {@code PlayerEvents#coronationMotes}, which orbits yellow
 * {@code minecraft:dust} particles around any entity that has the vanilla Luck effect.
 */
@Pseudo
@Mixin(targets = "com.rlosking.flora.event.PlayerEvents", remap = false)
public class FloraLuckParticlesMixin {
    @Inject(method = "coronationMotes", at = @At("HEAD"), cancellable = true, require = 0)
    private static void foxstweaks$suppressFloraLuckParticles(CallbackInfo ci) {
        if (Config.enabled(Config.DISABLE_FLORA_LUCK_PARTICLES)) {
            ci.cancel();
        }
    }
}
