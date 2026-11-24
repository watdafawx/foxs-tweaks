package dev.watdafawx.foxstweaks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * The ordinary chat box, opened over {@link HologramScreen} so chat (and commands) work while editing.
 *
 * <p>Vanilla's chat screen closes by setting the screen to null, which would throw the editor away. Instead,
 * once this screen is removed and nothing else took its place, the editor it came from is put back. The
 * editor is the same instance, so its layers, undo history and studio camera are all still there.
 */
public final class HologramChatScreen extends ChatScreen {
    private final Screen parent;

    public HologramChatScreen(String initial, Screen parent) {
        super(initial);
        this.parent = parent;
    }

    @Override
    public void removed() {
        super.removed();
        Minecraft mc = Minecraft.getInstance();
        // Deferred: at this point Minecraft has not yet swapped in the next screen.
        mc.tell(() -> {
            if (mc.screen == null && mc.level != null && mc.player != null)
                mc.setScreen(this.parent);
        });
    }
}
