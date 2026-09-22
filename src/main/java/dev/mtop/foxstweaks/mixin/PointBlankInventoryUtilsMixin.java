package dev.mtop.foxstweaks.mixin;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.vicmatskiv.pointblank.crafting.PointBlankIngredient;

import dev.mtop.foxstweaks.storage.NearbyCounts;
import dev.mtop.foxstweaks.storage.NearbyStorage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The two places Point Blank decides whether a recipe can be paid for and then pays for it - both
 * look only at the player's main inventory. Outside a printer craft ({@link NearbyStorage#active})
 * only the client's availability check is touched, and only while the server is reporting.
 */
@Mixin(targets = "com.vicmatskiv.pointblank.util.InventoryUtils", remap = false)
public abstract class PointBlankInventoryUtilsMixin {
    @Shadow
    public static boolean removeItem(Player player, Predicate<ItemStack> match, int count) {
        throw new AssertionError();
    }

    /**
     * Only when the player alone falls short, so an ingredient they already carry costs nothing extra.
     * On the server that is the real check, against the storage around the printer. On the client -
     * where Point Blank decides whether the Craft button is enabled - the server's latest report of
     * that storage stands in for it.
     */
    @Inject(method = "hasIngredient", at = @At("RETURN"), cancellable = true)
    private static void foxstweaks$countNearby(Player player, PointBlankIngredient ingredient, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ())
            return;

        int missing = ingredient.getCount() - carried(player, ingredient::matches);
        if (NearbyStorage.active() ? NearbyStorage.count(ingredient::matches, missing) >= missing
                : player.level().isClientSide && NearbyCounts.fresh() && NearbyCounts.count(ingredient::matches) >= missing)
            cir.setReturnValue(true);
    }

    /**
     * Takes what the player is short of from nearby storage first, then lets Point Blank's own
     * removal take the rest from the player - so its inventory sync and its bookkeeping stay its own.
     * Re-entering {@code removeItem} is what runs that original; {@code enter} keeps this from
     * applying to the re-entrant call.
     */
    @Inject(method = "removeItem", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$takeNearby(Player player, Predicate<ItemStack> match, int count, CallbackInfoReturnable<Boolean> cir) {
        if (!NearbyStorage.active())
            return;

        int missing = count - carried(player, match);
        if (missing <= 0)
            return;

        int rest = count - NearbyStorage.take(match, missing);
        if (rest <= 0) {
            cir.setReturnValue(true);
            return;
        }

        NearbyStorage.enter();
        try {
            cir.setReturnValue(removeItem(player, match, rest));
        }
        finally {
            NearbyStorage.exit();
        }
    }

    /** Point Blank only ever looks at the main inventory list, not armor or offhand, and neither do we. */
    private static int carried(Player player, Predicate<ItemStack> match) {
        int total = 0;
        for (var stack : player.getInventory().items) {
            if (match.test(stack))
                total += stack.getCount();
        }

        return total;
    }
}
