package dev.mtop.foxstweaks.relics;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires up everything that needs Relics on the classpath.
 *
 * <p>Called from the mod constructor only when Relics is present. Registering these listeners by
 * hand instead of with {@code @EventBusSubscriber} is the whole point: FML loads annotated classes
 * whether or not their dependencies exist, so an annotation here would crash a pack that has this
 * mod for the Apotheosis tooltip but no Relics.
 */
public final class RelicsIntegration {
    private RelicsIntegration() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.register(AutoResearchHandler.class);
        NeoForge.EVENT_BUS.register(RelicsTestCommand.class);

        if (FMLEnvironment.dist == Dist.CLIENT)
            RelicsClientIntegration.init();
    }
}
