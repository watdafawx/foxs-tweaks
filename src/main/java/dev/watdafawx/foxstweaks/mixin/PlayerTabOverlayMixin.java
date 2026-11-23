package dev.watdafawx.foxstweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.watdafawx.foxstweaks.perf.PerfOverlayRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the exact bounding box of vanilla's Tab player list so Fox's Tweaks' Server and Client
 * performance cards can flank it cleanly on the left and right (or fall back below if too wide).
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void foxstweaks$beginTabRender(GuiGraphics guiGraphics, int width, Scoreboard scoreboard,
                                           Objective objective, CallbackInfo ci) {
        PerfOverlayRenderer.beginVanillaTab();
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void foxstweaks$trackTabBounds(GuiGraphics instance, int minX, int minY, int maxX, int maxY, int color,
                                           Operation<Void> original) {
        PerfOverlayRenderer.recordVanillaTabBounds(minX, minY, maxX, maxY);
        original.call(instance, minX, minY, maxX, maxY, color);
    }
}
