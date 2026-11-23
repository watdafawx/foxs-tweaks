package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.anchor.SpawnAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets vanilla {@link NaturalSpawner} spawn mobs in the 24..128 block ring around a virtual spawn
 * anchor even when all real players are far away.
 */
@Mixin(NaturalSpawner.class)
public class NaturalSpawnerAnchorMixin {
    @Inject(method = "isRightDistanceToPlayerAndSpawnPoint", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$anchorSpawnDistance(ServerLevel level, ChunkAccess chunk,
                                                       BlockPos.MutableBlockPos pos, double squaredDistance,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (squaredDistance > 128.0 * 128.0) {
            double ad = SpawnAnchors.nearestAnchorDistSq(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (ad >= 24.0 * 24.0 && ad <= 128.0 * 128.0) {
                if (!level.getSharedSpawnPos().closerToCenterThan(
                        new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), 24.0)) {
                    cir.setReturnValue(true);
                }
            }
        }
    }
}
