package dev.mtop.foxstweaks.client;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import dev.mtop.foxstweaks.gag.TemporalPouchNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends {@link TemporalPouchNetwork.UsePouchPayload} for each press of {@link FoxsTweaksKeys#USE_TEMPORAL_POUCH} while
 * looking at a block with no screen open. Everything else (finding the pouch, reach, using it) happens on the server.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public class TemporalPouchKeyHandler {
    private static Boolean available;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (available == null)
            available = TemporalPouchNetwork.isAvailable();

        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.USE_TEMPORAL_POUCH.consumeClick()) {
            if (!available || !Config.TEMPORAL_POUCH_CURIO.get() || mc.player == null || mc.screen != null)
                continue;

            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK)
                PacketDistributor.sendToServer(TemporalPouchNetwork.UsePouchPayload.INSTANCE);
        }
    }
}
