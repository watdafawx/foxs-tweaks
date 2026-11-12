package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Opens {@link ToggleScreen} when the toggle key is pressed in game, or over the inventory or any container screen. */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public class ToggleKeyHandler {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.TOGGLE_AFFIXES.consumeClick()) {
            if (mc.player != null && mc.screen == null && ToggleScreen.canOpen())
                mc.setScreen(new ToggleScreen(ToggleScreen.firstUsable(EquipmentSlot.MAINHAND)));
        }
    }

    /** Same key while an inventory or container is open: the box opens over it, and closing returns to it. */
    @SubscribeEvent
    public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();

        if (!(screen instanceof AbstractContainerScreen<?>) || screen.getFocused() instanceof EditBox)
            return;

        if (FoxsTweaksKeys.TOGGLE_AFFIXES.matches(event.getKeyCode(), event.getScanCode()) && ToggleScreen.canOpen()) {
            Minecraft.getInstance().setScreen(new ToggleScreen(ToggleScreen.firstUsable(EquipmentSlot.MAINHAND), screen));
            event.setCanceled(true);
        }
    }
}
