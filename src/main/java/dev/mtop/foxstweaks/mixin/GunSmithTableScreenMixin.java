package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.storage.NearbyCounts;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * TACZ's gunsmith table shows, per ingredient, how much of it you're carrying - the red/green count
 * that {@code GunSmithTableMenuMixin}'s server-side patch doesn't touch, since TACZ never gates the
 * Craft button on it (see that mixin's own doc). Left alone, a player would see "not enough" for an
 * ingredient nearby storage actually covers, and never click Craft to find out otherwise.
 *
 * <p>{@code getPlayerIngredientCount} reads {@code Inventory#items} once and walks it with TACZ's own
 * {@code Ingredient#test}/{@code ItemStack#getCount} logic - the same one-field trick as
 * {@code GunSmithTableMenuMixin}'s capability redirect, so this needs no TACZ type either: redirecting
 * that single field read to a list with nearby storage appended makes TACZ's own unmodified counting
 * logic add nearby stock in as if it were more inventory.
 */
@Mixin(targets = "com.tacz.guns.client.gui.GunSmithTableScreen", remap = false)
public abstract class GunSmithTableScreenMixin {
    @Redirect(method = "getPlayerIngredientCount",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Inventory;items:Lnet/minecraft/core/NonNullList;"))
    private NonNullList<ItemStack> foxstweaks$withNearbyStorage(Inventory inventory) {
        if (!Config.TACZ_WORKBENCH_NEARBY_STORAGE.get() || !NearbyCounts.fresh())
            return inventory.items;

        var combined = NonNullList.<ItemStack>create();
        combined.addAll(inventory.items);
        combined.addAll(NearbyCounts.asStacks());

        return combined;
    }
}
