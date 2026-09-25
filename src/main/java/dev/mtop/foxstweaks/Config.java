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

    public static final ModConfigSpec.BooleanValue AUTO_SOLVE_BUTTON = BUILDER
            .comment(
                    "Show the auto-solve button next to the hint button on Relics' research screen. Clicking",
                    "it draws the correct constellation for you, exactly as if you had dragged the stars",
                    "yourself (no experience cost).",
                    "",
                    "Client-side only. Independent of autoSolveOnPickup - turn that off and leave this on to",
                    "solve on demand, or turn this off to hide the button entirely.")
            .define("autoSolveButton", true);

    public static final ModConfigSpec.BooleanValue AFFIX_TOOLTIP_PANEL = BUILDER
            .comment(
                    "Show the second tooltip panel listing an item's Apotheosis affixes, attributes and gem",
                    "sockets while the 'show affix info' key is held (Left Ctrl by default, rebindable under",
                    "Controls). The item's normal tooltip is left untouched.",
                    "",
                    "Client-side only.")
            .define("affixTooltipPanel", true);

    public static final ModConfigSpec.BooleanValue AFFIX_TOGGLE_GUI = BUILDER
            .comment(
                    "Let the 'toggle affixes' key (unbound by default - set it under Controls) open the held",
                    "item's own tooltip as a clickable overlay: hover an affix line and click to disable or",
                    "re-enable it. Works for any item, not just the handful Apotheosis itself lets you toggle",
                    "(mining radius, etc). A disabled affix is actually removed from the item (so it truly",
                    "stops applying, including effects this mod never touches) and kept so it can be put back",
                    "exactly as it was; it still shows on the tooltip as a red ❌ line.",
                    "",
                    "The server needs this mod for a toggle to actually take effect; the client needs it too",
                    "for the key and overlay to exist at all.")
            .define("affixToggleGui", true);

    public static final ModConfigSpec.BooleanValue GUN_DAMAGE_SCALING = BUILDER
            .comment(
                    "Scale gun damage up with the held gun's Apothic Ascension rarity. A gun's damage is fixed by",
                    "its gun pack (TACZ) or its own base stat (Point Blank) and only gains a few flat points",
                    "from affixes, so it falls far behind Ascension's mobs, whose health and armor are tuned",
                    "for melee gear. Guns of Ascension's rarities get a damage multiplier; every other rarity",
                    "is untouched.",
                    "",
                    "Applies to bullet hits fired by players - TACZ (including Ragnarok's fire/ice and",
                    "armor-piercing parts) and Vic's Point Blank alike. The multiplier climbs from Legendary",
                    "to Apotheotic on the same curve the affix extrapolation uses, reaching",
                    "gunDamageMaxMultiplier at Apotheotic.",
                    "",
                    "Server-side: the server needs this mod, clients do not.")
            .define("gunDamageScaling", true);

    public static final ModConfigSpec.DoubleValue GUN_DAMAGE_MAX_MULTIPLIER = BUILDER
            .comment(
                    "Damage multiplier a gun reaches at Apotheotic, Ascension's top rarity (see gunDamageScaling).",
                    "Lower rarities get a share of it, e.g. Legendary is about 12% of the way from 1x to this.",
                    "A heuristic starting point: tune it against the mobs you actually fight.")
            .defineInRange("gunDamageMaxMultiplier", 20.0, 1.0, 1000.0);

    public static final ModConfigSpec.BooleanValue PRINTER_NEARBY_STORAGE = BUILDER
            .comment(
                    "Let Vic's Point Blank's weapon printer take a recipe's ingredients from storage near the",
                    "printer, not just from the crafting player's pockets - the way Ars Nouveau's scribe's",
                    "table does. Chests, barrels and any block with an item inventory work, and so does Applied",
                    "Energistics 2: an ME Interface with nothing configured serves anything in its network,",
                    "with no setup (ExtendedAE and other addons' interfaces included). A configured interface",
                    "offers only the items it is set to stock.",
                    "",
                    "The player's own inventory is always used first. Point Blank enables its Craft button on",
                    "the client, from the player's own inventory, so the server also tells the client what is",
                    "nearby while the printer is open. The server needs this mod for the craft to work; the",
                    "client needs it too for the Craft button to enable (a client without it can still join).")
            .define("printerNearbyStorage", true);

    public static final ModConfigSpec.IntValue PRINTER_STORAGE_RANGE = BUILDER
            .comment(
                    "How far from the printer to look for storage, in blocks (see printerNearbyStorage).",
                    "Vertically it looks at most 2 blocks up and down. Ars Nouveau uses 6.")
            .defineInRange("printerStorageRange", 6, 1, 16);

    public static final ModConfigSpec.BooleanValue TACZ_WORKBENCH_NEARBY_STORAGE = BUILDER
            .comment(
                    "Let TACZ's gunsmith table take a recipe's ingredients from storage near the table, not",
                    "just from the crafting player's pockets - the same idea as printerNearbyStorage, for",
                    "every gun pack's own workbench (they all share TACZ's own table block). Chests, barrels",
                    "and any block with an item inventory work, and so does Applied Energistics 2 (see",
                    "printerNearbyStorage's comment for how ME Interfaces behave).",
                    "",
                    "The player's own inventory is always used first. The table's own per-ingredient count",
                    "display is also topped up with nearby stock, so it stops showing 'not enough' for",
                    "something the craft would actually accept. The server needs this mod for the craft to",
                    "work; the client needs it too for that display to be accurate (a client without it can",
                    "still join - the display just goes back to pockets-only).")
            .define("taczWorkbenchNearbyStorage", true);

    public static final ModConfigSpec.IntValue TACZ_WORKBENCH_STORAGE_RANGE = BUILDER
            .comment("How far from the table to look for storage, in blocks (see taczWorkbenchNearbyStorage).",
                    "Vertically it looks at most 2 blocks up and down.")
            .defineInRange("taczWorkbenchStorageRange", 6, 1, 16);

    public static final ModConfigSpec.BooleanValue AMMO_BOX_RESET = BUILDER
            .comment(
                    "Let a held TACZ ammo box's stocked ammo type be cleared with sneak + right-click in the",
                    "air, so a different type can be set afterwards. TACZ locks a box to whichever ammo it was",
                    "first right-clicked on and gives no way to change it otherwise (creative boxes included).",
                    "",
                    "Server-side: the server needs this mod, clients do not.")
            .define("ammoBoxReset", true);

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

    public static final ModConfigSpec.BooleanValue ANCIENT_REFORGING_COMPAT = BUILDER
            .comment(
                    "Make third-party Apotheosis affixes (Apothic Point Blank, Apothic Compats, Iron's Apothic,",
                    "...) available at Ancient Reforging's Ancient rarity. An affix only exists at rarities it",
                    "lists a value for, so a weapon reforged at the Ancient Reforging table comes out with no",
                    "affixes of these packs at all ('no affixes available' in the log).",
                    "",
                    "Ancient sits level with Apothic Ascension's Legendary, so it gets the values extrapolated",
                    "for Legendary, from each affix's own top tier. Affixes that already list Ancient (or get",
                    "it from ragnarokAncientReforging) are left alone. Works without Apothic Ascension.",
                    "Server-side, like ascensionCompat.")
            .define("ancientReforgingCompat", true);

    public static final ModConfigSpec.BooleanValue RAGNAROK_ANCIENT_REFORGING = BUILDER
            .comment(
                    "Let Apotheosis Modern Ragnarok's gun affixes roll at Ancient Reforging's Ancient rarity.",
                    "Ragnarok's affixes only know its own Ancient rarity, so a gun reforged at the Ancient",
                    "Reforging table would otherwise come out with no affixes at all. The values are",
                    "Ragnarok's own Ancient values, copied across. Server-side, like ascensionCompat.")
            .define("ragnarokAncientReforging", true);

    public static final ModConfigSpec.BooleanValue ICON_PICKER_CACHE = BUILDER
            .comment(
                    "Make EZActions' icon picker open fast. EZActions rebuilds its item list and every item",
                    "name slowly in the background after each launch; on a big pack that is tens of seconds",
                    "of 'Indexing icons' / 'Preparing names'. This builds the list at once and remembers the",
                    "names on disk (cache/foxstweaks_icon_names.json, re-resolved per mod when that mod",
                    "changes version; language and resource packs are ignored, so delete the file after",
                    "switching language).",
                    "",
                    "Client-side only. Does nothing without EZActions.")
            .define("iconPickerCache", true);

    public static final ModConfigSpec.BooleanValue GUIDEME_TOOLTIP_FIX = BUILDER
            .comment(
                    "Stop GuideME's 'Hold [G] to open guide' tooltip line from flickering, or showing another",
                    "item's guide, when some other mod builds a tooltip for a different item every tick.",
                    "",
                    "Client-side only. Does nothing without GuideME.")
            .define("guidemeTooltipFix", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
