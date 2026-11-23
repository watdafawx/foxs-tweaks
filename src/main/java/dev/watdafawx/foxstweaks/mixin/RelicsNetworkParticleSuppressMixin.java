package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.relics.RelicParticles;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops Relics' {@code S2CSpawnParticle} packets sent while a muted-particle relic is ticking. */
@Pseudo
@Mixin(targets = "it.hurts.sskirillss.relics.network.NetworkHandler", remap = false)
public class RelicsNetworkParticleSuppressMixin {
    @Inject(method = "sendToClient", at = @At("HEAD"), cancellable = true, require = 0)
    private static void foxstweaks$suppressClient(CustomPacketPayload payload, ServerPlayer player, CallbackInfo ci) {
        if (RelicParticles.isSuppressed() && isParticlePacket(payload))
            ci.cancel();
    }

    @Inject(method = "sendToClientsTrackingEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private static void foxstweaks$suppressTracking(CustomPacketPayload payload, Entity entity, CallbackInfo ci) {
        if (RelicParticles.isSuppressed() && isParticlePacket(payload))
            ci.cancel();
    }

    @Inject(method = "sendToClientsTrackingEntityAndSelf", at = @At("HEAD"), cancellable = true, require = 0)
    private static void foxstweaks$suppressTrackingSelf(CustomPacketPayload payload, Entity entity, CallbackInfo ci) {
        if (RelicParticles.isSuppressed() && isParticlePacket(payload))
            ci.cancel();
    }

    @Inject(method = "sendToClientsTrackingChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private static void foxstweaks$suppressTrackingChunk(CustomPacketPayload payload, ServerLevel level, ChunkPos pos, CallbackInfo ci) {
        if (RelicParticles.isSuppressed() && isParticlePacket(payload))
            ci.cancel();
    }

    private static boolean isParticlePacket(CustomPacketPayload payload) {
        return payload != null && payload.getClass().getName().endsWith(".S2CSpawnParticle");
    }
}
