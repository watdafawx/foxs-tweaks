package dev.watdafawx.foxstweaks.client;

import java.util.List;


import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.enchant.EnchantToggle;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Shows an enchantment the toggle overlay switched off as a red X line where it used to be, so it
 * reads as "present but off" rather than vanishing. Vanilla only - no Apotheosis types here.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class EnchantTooltip {
    private EnchantTooltip() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (!Config.ENCHANT_TOGGLE.get() || event.getItemStack().isEmpty())
            return;

        HolderLookup.Provider registries = event.getContext().registries();

        if (registries == null || EnchantToggle.disabledOf(event.getItemStack()).entries().isEmpty())
            return;

        List<EnchantToggle.Row> rows = EnchantToggle.list(event.getItemStack(), registries);
        List<Component> lines = event.getToolTip();

        // After the last live enchantment line; right under the name when none are left.
        int at = Math.min(1, lines.size());

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).getString();

            for (EnchantToggle.Row row : rows) {
                if (row.enabled() && line.equals(name(row).getString()))
                    at = i + 1;
            }
        }

        for (EnchantToggle.Row row : rows) {
            if (!row.enabled())
                lines.add(at++, disabledLine(row));
        }
    }

    public static Component name(EnchantToggle.Row row) {
        return Enchantment.getFullname(row.enchantment(), row.level());
    }

    public static Component disabledLine(EnchantToggle.Row row) {
        return ToggleIcons.off().append(name(row).copy().withStyle(ChatFormatting.RED));
    }
}
