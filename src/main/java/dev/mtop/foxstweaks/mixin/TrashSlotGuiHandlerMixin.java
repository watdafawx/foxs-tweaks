package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Waystones' settings screens are container screens over a slotless menu. TrashSlot still draws its
 * slot there, and GuiTween's renderSlot hook then calls {@code menu.getSlot(index)} on the empty
 * list and crashes. A screen with no slots has nothing to trash, so skip drawing.
 */
@Mixin(targets = "net.blay09.mods.trashslot.client.TrashSlotGuiHandler", remap = false)
public abstract class TrashSlotGuiHandlerMixin {
    @Inject(method = "onScreenDrawn", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$skipSlotlessMenus(CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().slots.isEmpty()) {
            ci.cancel();
        }
    }
}
