package dev.mtop.foxstweaks.client;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import dev.mtop.foxstweaks.client.compat.AffixToggleScreen;
import dev.mtop.foxstweaks.compat.ApothicAffixToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Opens {@link AffixToggleScreen} (the held item's own tooltip, used as clickable affix controls)
 * when {@link FoxsTweaksKeys#TOGGLE_AFFIXES} is pressed with no other screen open. Apotheosis types
 * only reach this class through {@code compat.ApothicAffixToggle} and {@code AffixToggleScreen},
 * both guarded by the same {@code ModList} check {@code AffixTooltipHandler} uses.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public class AffixToggleKeyHandler {
    private static Boolean apotheosisLoaded;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!Config.AFFIX_TOGGLE_GUI.get() || !isApotheosisLoaded())
            return;

        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.TOGGLE_AFFIXES.consumeClick()) {
            if (mc.player == null || mc.screen != null)
                continue;

            ItemStack stack = mc.player.getMainHandItem();

            if (!stack.isEmpty() && ApothicAffixToggle.hasAnyAffixes(stack))
                mc.setScreen(new AffixToggleScreen(stack));
        }
    }

    private static boolean isApotheosisLoaded() {
        if (apotheosisLoaded == null)
            apotheosisLoaded = ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS);

        return apotheosisLoaded;
    }
}
