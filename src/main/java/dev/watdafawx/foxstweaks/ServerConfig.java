package dev.watdafawx.foxstweaks;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Per-world settings, written to {@code <world>/serverconfig/foxstweaks-server.toml} (defaults copied from
 * {@code defaultconfigs/foxstweaks-server.toml} when the world has none). NeoForge syncs them to connected
 * clients, so a client sees the server's values and can never change them; edit the file on the server or
 * use {@code /foxstweaks portal frame ...}.
 *
 * <p>Only what the server decides lives here. The {@code overworldCopy} switch stays in {@link Config}: the
 * datapack condition reads it before any world, and so before this file, is loaded.
 */
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final String DEFAULT_FRAME = "minecraft:stone_bricks";

    static {
        BUILDER.push("portal");
    }

    public static final ModConfigSpec.ConfigValue<List<? extends String>> OVERWORLD_COPY_FRAME = BUILDER
            .comment(
                    "Blocks (or #tags) that make a portal frame to the overworld copy. Same rules as a Nether",
                    "portal: a rectangle of these, 2-21 wide and 3-21 tall inside, corners optional. Light it",
                    "with flint and steel or a fire charge. The same portal leads back from the copy to the",
                    "main overworld. Applies at once, also to a running server; portals already built keep",
                    "working only while their frame still matches this list.",
                    "",
                    "minecraft:obsidian is ignored: it would turn every Nether portal into one of these.",
                    "Change it in game with /foxstweaks portal frame set <block>.")
            .defineListAllowEmpty("overworldCopyPortalFrame", List.of(DEFAULT_FRAME),
                    () -> DEFAULT_FRAME, o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue OVERWORLD_COPY_INSTANT = BUILDER
            .comment(
                    "Teleport through a portal to the overworld copy the moment you step in, with no wait.",
                    "Off: the usual portal delay (the Nether portal game rules) applies.")
            .define("overworldCopyInstantTeleport", true);

    public static final ModConfigSpec.BooleanValue OVERWORLD_COPY_FLAT = BUILDER
            .comment(
                    "When a portal to the overworld copy has to be built, level a small pad of surface around it",
                    "(about 9x9): the ground is raised or cut to the portal's floor with the biome's own surface",
                    "block, and anything standing on it is cleared. Terrain, caves and everything else are left",
                    "as generated. Off by default.")
            .define("overworldCopyFlatSurface", false);

    public static final ModConfigSpec.BooleanValue OVERWORLD_COPY_DYE = BUILDER
            .comment("Right-click a portal to the overworld copy with a dye to colour it. Needs the mod on the client to be seen.")
            .define("overworldCopyPortalDye", true);

    static {
        BUILDER.pop();
        BUILDER.push("performance");
    }

    public static final ModConfigSpec.BooleanValue PERF_OVERLAY_ENABLED = BUILDER
            .comment("Allow the server to send live performance snapshots (TPS, MSPT, CPU, heap, world counts) to clients holding Tab or using the mini-HUD.")
            .define("perfOverlayEnabled", true);

    public static final ModConfigSpec.BooleanValue PERF_OVERLAY_OP_ONLY = BUILDER
            .comment("Restrict server performance snapshots to operators (permission level 2+). Off by default so every player can check server TPS/MSPT.")
            .define("perfOverlayOpOnly", false);

    static {
        BUILDER.pop();
        BUILDER.push("spawnAnchors");
    }

    public static final ModConfigSpec.BooleanValue SPAWN_ANCHORS_ENABLED = BUILDER
            .comment("Allow virtual spawn anchors (/foxstweaks anchor add|remove|list|tp) that force-load chunks, run random ticks (Dreadful Dirt, crops), activate spawners, and prevent mob despawning.")
            .define("spawnAnchorsEnabled", true);

    public static final ModConfigSpec.IntValue SPAWN_ANCHORS_MAX_RADIUS = BUILDER
            .comment("Maximum chunk radius allowed for a virtual spawn anchor (1 = 3x3 chunks, 2 = 5x5, 4 = 9x9).")
            .defineInRange("spawnAnchorsMaxRadius", 4, 1, 8);

    public static final ModConfigSpec.BooleanValue SPAWN_ANCHORS_APOTHEOSIS_TIER = BUILDER
            .comment(
                    "Let a spawn anchor carry an Apotheosis world tier, so mobs spawning around it get the tier of the",
                    "player who placed it even when they are far away or offline. Without this, a loaded farm only",
                    "gets Apotheosis bosses, elites and tier augments while a player is somewhere in that dimension.",
                    "The tier is captured when the anchor is placed and follows the owner live while they are online;",
                    "change it with /foxstweaks anchor tier <name> <haven|frontier|ascent|summit|pinnacle|off>.",
                    "Does nothing unless Apotheosis is installed.")
            .define("spawnAnchorsApotheosisTier", true);

    public static final ModConfigSpec.IntValue SPAWN_ANCHORS_TIER_PLAYER_RANGE = BUILDER
            .comment(
                    "How close a real player has to be to a spawn to keep their own Apotheosis tier instead of the",
                    "anchor owner's. A player inside this radius always wins; nobody inside means the anchor's tier",
                    "is used. 0 = the anchor always wins.")
            .defineInRange("spawnAnchorsTierPlayerRange", 128, 0, 512);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** False before a world's server config is read (main menu, datapack loading); callers fall back to the defaults. */
    public static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static boolean instant() {
        return !loaded() || OVERWORLD_COPY_INSTANT.get();
    }

    public static boolean flat() {
        return loaded() && OVERWORLD_COPY_FLAT.get();
    }

    public static boolean dye() {
        return !loaded() || OVERWORLD_COPY_DYE.get();
    }

    public static boolean perfOverlayEnabled() {
        return !loaded() || PERF_OVERLAY_ENABLED.get();
    }

    public static boolean perfOverlayOpOnly() {
        return loaded() && PERF_OVERLAY_OP_ONLY.get();
    }

    public static boolean spawnAnchorsEnabled() {
        return !loaded() || SPAWN_ANCHORS_ENABLED.get();
    }

    public static int spawnAnchorsMaxRadius() {
        return loaded() ? SPAWN_ANCHORS_MAX_RADIUS.get() : 4;
    }

    public static boolean spawnAnchorsApotheosisTier() {
        return !loaded() || SPAWN_ANCHORS_APOTHEOSIS_TIER.get();
    }

    public static int spawnAnchorsTierPlayerRange() {
        return loaded() ? SPAWN_ANCHORS_TIER_PLAYER_RANGE.get() : 128;
    }

    private ServerConfig() {
    }
}
