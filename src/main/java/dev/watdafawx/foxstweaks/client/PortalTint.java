package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.dimension.ClientPortalColors;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Tints vanilla's nether_portal block (our model override gives it tint index 0 and a grey texture).
 * Portals with no dye come out purple, close to vanilla. Client only.
 */
public final class PortalTint {
    public static void register(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, index) -> index == 0 ? ClientPortalColors.tint(pos) : -1, Blocks.NETHER_PORTAL);
    }

    private PortalTint() {
    }
}
