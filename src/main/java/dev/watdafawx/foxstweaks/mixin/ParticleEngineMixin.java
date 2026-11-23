package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.client.ParticleDebug;
import dev.watdafawx.foxstweaks.relics.RelicParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@link ParticleEngine} directly — {@code ClientLevel} overrides {@code addParticle}
 * without calling {@code super}, so hooking {@code ParticleEngine} catches every client-side
 * particle (from {@code ClientLevel#addParticle}, {@code LevelRenderer}, S2C packets, and direct
 * {@code ParticleEngine#add} calls).
 */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$onCreateParticle(ParticleOptions options, double x, double y, double z,
                                             double xd, double yd, double zd,
                                             CallbackInfoReturnable<Particle> cir) {
        if (RelicParticles.isSuppressed()) {
            cir.setReturnValue(null);
            return;
        }
        ParticleDebug.recordOptions(options, x, y, z);
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$onAddParticle(Particle particle, CallbackInfo ci) {
        if (RelicParticles.isSuppressed()) {
            ci.cancel();
            return;
        }
        ParticleDebug.recordDirect(particle);
    }
}
