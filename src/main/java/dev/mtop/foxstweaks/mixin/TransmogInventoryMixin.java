package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.bawnorton.mixinsquared.TargetHandler;

import dev.mtop.foxstweaks.Config;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

/**
 * Transmog's {@code PlayerMixin.transmogInventory} hooks {@code Player#getInventory}: while anything is being
 * rendered it builds a brand-new {@code Inventory}, copying all 41 slots through
 * {@code TransmogUtils.getAppearanceStackOrOriginal}. Trinket layers, HUDs and ritual renderers call
 * {@code getInventory} many times per frame, so that copy cost ~10% of frame time in a spark profile.
 *
 * <p>The copy only differs from the real inventory when some stack carries Transmog's appearance component, so
 * when none does, Transmog's handler is skipped and the original inventory stands. That is the common case; a
 * player with any transmogged item still gets the full original behaviour.
 *
 * <p>Targets Transmog's handler through MixinSquared ({@code priority} above Transmog's default so its mixin is
 * already merged). {@code require = 0}: without Transmog there is no handler and this does nothing.
 */
@Mixin(value = Player.class, priority = 1500)
public abstract class TransmogInventoryMixin {
    @Unique private static final ResourceLocation foxstweaks$APPEARANCE_ID =
            ResourceLocation.fromNamespaceAndPath("transmog", "appearance_item");
    @Unique private static DataComponentType<?> foxstweaks$appearanceType;

    @TargetHandler(mixin = "com.hidoni.transmog.mixin.PlayerMixin", name = "transmogInventory")
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true, require = 0)
    private void foxstweaks$skipUntransmogged(CallbackInfoReturnable<Inventory> original, CallbackInfo ci) {
        if (!Config.enabled(Config.TRANSMOG_INVENTORY_FAST_PATH)) {
            return;
        }
        if (foxstweaks$appearanceType == null) {
            foxstweaks$appearanceType = BuiltInRegistries.DATA_COMPONENT_TYPE.get(foxstweaks$APPEARANCE_ID);
            if (foxstweaks$appearanceType == null) {
                return;
            }
        }

        // The value getInventory is about to return - calling getInventory here would re-enter this handler.
        Inventory inventory = original.getReturnValue();
        if (inventory == null) {
            return;
        }

        for (int i = 0, size = inventory.getContainerSize(); i < size; i++) {
            if (inventory.getItem(i).has(foxstweaks$appearanceType)) {
                return;
            }
        }

        ci.cancel();
    }
}
