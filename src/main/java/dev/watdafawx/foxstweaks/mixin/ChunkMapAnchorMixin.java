package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.anchor.SpawnAnchors;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Treats chunks within a virtual spawn anchor's radius as having a nearby player so vanilla runs
 * random ticks (Dreadful/Delightful Dirt, crops) and natural mob spawning there.
 */
@Mixin(ChunkMap.class)
public class ChunkMapAnchorMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$anchorAllowsSpawning(ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        if (SpawnAnchors.isChunkNearAnchor(this.level, chunkPos)) {
            cir.setReturnValue(true);
        }
    }
}
