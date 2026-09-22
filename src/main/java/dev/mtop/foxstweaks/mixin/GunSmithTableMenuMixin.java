package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.storage.NearbyStorage;
import dev.mtop.foxstweaks.tacz.NearbyBackedItemHandler;
import dev.mtop.foxstweaks.tacz.WorkbenchOpenTracker;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * TACZ's gunsmith table takes a recipe's ingredients entirely from the crafting player's own
 * {@code IItemHandler} capability - one call, in {@code doCraft}, whose result every bit of TACZ's
 * own ingredient matching and extraction then walks by slot index. Redirecting that one call to
 * return {@link NearbyBackedItemHandler#wrap} instead - real slots first, then virtual ones for
 * nearby storage - lets TACZ's unmodified logic pay for a recipe out of storage the same way it
 * already pays out of pockets. No TACZ type is referenced here or in {@code NearbyBackedItemHandler};
 * only the mixin target strings name TACZ classes.
 *
 * <p>{@code begin}/{@code end} bracket the whole craft (not just the redirect) because extraction
 * happens later, inside a lambda captured from the handler this redirect returns.
 */
@Mixin(targets = "com.tacz.guns.inventory.GunSmithTableMenu", remap = false)
public abstract class GunSmithTableMenuMixin {
    @Inject(method = "doCraft", at = @At("HEAD"))
    private void foxstweaks$beginCraft(ResourceLocation id, Player player, CallbackInfo ci) {
        var pos = WorkbenchOpenTracker.get(player);
        if (Config.TACZ_WORKBENCH_NEARBY_STORAGE.get() && pos != null)
            NearbyStorage.begin(player.level(), pos, Config.TACZ_WORKBENCH_STORAGE_RANGE.get());
    }

    @Inject(method = "doCraft", at = @At("RETURN"))
    private void foxstweaks$endCraft(ResourceLocation id, Player player, CallbackInfo ci) {
        NearbyStorage.end();
    }

    @Redirect(method = "doCraft",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getCapability(Lnet/neoforged/neoforge/capabilities/EntityCapability;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object foxstweaks$withNearbyStorage(Player player, EntityCapability capability, Object context) {
        var real = player.getCapability(capability, context);
        if (!(real instanceof IItemHandler handler) || !NearbyStorage.active())
            return real;

        var pos = WorkbenchOpenTracker.get(player);
        return pos == null ? real
                : NearbyBackedItemHandler.wrap(handler, player.level(), pos, Config.TACZ_WORKBENCH_STORAGE_RANGE.get());
    }
}
