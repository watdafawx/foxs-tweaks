package dev.watdafawx.foxstweaks.relics.client;

import dev.watdafawx.foxstweaks.Config;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Adds a "Right-click to open description" hint to a relic's tooltip while {@link RelicQuickOpenHandler}
 * can actually serve that click (inventory screen of the player's own menu, empty cursor).
 */
public class RelicQuickOpenTooltip {
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!Config.RELIC_RIGHT_CLICK_OPEN.get() || !(event.getItemStack().getItem() instanceof IRelicItem))
            return;

        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null
                || !(mc.screen instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != mc.player.containerMenu
                || !screen.getMenu().getCarried().isEmpty())
            return;

        var lines = event.getToolTip();

        // Right under Relics' own "Hold [key] to research..." line when it is there, else at the end.
        int at = lines.size();

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).getString().contains("research")) {
                at = i + 1;
                break;
            }
        }

        lines.add(at, Component.translatable("foxstweaks.relic.right_click_open").withStyle(ChatFormatting.GRAY));
    }
}
