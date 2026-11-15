package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.shrink.ShrinkNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@link FoxsTweaksKeys#TOGGLE_SHRINK}, with no screen open:
 * <ul>
 *   <li>alone - shrink or unshrink the living entity or player under the crosshair, or yourself when there is none;</li>
 *   <li>with Shift held - open Shrink's own device menu (scale setting) for the device wherever it is.</li>
 * </ul>
 * The server finds the device, checks reach and power and does the change; it needs Fox's Tweaks too.
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
            if (!available || !Config.SHRINK_HOTKEY.get() || mc.player == null || mc.screen != null || mc.getConnection() == null
                    || !mc.getConnection().hasChannel(ShrinkNetwork.ShrinkPayload.TYPE))
                continue;

            press(mc, Screen.hasShiftDown());
        }
    }

    /** One press of the key; public for the dev check, which can't hold a real Shift. */
    public static void press(Minecraft mc, boolean shift) {
        if (shift) {
            PacketDistributor.sendToServer(ShrinkNetwork.ShrinkPayload.openMenu());
            return;
        }

        int target = ShrinkNetwork.SELF;

        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY
                && ((EntityHitResult) mc.hitResult).getEntity() instanceof LivingEntity living)
            target = living.getId();

        PacketDistributor.sendToServer(ShrinkNetwork.ShrinkPayload.toggle(target));
    }
}
