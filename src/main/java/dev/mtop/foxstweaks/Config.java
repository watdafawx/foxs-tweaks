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

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
