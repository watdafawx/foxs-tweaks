package dev.mtop.foxstweaks;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Written to {@code config/foxstweaks-common.toml} on first run. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AUTO_SOLVE_ON_PICKUP = BUILDER
            .comment(
                    "Complete a relic's constellation research automatically, so its abilities are never",
                    "gated behind the star puzzle. Applies when a relic is picked up, and to any relic",
                    "already carried (so relics given in creative or taken from a chest are covered too).",
                    "",
                    "This only clears the research gate. Ability unlocks still cost relic levels and upgrade",
                    "points exactly as they normally do - no progression is skipped.",
                    "",
                    "Turn this off to solve constellations manually with the auto-solve button instead.")
            .define("autoSolveOnPickup", true);

    public static final ModConfigSpec.BooleanValue CURIOS_TOOLTIP_WORKAROUND = BUILDER
            .comment(
                    "Recover the hovered item ourselves when a screen's own tooltip rendering forgets to",
                    "attach it. Curios' inventory screen does this for every populated curio slot",
                    "(TheIllusiveC4/Curios#536), which otherwise blanks out the affix panel there.",
                    "",
                    "Only restores our own panel - Apotheosis' own tooltip content is Apotheosis' code",
                    "reading the same broken event, and this can't reach it. Turn off once Curios ships",
                    "its own fix (TheIllusiveC4/Curios#625).")
            .define("curiosTooltipWorkaround", true);

    public static final ModConfigSpec.BooleanValue ASCENSION_COMPAT = BUILDER
            .comment(
                    "Make third-party Apotheosis affixes (Apothic Compats, Iron's Apothic, Fallen Gems,",
                    "Ragnarok, ...) available at Apothic Ascension's rarities. Apothic Ascension only ships",
                    "values for Apotheosis' own affixes, so every other affix silently vanishes above Mythic.",
                    "",
                    "Values for the new rarities are extrapolated from each affix's own top tier on the same",
                    "curve Ascension uses. Server-side: the server needs this mod, clients do not.")
            .define("ascensionCompat", true);

    public static final ModConfigSpec.BooleanValue RAGNAROK_ANCIENT_REFORGING = BUILDER
            .comment(
                    "Let Apotheosis Modern Ragnarok's gun affixes roll at Ancient Reforging's Ancient rarity.",
                    "Ragnarok's affixes only know its own Ancient rarity, so a gun reforged at the Ancient",
                    "Reforging table would otherwise come out with no affixes at all. The values are",
                    "Ragnarok's own Ancient values, copied across. Server-side, like ascensionCompat.")
            .define("ragnarokAncientReforging", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
