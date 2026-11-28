package dev.watdafawx.foxstweaks.ae2variant.client;

import appeng.init.client.InitScreens;
import dev.watdafawx.foxstweaks.ae2variant.Ae2VariantIntegration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client-only registration for the ME Variant Crafter screen. */
public final class Ae2VariantClient {
    private Ae2VariantClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(Ae2VariantClient::onRegisterScreens);
    }

    private static void onRegisterScreens(RegisterMenuScreensEvent event) {
        InitScreens.register(
                event,
                Ae2VariantIntegration.MENU_TYPE.get(),
                VariantCrafterScreen::new,
                "/screens/foxstweaks_variant_crafter.json");
    }
}
