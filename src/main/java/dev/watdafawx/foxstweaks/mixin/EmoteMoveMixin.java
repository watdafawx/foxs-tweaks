package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional client patch for Emotecraft ({@code io.github.kosmx.emotes.main.EmoteHolder}):
 * lets players keep emoting while walking, sprinting, or jumping when {@link Config#allowEmoteWhileMoving()} is on.
 */
@Mixin(targets = "io.github.kosmx.emotes.main.EmoteHolder", remap = false)
public abstract class EmoteMoveMixin {

    @Inject(method = "canRunEmote", at = @At("HEAD"), cancellable = true, require = 0)
    private static void foxstweaks$allowEmoteWhileMoving(CallbackInfoReturnable<Boolean> cir) {
        if (Config.allowEmoteWhileMoving()) {
            cir.setReturnValue(true);
        }
    }
}
