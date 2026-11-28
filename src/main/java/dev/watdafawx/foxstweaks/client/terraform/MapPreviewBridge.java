package dev.watdafawx.foxstweaks.client.terraform;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Mod-agnostic map preview dispatcher. Holds the current selection polygon and forwards updates
 * to optional JourneyMap and FTB Chunks bridges without importing either mod here.
 */
public final class MapPreviewBridge {
    public record Preview(ResourceKey<Level> dimension, List<BlockPos> polygon, int rgb, String label) {
    }

    @Nullable
    private static volatile Preview current;

    @Nullable
    public static Preview current() {
        return current;
    }

    public static void update(ResourceKey<Level> dimension, List<BlockPos> polygon, int rgb, String label) {
        if (polygon.size() < 3) {
            clear();
            return;
        }
        Preview next = new Preview(dimension, List.copyOf(polygon), rgb & 0xFFFFFF, label);
        current = next;
        if (ModList.get().isLoaded("journeymap")) {
            dev.watdafawx.foxstweaks.client.compat.JourneyMapPreview.update(next);
        }
    }

    public static void clear() {
        if (current == null) return;
        current = null;
        if (ModList.get().isLoaded("journeymap")) {
            dev.watdafawx.foxstweaks.client.compat.JourneyMapPreview.clear();
        }
    }

    private MapPreviewBridge() {
    }
}
