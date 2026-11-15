package dev.watdafawx.foxstweaks.shrink;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

/**
 * Curios side of the Shrink hotkey: finds a Shrinking Device in any Curios slot (normally our own
 * {@code shrinking_device} slot, {@code data/foxstweaks/curios/slots/shrinking_device.json}). Only reached from
 * {@link ShrinkNetwork} after a {@code ModList} check for Curios, so this class never loads without it.
 */
final class ShrinkCurios {
    private ShrinkCurios() {
    }

    static ItemStack find(Player player, Item device) {
        return CuriosApi.getCuriosInventory(player)
                .flatMap(inventory -> inventory.findFirstCurio(device))
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }
}
