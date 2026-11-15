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
 * Shrink's menu saves the new scale to the main-hand item (the packet's hand is always MAIN_HAND). Save it to the
 * device wherever it is instead. The handler body is the lambda {@code lambda$handle$0}; {@code require = 0} so a
 * Shrink build that renames it just saves to the main hand again.
 */
@Mixin(targets = "net.gigabit101.shrink.network.packets.PacketShrinkDevice", remap = false)
public abstract class ShrinkDevicePacketMixin {
    @WrapOperation(method = "lambda$handle$0", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack foxstweaks$device(Player player, InteractionHand hand, Operation<ItemStack> original) {
        ItemStack stack = original.call(player, hand);
        return hand == InteractionHand.MAIN_HAND ? ShrinkNetwork.deviceFor(player, stack) : stack;
    }
}
