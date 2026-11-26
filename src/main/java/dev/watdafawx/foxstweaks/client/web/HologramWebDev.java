package dev.watdafawx.foxstweaks.client.web;

import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dev check hooks into the web JSON, which stays package-private. */
public final class HologramWebDev {

    private HologramWebDev() {
    }

    /**
     * {@code spec} written as the page receives it and read back as if the page sent it unchanged; a refusal comes
     * back as an {@link IllegalArgumentException} with the reason.
     */
    public static HologramSpec roundTrip(HologramSpec spec, int maxRenderDistance) {
        Map<String, CompoundTag> keys = new LinkedHashMap<>();
        var json = HologramWebJson.write(spec, maxRenderDistance, keys, k -> {
            CompoundTag tag = keys.get(k);
            return tag == null ? "" : tag.toString();
        });
        try {
            return HologramWebJson.read(json, spec, maxRenderDistance, keys);
        } catch (HologramWebJson.Invalid e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** The JSON of {@code spec} as the page receives it. */
    public static String json(HologramSpec spec, int maxRenderDistance) {
        return HologramWebJson.write(spec, maxRenderDistance, new LinkedHashMap<>(), k -> "").toString();
    }
}
