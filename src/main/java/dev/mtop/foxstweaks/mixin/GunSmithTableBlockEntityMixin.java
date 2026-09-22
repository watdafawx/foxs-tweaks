package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.tacz.WorkbenchOpenTracker;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * TACZ's gunsmith table ({@code tacz:workbench_a/b/c} - one shared block entity that every gun pack's
 * own workbench block reuses, distinguished only by its {@code BlockId}) opens its menu here.
 * {@code createMenu} is the only place the block's position is available to associate with the
 * player who is about to craft - see {@link GunSmithTableMenuMixin}, which reads it back.
 *
 * <p>Targeted by name, so TACZ is never loaded by this class; a pack without it never applies it.
 */
@Mixin(targets = "com.tacz.guns.block.entity.GunSmithTableBlockEntity", remap = false)
public abstract class GunSmithTableBlockEntityMixin {
    @Inject(method = "createMenu", at = @At("HEAD"))
    private void foxstweaks$trackOpen(int id, Inventory inventory, Player player, CallbackInfoReturnable<AbstractContainerMenu> cir) {
        var table = (BlockEntity) (Object) this;
        WorkbenchOpenTracker.open(player, table.getBlockPos());
    }
}
