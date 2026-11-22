package dev.watdafawx.foxstweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Guards {@link BlockRenderDispatcher#renderBreakingTexture} against pose stack leaks caused by
 * conflicting mixins. Block Manipulator's {@code BreakingRenderMixin} pushes a pose at {@code HEAD}
 * and pops at {@code RETURN}; when a later-applied cancellable {@code HEAD} mixin (such as Farmer's
 * Delight's {@code HideBlockBreakProgressMixin} on {@code canvas_rug}, or ComputerCraft's) cancels
 * early, the {@code RETURN} pop is skipped and {@code LevelRenderer.checkPoseStack} crashes with
 * {@code IllegalStateException: Pose stack not empty}.
 */
@Mixin(BlockRenderDispatcher.class)
public class BreakingRenderPoseGuardMixin {

    @WrapMethod(method = "renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/neoforged/neoforge/client/model/data/ModelData;)V")
    private void foxstweaks$guardBreakingPoseStack(
            BlockState state,
            BlockPos pos,
            BlockAndTintGetter level,
            PoseStack poseStack,
            VertexConsumer consumer,
            ModelData modelData,
            Operation<Void> original) {
        PoseStack.Pose before = poseStack.last();
        try {
            original.call(state, pos, level, poseStack, consumer, modelData);
        } finally {
            while (!poseStack.clear() && poseStack.last() != before) {
                poseStack.popPose();
            }
        }
    }
}
