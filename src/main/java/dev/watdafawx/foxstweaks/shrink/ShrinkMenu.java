package dev.watdafawx.foxstweaks.shrink;

import dev.architectury.registry.menu.MenuRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;

/**
 * Opens Shrink's own device menu the way {@code ItemShrinkDevice#use} does. Kept apart so Architectury (a Shrink
 * dependency, not ours) is only touched when Shrink is loaded.
 */
final class ShrinkMenu {
    private ShrinkMenu() {
    }

    /** @return false if the item is not a menu provider (Shrink changed) */
    static boolean open(ServerPlayer player, Item device) {
        if (!(device instanceof MenuProvider provider))
            return false;

        MenuRegistry.openExtendedMenu(player, provider, buf -> buf.writeDouble(0));
        return true;
    }
}
