package dev.watdafawx.foxstweaks.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.client.HologramPlayerPose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the live animated {@code PlayerModel} body &amp; head matrices right after rendering a player,
 * so player-attached holograms follow Emotecraft / playerAnimator / PAL animations accurately.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererPoseMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Shadow
    protected M model;

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    shift = At.Shift.AFTER))
    private void foxstweaks$capturePlayerPose(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                              MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (entity instanceof AbstractClientPlayer player && this.model instanceof PlayerModel<?> pm) {
            HologramPlayerPose.capture(player, pm, poseStack);
        }
    }
}
