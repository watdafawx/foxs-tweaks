package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.relics.RelicParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops client-side particles spawned while a muted-particle relic is ticking, and feeds {@code /foxstweaks debug particles}. */
@Mixin(Level.class)
public class LevelParticleSuppressMixin {
    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$suppressAddParticle(ParticleOptions options, double x, double y, double z,
                                                double xd, double yd, double zd, CallbackInfo ci) {
        if (RelicParticles.isSuppressed()) {
            ci.cancel();
            return;
        }
        if (FMLEnvironment.dist.isClient())
            dev.watdafawx.foxstweaks.client.ParticleDebug.record((Level) (Object) this, options, x, y, z);
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$suppressAddParticleForce(ParticleOptions options, boolean force, double x, double y, double z,
                                                     double xd, double yd, double zd, CallbackInfo ci) {
        if (RelicParticles.isSuppressed()) {
            ci.cancel();
            return;
        }
        if (FMLEnvironment.dist.isClient())
            dev.watdafawx.foxstweaks.client.ParticleDebug.record((Level) (Object) this, options, x, y, z);
    }

    @Inject(method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$suppressAlwaysVisible(ParticleOptions options, double x, double y, double z,
                                                  double xd, double yd, double zd, CallbackInfo ci) {
        if (RelicParticles.isSuppressed()) {
            ci.cancel();
            return;
        }
        if (FMLEnvironment.dist.isClient())
            dev.watdafawx.foxstweaks.client.ParticleDebug.record((Level) (Object) this, options, x, y, z);
    }

    @Inject(method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$suppressAlwaysVisibleForce(ParticleOptions options, boolean overrideLimiter, double x, double y, double z,
                                                       double xd, double yd, double zd, CallbackInfo ci) {
        if (RelicParticles.isSuppressed()) {
            ci.cancel();
            return;
        }
        if (FMLEnvironment.dist.isClient())
            dev.watdafawx.foxstweaks.client.ParticleDebug.record((Level) (Object) this, options, x, y, z);
    }
}
