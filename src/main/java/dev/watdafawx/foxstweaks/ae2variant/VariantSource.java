package dev.watdafawx.foxstweaks.ae2variant;

import net.neoforged.fml.ModList;

/** Sources of block-variant conversions supported by the ME Variant Crafter. */
public enum VariantSource {
    CHIPPED(0, "chipped", "Chipped", true),
    RECHISELED(1, "rechiseled", "Rechiseled", true),
    CHISEL(2, "chisel", "Chisel", true),
    FACTORY_BLOCKS(3, "factory_blocks", "Factory Blocks", true),
    STONECUTTER(4, "minecraft", "Stonecutter", false),
    CUSTOM_TAGS(5, "", "Custom Tags", true);

    public final int index;
    public final String modId;
    public final String displayName;
    public final boolean defaultEnabled;

    VariantSource(int index, String modId, String displayName, boolean defaultEnabled) {
        this.index = index;
        this.modId = modId;
        this.displayName = displayName;
        this.defaultEnabled = defaultEnabled;
    }

    public int bit() {
        return 1 << index;
    }

    public boolean isEnabled(int mask) {
        return (mask & bit()) != 0;
    }

    public boolean isModLoaded() {
        return modId.isEmpty() || "minecraft".equals(modId) || ModList.get().isLoaded(modId);
    }

    public static int defaultMask() {
        int mask = 0;
        for (VariantSource s : values()) {
            if (s.defaultEnabled) mask |= s.bit();
        }
        return mask;
    }

    public static VariantSource byIndex(int idx) {
        VariantSource[] vals = values();
        return idx >= 0 && idx < vals.length ? vals[idx] : CHIPPED;
    }
}
