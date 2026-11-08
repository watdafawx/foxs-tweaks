package dev.watdafawx.foxstweaks.client.compat;

import dev.ftb.mods.ftblibrary.sidebar.SidebarGroupGuiButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Makes FTB Library's sidebar clickable on owo-lib container screens (Accessories' default screen).
 *
 * <p>FTB fires a sidebar button's click on mouse <i>release</i>. Vanilla routes a release to the first child
 * whose bounds contain the mouse ({@code ContainerEventHandler#mouseReleased} via {@code getChildAt}). owo's
 * UI adapter is added to the screen before FTB's sidebar and its bounds are the whole screen, so it always wins
 * that lookup, finds no component under the mouse and returns false: FTB never sees the release and every
 * sidebar button, ours and FTB's own, does nothing. Presses are fine, they try every child in turn.
 *
 * <p>Only needs FTB Library. When vanilla routing would already reach the sidebar this does nothing.
 */
public final class FtbSidebarRelease {
    private FtbSidebarRelease() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(FtbSidebarRelease::onRelease);
    }

    private static void onRelease(ScreenEvent.MouseButtonReleased.Pre event) {
        Screen screen = event.getScreen();

        if (!(screen instanceof AbstractContainerScreen<?>))
            return;

        double x = event.getMouseX();
        double y = event.getMouseY();

        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof SidebarGroupGuiButton sidebar))
                continue;

            if (!sidebar.visible || !sidebar.isMouseOver(x, y))
                return;

            // Vanilla routing reaches the sidebar by itself (inventory, chests): leave it alone.
            if (screen.getChildAt(x, y).orElse(null) == sidebar)
                return;

            if (sidebar.mouseReleased(x, y, event.getButton()))
                event.setCanceled(true);

            return;
        }
    }
}
