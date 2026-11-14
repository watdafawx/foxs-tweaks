package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.shrink.ShrinkNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends {@link ShrinkNetwork.ToggleShrinkPayload} for each press of {@link FoxsTweaksKeys#TOGGLE_SHRINK} with no screen
 * open. Finding the device, power and the toggle itself happen on the server, which needs Fox's Tweaks too.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public class ShrinkKeyHandler {
    private static Boolean available;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (available == null)
            available = ShrinkNetwork.isAvailable();

        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.TOGGLE_SHRINK.consumeClick()) {
            if (!available || !Config.SHRINK_HOTKEY.get() || mc.player == null || mc.screen != null || mc.getConnection() == null)
                continue;

            if (mc.getConnection().hasChannel(ShrinkNetwork.ToggleShrinkPayload.TYPE))
                PacketDistributor.sendToServer(ShrinkNetwork.ToggleShrinkPayload.INSTANCE);
        }
    }
}
