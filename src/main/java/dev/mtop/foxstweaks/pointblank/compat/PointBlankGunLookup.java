package dev.mtop.foxstweaks.pointblank.compat;

import com.vicmatskiv.pointblank.item.GunItem;

import net.minecraft.world.item.ItemStack;

/** Confines the Point Blank type to one class that is only loaded once Point Blank is known to be present. */
public final class PointBlankGunLookup {
    private PointBlankGunLookup() {
    }

    public static boolean isGun(ItemStack stack) {
        return stack.getItem() instanceof GunItem;
    }
}
