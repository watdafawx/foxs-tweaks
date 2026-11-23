package dev.watdafawx.foxstweaks.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.client.perf.FpsProfiler;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times per-BlockEntity render cost only while {@link FpsProfiler#sampling} is active. */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderProfilerMixin {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"))
    private <E extends BlockEntity> void foxstweaks$beRenderStart(E blockEntity, float partialTick,
                                                                  PoseStack poseStack, MultiBufferSource bufferSource,
                                                                  CallbackInfo ci) {
        if (FpsProfiler.sampling) {
            FpsProfiler.beginBlockEntity();
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("RETURN"))
    private <E extends BlockEntity> void foxstweaks$beRenderEnd(E blockEntity, float partialTick,
                                                                PoseStack poseStack, MultiBufferSource bufferSource,
                                                                CallbackInfo ci) {
        if (FpsProfiler.sampling) {
            FpsProfiler.endBlockEntity(blockEntity);
        }
    }
}
