package dev.watdafawx.foxstweaks.mixin;

import dev.watdafawx.foxstweaks.relics.client.RelicParticlesOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Adds the "Show relic particles" toggle to Relics' native Relic Options tab. */
@Pseudo
@Mixin(targets = "it.hurts.sskirillss.relics.client.screen.description.relic.widgets.RelicOptionsContainerWidget", remap = false)
public class RelicOptionsMixin {
    @Shadow
    @Final
    @Mutable
    private static List<Object> OPTIONS;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
    private static void foxstweaks$appendParticleOption(CallbackInfo ci) {
        if (OPTIONS != null) {
            OPTIONS = RelicParticlesOptions.append(OPTIONS);
        }
    }
}
