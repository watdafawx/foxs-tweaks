package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.ArsSourcelinks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * ApotSpawner Nexus cancels a spawner's mob as it joins the world and hands out its loot directly,
 * so the mob never dies and Ars' Vitalic Sourcelink (which listens for {@code LivingDeathEvent})
 * never hears of it. Each simulated kill is reported as one death at the spawner instead.
 *
 * <p>{@code generateDeathRewards(ServerLevel, BlockPos spawner, LivingEntity mob, ServerPlayer,
 * ServerPlayer, RewardCapture)} runs once per simulated kill. Its last parameter is a private type,
 * so the arguments are picked by {@code @Local} rather than spelled out. Targeted by name; against
 * ApotSpawner 1.2.0 - re-check the method on update.
 */
@Mixin(targets = "com.beyondtheorder.apotspawner.SpawnerEventHandler", remap = false)
public abstract class ApotSpawnerKillMixin {
    @Inject(method = "generateDeathRewards", at = @At("HEAD"))
    private static void foxstweaks$feedSourcelink(CallbackInfo ci, @Local(argsOnly = true) ServerLevel level,
            @Local(argsOnly = true) BlockPos spawner, @Local(argsOnly = true) LivingEntity mob) {
        if (Config.APOTSPAWNER_SOURCE.get() && ArsSourcelinks.loaded())
            ArsSourcelinks.mobDied(level, spawner, mob);
    }
}
