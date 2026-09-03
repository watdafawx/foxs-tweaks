package dev.mtop.foxstweaks.relics;

import dev.mtop.foxstweaks.relics.client.ResearchScreenHandler;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The client half of {@link RelicsIntegration}, kept in its own class so that loading it - and
 * through it the client-only Relics screen classes - only happens on a client that has Relics.
 */
public final class RelicsClientIntegration {
    private RelicsClientIntegration() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.register(ResearchScreenHandler.class);
    }
}
