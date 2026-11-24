package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.client.HologramCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies {@link HologramCamera}'s detached free-fly studio position & orientation while the
 * hologram editor is open.
 */
@Mixin(Camera.class)
public abstract class HologramCameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void foxstweaks$applyHologramCamera(BlockGetter level, Entity entity, boolean detached,
                                                boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!HologramCamera.isActive())
            return;
        HologramCamera.tickFrame();
        this.detached = true;
        this.setRotation(HologramCamera.yaw(), HologramCamera.pitch());
        Vec3 pos = HologramCamera.pos();
        this.setPosition(pos.x, pos.y, pos.z);
    }
}
