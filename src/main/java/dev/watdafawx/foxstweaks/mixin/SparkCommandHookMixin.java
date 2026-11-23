package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.client.perf.FpsProfiler;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects {@code /sparkc profiler} commands so {@link FpsProfiler} highlights hotspots alongside sparkc. */
@Mixin(ChatScreen.class)
public class SparkCommandHookMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"))
    private void foxstweaks$onChatInput(String msg, boolean addToRecentChat, CallbackInfo ci) {
        FpsProfiler.onChatInput(msg);
    }
}
