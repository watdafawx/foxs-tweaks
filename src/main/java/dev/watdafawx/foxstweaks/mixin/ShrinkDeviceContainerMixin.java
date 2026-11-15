package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.watdafawx.foxstweaks.shrink.ShrinkNetwork;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Shrink's device menu takes the device (for its energy bar) from the main hand. Point it at the device wherever
 * it is (hand, Curios slot, inventory) so Shift + the Toggle Shrink key can open it. Runs on both sides.
 * {@code require = 0}: a Shrink update that moves this read leaves the menu as Shrink made it.
 */
@Mixin(targets = "net.gigabit101.shrink.ShrinkingDeviceContainer", remap = false)
public abstract class ShrinkDeviceContainerMixin {
    @WrapOperation(method = "<init>", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack foxstweaks$device(Player player, InteractionHand hand, Operation<ItemStack> original) {
        return ShrinkNetwork.deviceFor(player, original.call(player, hand));
    }
}
