package dev.mtop.foxstweaks.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.gson.JsonElement;

import dev.mtop.foxstweaks.apotheosis.RarityPatcher;
import dev.shadowsoffire.placebo.reload.DynamicRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Placebo exposes no hook between reading a registry's JSON and decoding it, and that gap is the
 * only place affix data can be corrected before a codec rejects or drops it. Only ever loaded with
 * Placebo, since it is what the target class comes from.
 */
@Mixin(DynamicRegistry.class)
public abstract class DynamicRegistryMixin {
    @Shadow
    public abstract String getPath();

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void foxstweaks$patchRarities(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler, CallbackInfo ci) {
        RarityPatcher.patch(getPath(), data);
    }
}
