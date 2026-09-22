package dev.mtop.foxstweaks.tacz;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Sneak + right-click in the air clears the ammo type TACZ's ammo box (creative or otherwise) is
 * locked to, so a different type can be set. TACZ has no such interaction itself: once
 * {@code IAmmoBox#setAmmoId} is called (by right-clicking the box on a stack of ammo), the box stays
 * locked to that type, and {@code AmmoBoxItem} has no override for a bare {@code use()} - right-clicking
 * it in the air currently does nothing at all - so this claims that otherwise-unused gesture.
 *
 * <p>Reaches TACZ only by its item's runtime class name, and manipulates the exact NBT tag keys
 * {@code AmmoBoxItemDataAccessor.AMMO_ID_TAG} / {@code AMMO_COUNT_TAG} use (verified against TACZ
 * 1.1.8-hotfix-r6 by reading its jar) with plain vanilla data components - both hardcoded rather than
 * compiled against, so this needs no TACZ dependency at all, and a pack without TACZ never matches
 * the class name check.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class AmmoBoxResetHandler {
    private static final String AMMO_BOX_CLASS = "com.tacz.guns.item.AmmoBoxItem";
    private static final String AMMO_ID_TAG = "AmmoId";
    private static final String AMMO_COUNT_TAG = "AmmoCount";

    private AmmoBoxResetHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (!Config.AMMO_BOX_RESET.get() || event.getLevel().isClientSide() || !event.getEntity().isShiftKeyDown())
            return;

        var stack = event.getItemStack();
        if (!stack.getItem().getClass().getName().equals(AMMO_BOX_CLASS))
            return;

        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (!data.contains(AMMO_ID_TAG))
            return;

        stack.set(DataComponents.CUSTOM_DATA, data.update(tag -> {
            tag.remove(AMMO_ID_TAG);
            tag.remove(AMMO_COUNT_TAG);
        }));

        event.getEntity().displayClientMessage(Component.translatable("foxstweaks.ammo_box.cleared"), true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
