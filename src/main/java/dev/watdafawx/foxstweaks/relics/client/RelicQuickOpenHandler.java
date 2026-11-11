package dev.watdafawx.foxstweaks.relics.client;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.client.screen.description.relic.RelicDescriptionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Opens Relics' description screen at once for the relic under the cursor, in place of Relics' own
 * "hold the research key for a second". Right click (empty cursor) or a configurable key.
 */
public class RelicQuickOpenHandler {
    @SubscribeEvent
    public static void onMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || !Config.RELIC_RIGHT_CLICK_OPEN.get())
            return;

        if (event.getScreen() instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().getCarried().isEmpty()
                && open(screen))
            event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!FoxsTweaksKeys.OPEN_RELIC.matches(event.getKeyCode(), event.getScanCode()))
            return;

        if (event.getScreen() instanceof AbstractContainerScreen<?> screen && open(screen))
            event.setCanceled(true);
    }

    private static boolean open(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        var slot = screen.getSlotUnderMouse();

        // Relics' packets resolve the relic through the player's own container menu, so a screen
        // with any other menu (creative tabs) cannot be served.
        if (mc.player == null || slot == null || screen.getMenu() != mc.player.containerMenu
                || !(slot.getItem().getItem() instanceof IRelicItem))
            return false;

        int index = screen.getMenu().slots.indexOf(slot);

        if (index < 0)
            return false;

        mc.setScreen(new RelicDescriptionScreen(mc.player, screen.getMenu().containerId, index, screen));

        return true;
    }
}
