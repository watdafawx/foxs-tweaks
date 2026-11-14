package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Fox's Tweaks settings screen: NeoForge's generated {@link ConfigurationScreen} over our config, reachable from the
 * Mods list "Config" button and from {@link FoxsTweaksKeys#OPEN_CONFIG}. Labels come from
 * {@code foxstweaks.configuration.<key>} in the lang file; tooltips fall back to each value's config comment.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public class ConfigScreenKeys {
    /** Called from the mod constructor on the client only. */
    public static void registerScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.OPEN_CONFIG.consumeClick()) {
            if (mc.screen != null)
                continue;

            ModList.get().getModContainerById(FoxsTweaks.MODID)
                    .ifPresent(container -> mc.setScreen(new ConfigurationScreen(container, null)));
        }
    }
}
