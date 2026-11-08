package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphics;

/**
 * FTB Library's sidebar tooltip text vanishes on owo-lib container screens (Accessories' default screen),
 * leaving an empty tooltip box.
 *
 * <p>{@code GuiGraphics#renderTooltip} paints the box at once but only queues the text in the shared buffer
 * source; the text is drawn by whichever {@code flush()} comes next. On an owo screen that is one inside a
 * slot or component with an owo scissor active, which clips the text to that area, nowhere near the sidebar.
 * Flushing as the sidebar finishes drawing makes the text go out right away, under no scissor.
 *
 * <p>Targeted by name, so FTB Library is never a compile dependency here.
 */
@Mixin(targets = "dev.ftb.mods.ftblibrary.sidebar.SidebarGroupGuiButton", remap = false)
public abstract class FtbSidebarTooltipFlushMixin {
    @Inject(method = "renderWidget", at = @At("TAIL"), require = 0)
    private void foxstweaks$flushTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        graphics.flush();
    }
}
