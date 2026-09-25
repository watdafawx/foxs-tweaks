package dev.mtop.foxstweaks.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.Config;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * GuideME's "Hold [G] to open guide" line flickers when any other tooltip is built each tick.
 * {@code handleTooltip} keeps one global state and refreshes it only for the first tooltip built after a
 * client tick, then reuses it for every other tooltip that tick. If another mod builds a tooltip for a
 * different item each tick, the two items keep replacing each other's state, so the line for the hovered
 * item flickers, or shows the other item's guide instead.
 *
 * <p>Two rules fix it. A tooltip for an item that isn't the tracked one gets no line unless it is the
 * tick's first tooltip, so another item's guide never leaks onto it. When it is the first, GuideME
 * refreshes as normal. If that item has no guide page, we then restore the previous state, including
 * the unused tick, so an item with no guide can't take over the hovered item's line or hold progress.
 * Another item that <i>does</i> have a guide page can still compete; no case of that has been seen.
 */
@Mixin(targets = "guideme.internal.hotkey.OpenGuideHotkey", remap = false)
public abstract class GuideMeHotkeyMixin {
    @Shadow private static boolean newTick;
    @Shadow private static ResourceLocation previousItemId;
    @Shadow @Final private static List<Object> guidebookPages;
    @Shadow private static int ticksKeyHeld;
    @Shadow private static boolean holding;

    @Unique private static boolean foxstweaks$saved;
    @Unique private static ResourceLocation foxstweaks$savedItemId;
    @Unique private static final List<Object> foxstweaks$savedPages = new ArrayList<>();
    @Unique private static int foxstweaks$savedTicksKeyHeld;
    @Unique private static boolean foxstweaks$savedHolding;

    @Inject(method = "handleTooltip", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$guardState(ItemStack stack, TooltipFlag flag, List<?> lines, CallbackInfo ci) {
        foxstweaks$saved = false;
        if (!Config.GUIDEME_TOOLTIP_FIX.get()) {
            return;
        }

        // Same id expression GuideME uses, so the comparison matches its own.
        var itemId = stack.getItemHolder().unwrapKey().map(ResourceKey::location).orElse(null);
        if (Objects.equals(itemId, previousItemId)) {
            return;
        }

        if (!newTick) {
            // Not the tick's first tooltip, and not the tracked item: GuideME would show the tracked item's line here.
            ci.cancel();
            return;
        }

        foxstweaks$saved = true;
        foxstweaks$savedItemId = previousItemId;
        foxstweaks$savedPages.clear();
        foxstweaks$savedPages.addAll(guidebookPages);
        foxstweaks$savedTicksKeyHeld = ticksKeyHeld;
        foxstweaks$savedHolding = holding;
    }

    @Inject(method = "handleTooltip", at = @At("RETURN"))
    private static void foxstweaks$restoreIfNoGuide(CallbackInfo ci) {
        if (!foxstweaks$saved) {
            return;
        }

        foxstweaks$saved = false;
        if (!guidebookPages.isEmpty()) {
            return;
        }

        previousItemId = foxstweaks$savedItemId;
        guidebookPages.addAll(foxstweaks$savedPages);
        ticksKeyHeld = foxstweaks$savedTicksKeyHeld;
        holding = foxstweaks$savedHolding;
        newTick = true;
    }
}
