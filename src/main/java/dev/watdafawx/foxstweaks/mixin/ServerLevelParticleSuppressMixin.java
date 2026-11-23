package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.relics.RelicParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drops server-side particles broadcast while a muted-particle relic is ticking. */
@Mixin(ServerLevel.class)
public class ServerLevelParticleSuppressMixin {
    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$suppressSendParticles(ParticleOptions type, double x, double y, double z, int count,
                                                  double dx, double dy, double dz, double speed,
                                                  CallbackInfoReturnable<Integer> cir) {
        if (RelicParticles.isSuppressed())
            cir.setReturnValue(0);
    }

    @Inject(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/particles/ParticleOptions;ZDDDIDDDD)Z", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$suppressSendParticlesPlayer(ServerPlayer player, ParticleOptions type, boolean longDistance,
                                                        double x, double y, double z, int count,
                                                        double dx, double dy, double dz, double speed,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (RelicParticles.isSuppressed())
            cir.setReturnValue(false);
    }
}
