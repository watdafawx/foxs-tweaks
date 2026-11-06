package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.client.compat.TaczBedrockQuads;

/**
 * Draws TACZ's gun, attachment and ammo model cubes from a flat copy made once when the cube is built, without
 * zero-area quads and without per-vertex allocation. See {@link TaczBedrockQuads} for why.
 *
 * <p>Both of TACZ's cube types are covered; they have the same {@code polygons} field and {@code compile}
 * signature. Targeted by name, so a pack without TACZ never applies this. The toggle is read when a model loads,
 * so changing it takes a resource reload (F3+T).
 */
@Mixin(targets = {
        "com.tacz.guns.client.model.bedrock.BedrockCubeBox",
        "com.tacz.guns.client.model.bedrock.BedrockCubePerFace"
}, remap = false)
public abstract class TaczBedrockCubeMixin {
    /** Null means TACZ's own {@code compile} runs: toggle off, or the cube couldn't be read. */
    @Unique
    private float[] foxstweaks$quads;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void foxstweaks$flatten(CallbackInfo ci) {
        if (Config.enabled(Config.TACZ_FAST_MODEL_RENDER)) {
            foxstweaks$quads = TaczBedrockQuads.flatten(this);
        }
    }

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$emit(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, float red,
            float green, float blue, float alpha, CallbackInfo ci) {
        var quads = foxstweaks$quads;
        if (quads == null) {
            return;
        }

        TaczBedrockQuads.emit(quads, pose, consumer, light, overlay, red, green, blue, alpha);
        ci.cancel();
    }
}
