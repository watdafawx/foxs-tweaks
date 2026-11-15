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
 * Shrink's device screen reads the starting scale from the main-hand item (and casts it to the device, so anything
 * else there would crash). Read it from the device wherever it is instead.
 */
@Mixin(targets = "net.gigabit101.shrink.client.ShrinkScreen", remap = false)
public abstract class ShrinkScreenMixin {
    @WrapOperation(method = "buildGui", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack foxstweaks$device(Player player, InteractionHand hand, Operation<ItemStack> original) {
        return ShrinkNetwork.deviceFor(player, original.call(player, hand));
    }
}
