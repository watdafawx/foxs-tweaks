package dev.watdafawx.foxstweaks.dimension;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Client copy of the dyed portals of the dimension the player is in, and the tint for vanilla's
 * nether_portal block. Client only: reached from the payload handler and the colour registration, both
 * of which only run there.
 */
public final class ClientPortalColors {
    /** No dye: the portal texture is already vanilla purple, so no tint (shader packs read the raw texture). */
    public static final int DEFAULT = 0xFFFFFF;

    private static ResourceKey<Level> dimension;
    private static List<PortalColors.Entry> entries = List.of();

    public static void receive(PortalColorsPayload payload) {
        dimension = payload.dimension();
        entries = payload.entries();

        var mc = Minecraft.getInstance();

        if (mc.levelRenderer != null && mc.level != null) {
            for (var e : entries) {
                boolean x = e.axisId() == 0;
                BlockPos c = e.corner();
                mc.levelRenderer.setBlocksDirty(c.getX(), c.getY(), c.getZ(),
                        c.getX() + (x ? e.width() : 0), c.getY() + e.height(), c.getZ() + (x ? 0 : e.width()));
            }
        }
    }

    public static int tint(BlockPos pos) {
        var mc = Minecraft.getInstance();

        if (pos == null || mc.level == null || mc.level.dimension() != dimension) {
            return DEFAULT;
        }

        for (var e : entries) {
            if (e.contains(pos)) {
                return e.rgb();
            }
        }

        return DEFAULT;
    }

    private ClientPortalColors() {
    }
}
