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
        BUILDER.push("holograms");
    }

    public static final ModConfigSpec.BooleanValue HOLOGRAMS_ENABLED = BUILDER
            .comment(
                    "Allow the Hologram Wand to place holograms. Holograms are decoration only: a block shape drawn",
                    "at a spot, with no collision, no light, no ticking and nothing to break.",
                    "Turn this off to remove the feature without removing the mod.")
            .define("hologramsEnabled", true);

    public static final ModConfigSpec.IntValue HOLOGRAMS_MAX_PER_DIMENSION = BUILDER
            .comment(
                    "How many holograms one dimension may hold. 0 = unlimited.",
                    "Each hologram is one saved row and one model draw per frame while it is on screen, so the",
                    "cost is entirely in how many are visible at once.")
            .defineInRange("hologramsMaxPerDimension", 256, 0, 10000);

    public static final ModConfigSpec.IntValue HOLOGRAMS_MAX_RENDER_DISTANCE = BUILDER
            .comment(
                    "The furthest a single hologram may be set to render from, in blocks. The editor's range",
                    "control is capped to this, so a player cannot make a giant hologram that every client in",
                    "the area has to draw from the far side of the map.")
            .defineInRange("hologramsMaxRenderDistance", 256, 16, 512);

    public static final ModConfigSpec.IntValue HOLOGRAMS_EDIT_RANGE = BUILDER
            .comment(
                    "How far from a hologram a player may be while its editor is open and still save, delete or",
                    "preview it, in blocks. The studio camera and Grab mode move the player away from what they",
                    "are editing, and Done has to land anyway. Without an open editor the limit is 14 blocks",
                    "(wand reach plus 8), whatever this says. The session ends on Done, Delete, closing the",
                    "editor, logging out, changing dimension or ten idle minutes.")
            .defineInRange("hologramsEditRange", 64, 14, 512);

    public static final ModConfigSpec.IntValue HOLOGRAM_STRUCTURE_MAX_DIMENSION = BUILDER
            .comment(
                    "Maximum width, height or depth (in blocks) of a structure grabbed into a hologram layer.")
            .defineInRange("hologramStructureMaxDimension", 32, 1, 64);

    public static final ModConfigSpec.IntValue HOLOGRAM_STRUCTURE_MAX_BLOCKS = BUILDER
            .comment(
                    "Maximum number of non-air blocks allowed in a single grabbed structure hologram layer.")
            .defineInRange("hologramStructureMaxBlocks", 4096, 1, 32768);

    public static final ModConfigSpec.IntValue HOLOGRAM_STRUCTURE_MAX_BYTES = BUILDER
            .comment(
                    "Maximum NBT size (in bytes) of a single grabbed structure hologram layer, including block entity data.")
            .defineInRange("hologramStructureMaxBytes", 262144, 16384, 1048576);

    public static final ModConfigSpec.IntValue HOLOGRAM_TIMELINE_MAX_TRACKS = BUILDER
            .comment(
                    "Maximum number of keyframe tracks in one hologram's timeline (one track = one animated property",
                    "of one layer or group). Specs over the cap are refused when saved, from the editor or the web editor.")
            .defineInRange("hologramTimelineMaxTracks", 64, 0, 256);

    public static final ModConfigSpec.IntValue HOLOGRAM_TIMELINE_MAX_KEYS = BUILDER
            .comment(
                    "Maximum number of keyframes in one hologram's timeline, over all of its tracks.")
            .defineInRange("hologramTimelineMaxKeys", 2048, 0, 8192);

    public static final ModConfigSpec.IntValue HOLOGRAM_IMAGE_MAX_LAYERS = BUILDER
            .comment(
                    "Maximum number of image layers (pictures, GIFs, videos from the asset store) in one hologram.",
                    "Each visible image layer is one textured quad per frame plus one texture upload per animation frame.")
            .defineInRange("hologramImageMaxLayers", 8, 0, 16);

    public static final ModConfigSpec.IntValue HOLOGRAM_AUDIO_MAX_PER_VALUE = BUILDER
            .comment(
                    "Maximum number of audio modulations stacked on one hologram value (one effect parameter, or one",
                    "property of one layer or group). Specs over the cap are refused when saved. 0 turns music",
                    "reactive holograms off for new saves.")
            .defineInRange("hologramAudioMaxPerValue", 4, 0, 8);

    public static final ModConfigSpec.IntValue HOLOGRAM_AUDIO_MAX_PER_HOLOGRAM = BUILDER
            .comment(
                    "Maximum number of audio modulations in one hologram, over every effect, layer and group.")
            .defineInRange("hologramAudioMaxPerHologram", 64, 0, 256);

    static {
        BUILDER.pop();
        BUILDER.push("media");
    }

    public static final ModConfigSpec.BooleanValue MEDIA_FFMPEG_ENABLED = BUILDER
            .comment(
                    "Allow turning an uploaded video into a frame sequence (JPEG frames) for hologram images.",
                    "The work is done by a separate ffmpeg process, never in the game's own threads.",
                    "Turn this off to remove the feature without removing the mod.")
            .define("mediaFfmpegEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> MEDIA_FFMPEG_PATH = BUILDER
            .comment(
                    "Where ffmpeg lives: the executable itself, or the folder holding it.",
                    "Empty = look for ffmpeg on the server's PATH, then in the usual places",
                    "(C:\\ffmpeg\\bin, %ProgramFiles%\\ffmpeg\\bin, the WinGet / Chocolatey / Scoop shims,",
                    "/usr/bin, /usr/local/bin, /snap/bin). The binary is checked with 'ffmpeg -version' on first use.")
            .define("mediaFfmpegPath", "");

    public static final ModConfigSpec.IntValue MEDIA_CONVERT_TIMEOUT_SECONDS = BUILDER
            .comment(
                    "How long one conversion may run before ffmpeg is killed, in seconds. The whole process tree",
                    "is killed, so a stuck video cannot hold the server's single conversion slot forever.")
            .defineInRange("mediaConvertTimeoutSeconds", 60, 5, 600);

    static {
        BUILDER.pop();
    }

    static { BUILDER.push("assets"); }

    public static final ModConfigSpec.IntValue ASSET_MAX_FILE_BYTES = BUILDER.comment("Maximum uploaded file and canonical asset bytes.").defineInRange("assetMaxFileBytes", 2097152, 1024, 16777216);

    public static final ModConfigSpec.IntValue ASSET_MAX_WORLD_BYTES = BUILDER.comment("Maximum stored asset bytes per world.").defineInRange("assetMaxWorldBytes", 209715200, 1024, 1073741824);
    public static final ModConfigSpec.BooleanValue ASSET_UPLOAD_EVERYONE = BUILDER.comment("Allow everyone to upload; otherwise operators only.").define("assetUploadEveryone", false);

    public static final ModConfigSpec.IntValue ASSET_MAX_DIMENSION = BUILDER.comment("Larger images are downscaled preserving aspect ratio.").defineInRange("assetMaxDimension", 256, 16, 1024);

    public static final ModConfigSpec.IntValue ASSET_MAX_FPS = BUILDER.comment("Maximum animation frames per second.").defineInRange("assetMaxFps", 15, 1, 30);

    public static final ModConfigSpec.IntValue ASSET_MAX_SECONDS = BUILDER.comment("Maximum animation duration in seconds.").defineInRange("assetMaxSeconds", 10, 1, 30);

    public static final ModConfigSpec.IntValue ASSET_MAX_PER_PLAYER = BUILDER.comment("Maximum assets owned by each player.").defineInRange("assetMaxPerPlayer", 100, 1, 10000);
    static { BUILDER.pop(); }

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

    public static boolean hologramsEnabled() {
        return !loaded() || HOLOGRAMS_ENABLED.get();
    }

    public static int hologramsMaxPerDimension() {
        return loaded() ? HOLOGRAMS_MAX_PER_DIMENSION.get() : 256;
    }

    public static int hologramsMaxRenderDistance() {
        return loaded() ? HOLOGRAMS_MAX_RENDER_DISTANCE.get() : 256;
    }

    public static int hologramsEditRange() {
        return loaded() ? HOLOGRAMS_EDIT_RANGE.get() : 64;
    }

    public static int hologramStructureMaxDimension() {
        return loaded() ? HOLOGRAM_STRUCTURE_MAX_DIMENSION.get() : 32;
    }

    public static int hologramStructureMaxBlocks() {
        return loaded() ? HOLOGRAM_STRUCTURE_MAX_BLOCKS.get() : 4096;
    }

    public static int hologramStructureMaxBytes() {
        return loaded() ? HOLOGRAM_STRUCTURE_MAX_BYTES.get() : 262144;
    }

    public static boolean mediaFfmpegEnabled() {
        return !loaded() || MEDIA_FFMPEG_ENABLED.get();
    }

    public static String mediaFfmpegPath() {
        return loaded() ? MEDIA_FFMPEG_PATH.get() : "";
    }

    public static int mediaConvertTimeoutSeconds() {
        return loaded() ? MEDIA_CONVERT_TIMEOUT_SECONDS.get() : 60;
    }

    public static int hologramTimelineMaxTracks() {
        return loaded() ? HOLOGRAM_TIMELINE_MAX_TRACKS.get() : 64;
    }

    public static int hologramImageMaxLayers() {
        return loaded() ? HOLOGRAM_IMAGE_MAX_LAYERS.get() : 8;
    }

    public static boolean assetUploadEveryone() {
        return loaded() && ASSET_UPLOAD_EVERYONE.get();
    }

    public static int hologramTimelineMaxKeys() {
        return loaded() ? HOLOGRAM_TIMELINE_MAX_KEYS.get() : 2048;
    }

    public static int hologramAudioMaxPerValue() {
        return loaded() ? HOLOGRAM_AUDIO_MAX_PER_VALUE.get() : 4;
    }

    public static int hologramAudioMaxPerHologram() {
        return loaded() ? HOLOGRAM_AUDIO_MAX_PER_HOLOGRAM.get() : 64;
    }

    private ServerConfig() {
    }
}
