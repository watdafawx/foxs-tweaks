package dev.watdafawx.foxstweaks.mixin;

import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.watdafawx.foxstweaks.client.compat.FtbChunksPreview;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional mixin drawing the active Biome/Flatten preview polygon onto FTB Chunks' fullscreen map. */
@Pseudo
@Mixin(value = RegionMapPanel.class, remap = false)
public abstract class FtbChunksMapOverlayMixin {
    @Shadow
    @Final
    LargeMapScreen largeMap;

    @Shadow
    int regionMinX;

    @Shadow
    int regionMinZ;

    @Inject(method = "draw", at = @At("TAIL"), require = 0)
    private void foxstweaks$drawTerraformPreview(GuiGraphics graphics, Theme theme, int x, int y, int w, int h, CallbackInfo ci) {
        FtbChunksPreview.renderOverlay(graphics, (RegionMapPanel) (Object) this, this.largeMap,
                this.regionMinX, this.regionMinZ, x, y, w, h);
    }
}
