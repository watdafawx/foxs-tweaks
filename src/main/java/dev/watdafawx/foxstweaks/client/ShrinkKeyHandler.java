package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.shrink.ShrinkNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@link FoxsTweaksKeys#TOGGLE_SHRINK}, with no screen open:
 * <ul>
 *   <li>alone - shrink or unshrink the living entity or player under the crosshair (seen through grass and other
 *       non-solid blocks), or yourself when there is none;</li>
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

        LivingEntity living = pickLiving(mc);
        PacketDistributor.sendToServer(ShrinkNetwork.ShrinkPayload.toggle(living == null ? ShrinkNetwork.SELF : living.getId()));
    }

    /**
     * The living entity under the crosshair. Vanilla's pick stops at a block's outline, so a (shrunk) mob standing in
     * grass or flowers is hidden behind the plant; when the crosshair isn't on an entity, look again along the view,
     * stopping only at blocks with collision (walls, not plants), within entity interaction range.
     */
    private static LivingEntity pickLiving(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living)
            return living;

        Player player = mc.player;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.entityInteractionRange()));
        BlockHitResult wall = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

        if (wall.getType() != HitResult.Type.MISS)
            end = wall.getLocation();

        AABB box = player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
                e -> e instanceof LivingEntity && !e.isSpectator() && e.isPickable(), eye.distanceToSqr(end));
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }
}
