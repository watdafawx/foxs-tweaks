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

    public static final ModConfigSpec.BooleanValue BACKPACK_RENDER_INFO_CACHE = BUILDER
            .comment(
                    "Stop Sophisticated Backpacks re-reading a backpack's render data every frame. Drawing a",
                    "backpack (hotbar, inventory, worn on your back) re-parsed its whole render tag, upgrade",
                    "items included, each time it was drawn. This keeps the parsed result until the backpack's",
                    "render data changes.",
                    "",
                    "Client-side only. Does nothing without Sophisticated Backpacks.")
            .define("backpackRenderInfoCache", true);

    public static final ModConfigSpec.BooleanValue TACZ_ATTACHMENT_TOOLTIP_NUMBERS = BUILDER
            .comment(
                    "Show how much a TACZ attachment changes each stat in its tooltip, not just which stats it",
                    "changes: '+ ADS Time (+0.1s)', '- Vertical Recoil (-10%)'. Units match TACZ's refit screen.",
                    "",
                    "Client-side only. Does nothing without TACZ.")
            .define("taczAttachmentTooltipNumbers", true);

    public static final ModConfigSpec.BooleanValue TEMPORAL_POUCH_CURIO = BUILDER
            .comment(
                    "Gadgets Against Grind's Temporal Pouch gets its own Curios slot, keeps filling with time",
                    "while it sits there, and a hotkey (Options -> Controls, unbound by default) uses it on the",
                    "block you're looking at as if it were in your hand. The slot itself comes from data and",
                    "shows whenever GAG and Curios are both installed; this toggle turns off the filling and",
                    "the hotkey.",
                    "",
                    "Server + client. Does nothing without GAG and Curios.")
            .define("temporalPouchCurio", true);

    public static final ModConfigSpec.BooleanValue AE2_EMI_QUICK_FILL = BUILDER
            .comment(
                    "Let EMI's craft binds (shift-click a stack in the index, favorites or craftables) fill",
                    "any AE2 terminal - crafting terminal, pattern encoder, wireless ones - using AE2's own",
                    "check, which counts the whole ME network and encodes patterns for items you don't have.",
                    "Replaces the 'AE2: EMI Quick Fill' mod without its freeze: EMI's craftables list keeps",
                    "EMI's cheap check instead of running AE2's full transfer for every recipe in the pack.",
                    "",
                    "Client-side only. Does nothing without AE2 and EMI.")
            .define("ae2EmiQuickFill", true);

    public static final ModConfigSpec.BooleanValue BOTANY_POTS_SOURCE = BUILDER
            .comment(
                    "Feed Ars Nouveau's Agronomic Sourcelink from crops grown in Botany Pots (any pot built on",
                    "Botany Pots - tiered, power, dimensional pots and Botany Gardens included). Each harvest",
                    "counts as one growth of that plant in the world: 20 source, 45 for Ars' magic plants,",
                    "50 for saplings, 100 for magic saplings. A sourcelink within 15 blocks of the pot collects it.",
                    "",
                    "Server-side. Does nothing without Ars Nouveau and Botany Pots.")
            .define("botanyPotsSource", true);

    public static final ModConfigSpec.BooleanValue APOTSPAWNER_SOURCE = BUILDER
            .comment(
                    "Feed Ars Nouveau's Vitalic Sourcelink from kills simulated by ApotSpawner Nexus' virtual",
                    "spawners, the same 200 source a real death nearby gives. A sourcelink within 15 blocks of",
                    "the spawner collects it; Ars' vitalic death blacklist still applies.",
                    "",
                    "Server-side. Does nothing without Ars Nouveau and ApotSpawner.")
            .define("apotspawnerSource", true);

    public static final ModConfigSpec.BooleanValue APOTSPAWNER_OWN_XP_FLUID = BUILDER
            .comment(
                    "Make ApotSpawner's spawners always export their own liquid experience (20 mB per XP,",
                    "tagged c:experience), even with Create Enchantment Industry installed. Otherwise they",
                    "switch to CEI's experience (1 mB per XP), which Just Dire Things' Experience Holder and",
                    "other c:experience tanks refuse. Turn off if CEI machines should pull XP straight from",
                    "spawners.",
                    "",
                    "Server-side. Does nothing without ApotSpawner.")
            .define("apotspawnerOwnXpFluid", true);

    public static final ModConfigSpec.BooleanValue APOTSPAWNER_DROP_MISSING_ITEMS = BUILDER
            .comment(
                    "Remove items from removed mods out of ApotSpawner spawners' stored loot and drop statistics.",
                    "ApotSpawner keeps them forever and re-parses them on every read, logging \"Tried to load",
                    "invalid item\" thousands of times an hour. Each spawner is cleaned once, the first time it is",
                    "read. The items are already lost either way; this is what vanilla does for a chest.",
                    "",
                    "Server-side. Does nothing without ApotSpawner.")
            .define("apotspawnerDropMissingItems", true);

    public static final ModConfigSpec.BooleanValue HOSTILE_NETWORKS_SOURCE = BUILDER
            .comment(
                    "Feed Ars Nouveau's Vitalic Sourcelink from Hostile Neural Networks' simulation chamber:",
                    "each finished mob simulation counts as one death (200 source). Block data models are not",
                    "mobs and give nothing. A sourcelink within 15 blocks of the chamber collects it.",
                    "",
                    "Server-side. Does nothing without Ars Nouveau and Hostile Neural Networks.")
            .define("hostileNetworksSource", true);

    public static final ModConfigSpec.BooleanValue EXTRA_HNN_SOURCE = BUILDER
            .comment(
                    "As hostileNetworksSource, for ExtraHNN's Ultimate Simulation Chamber and Simulation Modeling",
                    "machine. A merged data model counts one death per mob in it; the chamber tier's drop",
                    "multiplier does not add deaths.",
                    "",
                    "Server-side. Does nothing without Ars Nouveau and ExtraHNN.")
            .define("extraHnnSource", true);

    public static final ModConfigSpec.BooleanValue HNI_SOURCE = BUILDER
            .comment(
                    "As hostileNetworksSource, for Hostile Neural Industrialization's Modern Industrialization",
                    "sim chambers (single-block and multiblock): each finished recipe counts as one death.",
                    "",
                    "Server-side. Does nothing without Ars Nouveau and Hostile Neural Industrialization.")
            .define("hostileNeuralIndustrializationSource", true);

    // Performance patches. Each one targets another mod by class name, so a pack without that mod
    // simply never applies it; the toggle switches it off in a pack that has the mod.

    public static final ModConfigSpec.BooleanValue TRANSMOG_INVENTORY_FAST_PATH = BUILDER
            .comment(
                    "Stop Transmog copying your whole inventory every time something looks at it while the",
                    "game is drawing. Transmog swaps in each item's transmogged look by building a full copy",
                    "of the inventory per call; trinket layers, HUDs and ritual renderers call it many times a",
                    "frame (~10% of frame time in a spark profile). When nothing in the inventory is",
                    "transmogged the copy would be identical, so the real inventory is returned instead.",
                    "",
                    "Client-side only. Does nothing without Transmog.")
            .define("transmogInventoryFastPath", true);

    public static final ModConfigSpec.BooleanValue ARCHITECTURY_FAST_EVENTS = BUILDER
            .comment(
                    "Make Architectury's events call their listeners directly. For every listener of every",
                    "event, Architectury looked the method up again through reflection (MethodHandles",
                    "unreflect) and invoked it the slowest way; with many Architectury mods that was a few",
                    "percent of both client frame time and server tick time.",
                    "",
                    "Both sides. Does nothing without Architectury.")
            .define("architecturyFastEvents", true);

    public static final ModConfigSpec.BooleanValue CURIOS_CACHE_HANDLER_FIX = BUILDER
            .comment(
                    "Let Curios Performance Fix's client cache actually hit. Its cache is rebuilt whenever it",
                    "is asked through a different Curios inventory object, but Curios hands out a new one on",
                    "every lookup, so most lookups rebuilt it. This reuses one inventory object per entity",
                    "for up to a second (the same refresh window the cache itself uses).",
                    "",
                    "Client-side only. Does nothing without Curios Performance Fix.")
            .define("curiosCacheHandlerFix", true);

    public static final ModConfigSpec.BooleanValue GPUSHIFT_CLASSIFIER_CACHE = BUILDER
            .comment(
                    "Remember GPUShift's particle category per particle type. It worked the category out",
                    "again for every particle spawned, lower-casing class names up the class hierarchy each",
                    "time; the answer never changes for a given particle class.",
                    "",
                    "Client-side only. Does nothing without GPUShift.")
            .define("gpushiftClassifierCache", true);

    public static final ModConfigSpec.BooleanValue CHICKENS_RADIATION_KEEP_LOOT = BUILDER
            .comment(
                    "Stop Modern Chickens' Mekanism radiation check from filling structure chests. It reads every",
                    "container in loaded chunks once a second, and reading a chest that still has its loot table",
                    "rolls the loot into it, so new village and dungeon chests were filled before Lootr could turn",
                    "them into per-player loot chests. Containers with an unrolled loot table are skipped (they",
                    "are empty, so they can't hold anything radioactive).",
                    "",
                    "Server-side. Does nothing without Modern Chickens' Mekanism integration.")
            .define("chickensRadiationKeepLoot", true);

    public static final ModConfigSpec.BooleanValue SB_EMI_FILL = BUILDER
            .comment(
                    "Make EMI's \"+\" work in a crafting table for Sophisticated Backpacks recipes (tier upgrades,",
                    "dyeing). Sophisticated Core shows them in EMI split into pieces, so EMI said \"Current",
                    "workstation does not support recipe\". The fill uses a full copy of the recipe instead.",
                    "Switches itself off per recipe once Sophisticated Core shows full recipes.",
                    "",
                    "Client-side only. Does nothing without EMI and Sophisticated Backpacks.")
            .define("sbEmiFill", true);

    public static final ModConfigSpec.IntValue DEBUG_LOG_LOOT_ROLLS = BUILDER
            .comment(
                    "Debugging aid, off by default. When above 0, logs a stack trace for each of the first N",
                    "times a chest/barrel (any RandomizableContainer) rolls its loot table with no player",
                    "opening it - i.e. some mod read its contents. Used to find what fills structure loot",
                    "before Lootr can convert it. Look for 'Loot rolled without a player' in latest.log.")
            .defineInRange("debugLogLootRolls", 0, 0, 1000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Reads a toggle without throwing when the config isn't loaded yet. Patches on hot paths (events,
     * rendering) can run before FML loads the COMMON config, where {@code get()} throws; those calls just
     * take the original, unpatched path.
     */
    public static boolean enabled(ModConfigSpec.BooleanValue value) {
        if (!SPEC.isLoaded()) {
            return false;
        }

        return value.get();
    }

    private Config() {
    }
}
