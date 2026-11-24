package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramSpec;

import javax.annotation.Nullable;

/**
 * The copied hologram, client-side only and session-only.
 *
 * <p>It holds a whole {@link HologramSpec} rather than a trimmed "style" record: identity, position and
 * owner are simply ignored when pasting, while everything else (block, blockstate values, scale,
 * rotation, spin, hover, glow, range) comes along for free and cannot drift out of sync with the spec
 * when a field is added later.
 *
 * <p>Not persisted on purpose - a copy that survives a relog is a surprise, and a saved clipboard would
 * need a file, cleanup and a way to see what is in it.
 */
public final class HologramClipboard {
    @Nullable
    private static HologramSpec copied;

    public static void copy(HologramSpec spec) {
        copied = spec;
    }

    @Nullable
    public static HologramSpec peek() {
        return copied;
    }

    public static boolean has() {
        return copied != null;
    }

    /** What to show next to the paste row. */
    public static String label() {
        return copied == null ? "" : copied.primaryId();
    }

    private HologramClipboard() {
    }
}
