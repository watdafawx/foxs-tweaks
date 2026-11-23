package dev.watdafawx.foxstweaks.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.client.perf.FpsProfiler;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times per-Entity render cost only while {@link FpsProfiler#sampling} is active. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderProfilerMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void foxstweaks$entityRenderStart(E entity, double x, double y, double z,
                                                                 float rotationYaw, float partialTicks,
                                                                 PoseStack poseStack, MultiBufferSource buffer,
                                                                 int packedLight, CallbackInfo ci) {
        if (FpsProfiler.sampling) {
            FpsProfiler.beginEntity();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void foxstweaks$entityRenderEnd(E entity, double x, double y, double z,
                                                               float rotationYaw, float partialTicks,
                                                               PoseStack poseStack, MultiBufferSource buffer,
                                                               int packedLight, CallbackInfo ci) {
        if (FpsProfiler.sampling) {
            FpsProfiler.endEntity(entity);
        }
    }
}
