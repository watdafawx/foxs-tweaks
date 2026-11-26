# AGENTS.md

Guidance for AI agents working in this repository.

## What this is

A NeoForge **1.21.1** mod (`foxstweaks`, "Fox's Tweaks") that is really a bundle of unrelated
quality-of-life features sharing a jar, among them:

1. **Relics integration** — auto-completes the constellation "star puzzle" research, both on pickup
   and via a button on the research screen.
   Also: right-click / key opens a relic's research screen (`relics/client/RelicQuickOpenHandler`);
   auto-level, rank-up, min XP and ability unlock run server-side in `relics/AutoLevelHandler` (marked abilities
   and mode live in the `auto_level` data component, an `AutoLevelSetting`: id list, round-robin flag, cursor; the old
   single-id `auto_level_ability` is still read as a one-ability list. Mode "in order" finishes the first ability before
   the next, "round robin" gives one upgrade each in turn. Rank-up waits until every marked ability is done. Left click
   on the star marks/unmarks, right or Shift click cycles the mode; payload channel version "2"), set by `AutoLevelPayload` from `AutoLevelWidget` on the
   ability screen). Dev check: `build.bat runRelicsShot` (game dir `run-relics/`, needs relics, curios, OctoLib,
   architectury jars) writes `foxstweaks-relics-check.txt`.
   **Overworld copy** (`dimension/`, `mixin/NetherPortalBlockMixin`): datapack dimension `foxstweaks:overworld_copy`
   (gated by the `foxstweaks:overworld_copy` load condition, i.e. the `overworldCopy` config). Portals are vanilla
   nether_portal blocks in a frame of the configured block; a portal block is "ours" exactly when a complete
   `CopyShape` surrounds it, and the mixin then keeps it alive, sends it to the other overworld and zeroes the wait.
   Dye colours live in `PortalColors` (SavedData) and reach clients through `PortalColorsPayload`; the tint is a
   block colour handler on vanilla's portal block plus our model/texture override. Dev check:
   `build.bat runPortalShot` writes `run-portal/foxstweaks-portal-check.txt`.
   The frame, instant, flat and dye keys live in `ServerConfig` (SERVER type, `<world>/serverconfig/foxstweaks-server.toml`,
   synced to clients); `overworldCopy` stays in COMMON `Config` because the datapack condition reads it before any world
   loads. `OverworldCopyCommand`: `/foxstweaks portal frame [set|add|remove|reset]` writes the server config;
   `/foxstweaks portal regenerate [cancel]` (op 4, refused while a player is in the copy) drops a marker file, and
   `ServerAboutToStartEvent` deletes the copy's `dimensions/foxstweaks/overworld_copy` folder on the next start (live
   deletion is impossible: the region files are open). The portals themselves are verified by
   `build.bat runPortalShot` (`run-portal/foxstweaks-portal-check.txt`, all PASS: frame, dye, both ways,
   vanilla frames untouched, instant teleport, flat pad); `/foxstweaks portal regenerate` has not been
   exercised in game.
2. **Apotheosis integration** — a second tooltip panel, shown while a key is held, listing an item's
   affixes, attributes and gem sockets without touching the main tooltip.
3. **Apotheosis affix toggles** — a hotkey opens the held item's own tooltip as a clickable overlay
   (any item, not just the few Apotheosis itself lets you toggle); click an affix line to disable or
   re-enable it.
4. **Apotheosis rarity compat** — server-side data patches that make third-party affix packs work
   with Apothic Ascension's rarities, and Ragnarok's gun affixes work with Ancient Reforging.
5. **Gun damage scaling** — server-side; guns of Apothic Ascension rarities deal more damage.
6. **Point Blank printer storage** — server-side (+ client for the Craft button); the weapon printer
   can pull ingredients from nearby chests and AE2.
7. **TACZ gunsmith table storage** — server-side; every gun pack's own workbench can do the same.
8. **TACZ ammo box reset** — server-side; clears a creative ammo box's locked-in ammo type.
9. **EZActions icon picker cache** — client-side mixin that makes its icon picker open fast.
10. **Ars Nouveau source from simulations** — server-side; Botany Pots harvests feed Agronomic
    Sourcelinks; ApotSpawner kills and Hostile Neural Networks (plus ExtraHNN / HNI) simulations feed
    Vitalic ones.
11. **GAG Temporal Pouch curio** — a Curios slot for Gadgets Against Grind's Temporal Pouch, which keeps
    filling there, plus a hotkey that uses it on the looked-at block.
12. **Stale mixin guard** — `mixinguard/StaleMixinCanceller` switches off *other* mods' mixins that
    call a class the mod they patch has since deleted (e.g. ExtendedAE Plus's JEI bookmark mixin after
    JEI 19.64 removed `CombinedInputHandler`). Without it the target class fails to load and the parent
    mod breaks. Logs `Disabled mixin ...` per cancel. Runs through MixinSquared, bundled jar-in-jar.
13. **Bedrock Ores thread fix** — server-side; `mixin/BedrockOresCleanupMixin` hands Bedrock Ores'
    unconfigured-ore cleanup queueing to the server thread. Distant Horizons' server-side world gen
    adds bedrock ores from its own threads, which corrupted the queue and crashed the server tick
    ("pos is null" in `UnconfiguredOreCleanup.isLoaded`).
14. **IPN buttons in FTB's sidebar** — client-side; `client/compat/IpnSidebar` adds Inventory Profiles
    Next editor + settings buttons to FTB Library's sidebar via plain sidebar JSON
    (`assets/foxstweaks/sidebar_buttons/`) whose click is FTB's `static_method:` action. Shift/Ctrl/Alt+click
    on the editor toggles IPN ignore (screen / container) or player-side-only, saved the way IPN's own
    editor saves (`client/compat/IpnBridge`, the only class importing IPN). Needs FTB Library *and* IPN.
    Dev testing: `build.bat runClientLite` (game dir `run-lite/`, 1 GB heap) with just IPN, libIPN,
    Kotlin for Forge, FTB Library and Architectury in `run-lite/mods`. IPN 2.2.5 crashes on a near-empty
    mod list (cyclic static init, `Features.ENABLE_PROFILES$delegate` null); `IpnSidebar#devInitOrder`
    works around it in dev runs only.
    **owo-lib screens (Accessories' default `AccessoriesExperimentalScreen`)** broke the whole FTB sidebar:
    owo's UI adapter spans the screen, so it wins vanilla's release lookup (`getChildAt`) and FTB, which fires
    clicks on release, never sees them (`client/compat/FtbSidebarRelease` forwards the release); and the
    tooltip text got clipped by owo's scissors at the next `flush()` (`mixin/FtbSidebarTooltipFlushMixin`
    flushes as the sidebar finishes). Test with Accessories + owo-lib added to `run-lite/mods`.
15. **TACZ model fast path** — client-side; `mixin/TaczBedrockCubeMixin` + `client/compat/TaczBedrockQuads`
    draw TACZ's gun/attachment/ammo model cubes from a flat copy without zero-area quads. Replaces our
    TACZ fork's `perf` branch (its NBT half went upstream). See
    [TACZ model fast path](#tacz-model-fast-path-mixintaczbedrockcubemixin-clientcompattaczbedrockquads).
16. **EMI Slim** — client-side; `emi/*` + `mixin/EmiSlim*Mixin` cut EMI's duplicated item data (shared
    tag stack lists, interned component patches). Merged in from the standalone EMI Slim mod (0.1.1). See
    [EMI Slim](#emi-slim-emi-mixinemislimmixin).
17. **Kaleidoscope Grilling skewer cache** — `mixin/GrillingDataManagerMixin`, `mixin/SkewerRecipesMixin`
    (+ `GrillingDataManagerInvoker`) stop Grilling rebuilding its skewer recipe list for every item drawn. See
    [Kaleidoscope Grilling skewer cache](#kaleidoscope-grilling-skewer-cache-mixingrillingdatamanagermixin-mixinskewerrecipesmixin).
18. **Shrink hotkey** — `shrink/ShrinkNetwork` + `client/ShrinkKeyHandler`: a key shrinks the looked-at mob/player or
    yourself with a Shrink Shrinking Device from anywhere (inventory, Curios); Shift + key sets its scale. See [Shrink hotkey](#shrink-hotkey-shrinkshrinknetwork-clientshrinkkeyhandler).
19. **Settings screen** — `client/ConfigScreenKeys`: NeoForge's generated config screen, from the Mods list and a key.
    **Every new config key needs a `foxstweaks.configuration.<key>` label in `en_us.json`**, or the screen shows the
    raw key. Tooltips fall back to the value's `.comment(...)`.
    **Both config files are grouped into sections** (`BUILDER.push(...)` in `push`/`pop` static initializers), which
    NeoForge renders as one "Edit" row per section instead of a flat list: common = relics, world, apotheosis,
    storage, performance, integrations, misc, holograms; server = portal, performance, spawnAnchors, holograms, media, assets. Each section name needs
    its own `foxstweaks.configuration.<section>` label too. Moving a key into a section **changes its path in the
    toml**, so values written before that move are ignored and fall back to defaults - say so in the CHANGELOG.
    Dev check: `build.bat runConfigCheck` (game dir `run-config/`, no extra mods) reads the specs to assert the
    sections and that every section/key label resolves, opens the real screen, and screenshots it. NeoForge's
    config rows are list entries, not screen children, so a check cannot click into a section without widening
    `OptionsSubScreen#list`; assert on the spec instead.
20. **Iris shader settings search** — client-side; `client/compat/IrisSettingsSearch` + two Iris mixins put a search
    box under Iris' shader pack settings. See [Iris settings search](#iris-shader-settings-search-clientcompatirissettingssearch).
21. **Sophisticated Backpacks recipes in EMI** — client-side; `client/compat/SbEmiFill` + two string-targeted EMI
    mixins make EMI's "+" fill Sophisticated Core's split crafting recipes. See
    [Sophisticated recipes in EMI](#sophisticated-recipes-in-emi-clientcompatsbemifill).
22. **TrashSlot on slotless menus** — client-side; `mixin/TrashSlotGuiHandlerMixin` stops TrashSlot drawing its slot on
    container screens whose menu has no slots (Waystones' settings screens), which crashed GuiTween's render hook.
23. **Search box assist** — client-side; `mixin/EditBoxSearchAssistMixin` + `client/search/*` give every search box
    grey autocomplete and a "Did you mean" line. See [Search box assist](#search-box-assist-clientsearch-mixineditboxsearchassistmixin).
24. **Mob & Chunk Loader** — server-side; the craftable anchor dummy plus `/foxstweaks anchor add|remove|list|tp`
    force-load a chunk square, run random ticks (Dreadful Dirt, crops), activate spawners and stop mobs despawning.
    It can also carry its owner's Apotheosis world tier. See
    [Mob & Chunk Loader and Apotheosis tiers](#mob--chunk-loader-and-apotheosis-tiers-anchor-and-anchortier).
25. **Holograms** — the craftable *Hologram Wand* projects a block into the world as decoration: no collision, no
    light, no ticking. The editor exposes the block's own blockstate properties, so every variant is reachable,
    plus scale/rotation/spin/hover/glow/render distance. See
    [Holograms](#holograms-hologram-holograms-hologramitem-hologramclient-hologramrenderer-hologramscreen).
26. **Music-reactive holograms** — client-side analysis, server-capped data; `client/audio/*` + `hologram/HologramAudioMod`.
    Hologram values follow jukebox, music or (opt-in) system audio levels. See
    [Music-reactive holograms](#music-reactive-holograms-clientaudio-hologramhologramaudiomod).
27. **3D model holograms** - `HologramLayer.Kind.MODEL`; `.glb`/`.gltf`/`.bbmodel` go through the asset pipeline as
    mode 3 and are stored as one canonical FTM1 blob. See [3D model layers](#3d-model-layers-hologrammodel-hologrammodelsettings-clienthologrammodels).

**Every parent mod is optional.** The mod must load and behave correctly with either, both, or
neither installed. This is the single most important invariant in the codebase — see
[Optional dependencies](#optional-dependencies).

## Building

```bat
build.bat build
```

Do **not** call `gradlew` directly unless you know a JDK 21 is on `PATH`. `build.bat` points
`JAVA_HOME` at the portable JDK in `toolchain/` and then delegates to `gradlew`.

Two environment facts that will otherwise waste your time:

- **This machine has no system JDK.** The `java` on `PATH` is an Adoptium **JRE 17**, and the launcher
  ships JREs too - none of them is a JDK. A JRE has no `javax.tools` compiler, so NeoForm cannot
  recompile Minecraft and the build dies with a confusing `JavaCompiler is null` NPE (and even a 17
  tools jar is the wrong version for a 21 build). Hence the portable JDK 21 in `toolchain/`, which
  `build.bat` picks up.
- **The Maven hosts publish AAAA records but IPv6 does not route here.** `gradle.properties` sets
  `systemProp.java.net.preferIPv4Stack=true`; without it dependency resolution stalls until it
  times out and falls back.

`toolchain/` is gitignored and absent from a fresh clone - see [Local setup](#local-setup). `libs/` is
gone for good: everything it held is now a normal Gradle dependency.

## Local setup

**Compiling needs nothing manual.** Every `compileOnly` dependency in `build.gradle` (Relics, Curios,
OctoLib, the Apotheosis family, Architectury, Point Blank, AE2, EMI, Iris, Ars Nouveau, Botany Pots,
HNN, IPN/libIPN, FTB Library) resolves from a real Maven repository: Shadows-of-Fire's own maven for
the Apotheosis family and HNN, TheIllusiveC4's own maven for Curios, Architectury's own maven, FTB's
for FTB Library, bawnorton's for MixinSquared, and Modrinth's Maven mirror
(`https://api.modrinth.com/maven`, group `maven.modrinth`, artifact = Modrinth slug, version = the
Modrinth *version id*, not the version number) for the rest, which have no maven of their own.
`build.bat build` downloads all of it on first run, same as any other Gradle dependency. NeoForge mod
jars for 1.21.1 ship in Mojang mappings, which is why a production jar works as a compile dependency
with no remapping.

There used to be a `libs/` folder of manually-downloaded jars for this. It is gone: everything it
held is now a normal Gradle dependency. Do not reintroduce it without a good reason - the whole point
was to stop hand-managing these.

**The dev client run still needs manual jars, and there is no way around that.** FML's mod discovery
(mixins, `neoforge.mods.toml`, all of it) only scans `run/mods/` and jar-in-jar - never the general
runtime classpath. ModDevGradle's `additionalRuntimeClasspath` was tried here for exactly this
purpose and does not work: it puts a jar's classes on the classpath (useful for a plain non-mod
library) but FML's `ModDiscoverer` still never sees it, so the "mod" never appears in the mod list,
and anything that depends on it (Relics needs Curios; `apothic_compats` needs Apotheosis and Placebo)
fails to load with "Missing or unsupported mandatory dependencies". Drop the real jars into
`run/mods/` by hand before running `build.bat runClient`:

- `relics-*.jar`, `curios-*.jar`, `OctoLib-*.jar` — for the Relics half
- `Apotheosis-*.jar`, `Placebo-*.jar`, `ApothicAttributes-*.jar` — for the Apotheosis half
- `architectury-*.jar` — OctoLib's own dependency

The versions in `gradle.properties` (`relics_version`, `curios_version`, etc.) tell you exactly what
to fetch - the same builds Gradle resolves for compiling.

Every other integration works the same way: TACZ, Shrink, EMI, Iris, Kaleidoscope Grilling and the rest
are reached only when their mod is loaded, so a general client needs their jars in `run/mods/` and each
dev check needs them in its own `run-*/mods` - the section for that feature says which.

**Headless check of the data patches:** `build.bat runServer` (game dir `run-server/`, gitignored)
loads datapacks without a window. Put the mods under test in `run-server/mods/` and read the log for
`Apothic Ascension: extended N ...` / `Ragnarok x Ancient Reforging: ...` and for any affix that
failed to parse. Placebo/Apotheosis log parse failures as `Failed to load ...` lines.

`apothic_compats` is not required to compile or run this mod, but drop it into `run/mods/` too before
testing Relics affixes specifically: without it, Relics' items resolve to no `LootCategory` at all
and Apotheosis refuses to affix or socket any of them (see the testhotbar diagnostic below and the
[known bug](#known-upstream-bugs-not-ours--do-not-fix-them-here) about its load order). It has no
Modrinth/CurseForge listing - build it from [ianm1647/apothic-compats](https://github.com/ianm1647/apothic-compats)
(branch `1.21.1`), or pull the `Package` artifact off its latest successful GitHub Actions run.

## Agent harnesses

This file is the single source of truth for every harness used here - Claude Code, Codex, OpenCode and
T3 Code all read it. `CLAUDE.md` is a stub that imports it (`@AGENTS.md`) and repeats the three traps
most likely to bite; don't grow a second copy of the rules there, add them here.

Machine-wide rules (disk hygiene, `rig`, delegation to cheaper models, output style) live in
`~/.claude/CLAUDE.md`; Codex reads a synced copy at `~/.codex/AGENTS.md`. Read it before touching game
runs, other machines or subagents. Whoever edits it runs
`pwsh -NoProfile -File X:\Dev\tools\sync-agent-rules.ps1` right after.

### Where a build and a run may happen

- **Headless work stays on FOX**: `build.bat build`, `build.bat runServer`, `git`, reading reports.
- **Anything that opens the game window runs on rig (192.168.50.3), never on FOX.** A Minecraft client
  grabs keyboard and mouse even while unfocused, so a local dev client hijacks the machine the user is
  working on. That covers `runClient`, `runClientLite`, and every `run*Shot` / `run*Check` task.

  ```bat
  pwsh -NoProfile -File X:\Dev\tools\run-on-rig.ps1 -Project X:\Dev\Minecraft\foxs-tweaks -Command "build.bat runClientLite"
  pwsh -NoProfile -File X:\Dev\tools\run-on-rig.ps1 -Project X:\Dev\Minecraft\foxs-tweaks -Command "build.bat runToggleShot" -Wait
  pwsh -NoProfile -File X:\Dev\tools\run-on-rig.ps1 -Project X:\Dev\Minecraft\foxs-tweaks -Stop
  ```

  Always `-NoProfile -File`: without it pwsh can hang for hours loading the user profile and never reach rig.
  `-Wait` is for checks that quit by themselves (the `run*Shot`/`run*Check` tasks); an interactive
  client needs the rig-windows MCP tools or the tunnel instead.

  The script mirrors this folder to `G:\rig-run\foxs-tweaks\` (FOX is the source of truth; `build`,
  `.gradle`, `.git` are skipped), runs the command in rig's desktop session and streams
  `G:\rig-run\foxs-tweaks\_rig-run.log`. Gradle's reports and the `run-*/foxstweaks-*-check.txt` files
  therefore appear under `G:\rig-run\foxs-tweaks\run-*` - read them from `\\Rig\g\rig-run\foxs-tweaks\`.
  `run-*/mods` copies with the source, so hand-placed jars travel. `-Stop` kills the game and its
  tunnels; always call it when the check is done. `-Tunnel 47861:47860` forwards rig's mc-mcp if an
  agent needs to drive the window (screenshot, click).
- **Keep dev clients muted**: master volume 0 (`soundCategory_master:0.0` in the run dir's
  `options.txt`), unmute only when the change under test makes sound.
- One dev client per game directory at a time - Gradle and the world lock the dir, and two of the same
  check overwrite each other's report.
- New in-game checks copy the pattern in `.claude/skills/neoforge-ingame-selftest/SKILL.md` (dev-only
  class behind a system property, own `run-*/` dir, PASS/FAIL text report, screenshots, quits at the
  end) and get their own run config + section here.

### Delegation

A child agent sees only its brief, so a brief must be self-contained: the absolute project path, the
files to read (including this file and any skill by path, since non-Claude harnesses cannot load Claude
skills), what to change, how to verify, and "build and test in the foreground, never end a turn while a
build runs, commit on your branch, no push". Review every child's report before merging or reporting the
work as done. The model/delegation table itself is in `~/.claude/CLAUDE.md`.

## Architecture

### Optional dependencies

FML loads `@EventBusSubscriber` classes **whether or not their dependencies exist**. Annotating a
class that imports `IRelicItem` would therefore hard-crash any pack without Relics.

Consequently:

- **Anything importing a Relics class must not use `@EventBusSubscriber`.** It is registered by hand
  from `FoxsTweaks`'s constructor, behind `ModList.get().isLoaded("relics")`, via
  `RelicsIntegration` (and `RelicsClientIntegration` for the client-only screen classes, kept
  separate so they only load on a client that has Relics).
- **Apotheosis code needs no such care**, because those handlers hang off vanilla events and only
  reach Apotheosis classes inside method bodies, which classload lazily. They are still guarded by a
  `ModList.isLoaded("apotheosis")` check before the first call.
- **All Apotheosis references are confined to a handful of `compat`/`client/compat` classes** —
  `client/compat/ApothicTooltip`, `compat/ApothicTestGear`, `compat/ApothicRarityLookup`,
  `compat/ApothicAffixToggle` and `client/compat/ApothicRows`. Keep it that way; touching an
  Apotheosis class from anywhere that gets loaded eagerly will throw `NoClassDefFoundError` on a pack
  without it. A new one is fine — the rule is "no eager class touches an Apotheosis type", not "these
  exact files".

When changing any of this, verify by launching a dev client with the relevant mods **removed** from
`run/mods/`. That is the only way to catch a violation; it compiles fine either way.

### Access transformer

`src/main/resources/META-INF/accesstransformer.cfg` widens `Screen#addRenderableWidget`, which is
`protected` and so unreachable when adding a widget to another mod's screen.

It widens the method on **both** `Screen` and `CreateWorldScreen`. `CreateWorldScreen` is the only
class in the game that overrides it, and Java forbids an override being less visible than what it
overrides — widening only the parent makes *Minecraft itself* fail to recompile with
"attempting to assign weaker access privileges". Both lines are required together.

### The affix tooltip panel

`client/AffixTooltipHandler` hooks `RenderTooltipEvent.Pre` and reproduces vanilla's own
measurement from `GuiGraphics#renderTooltipInternal` — including the `-2` height quirk when there is
exactly one component — then calls the very positioner the game is about to use. This yields the
finished tooltip's exact geometry so the panel can sit flush against its edge.

NeoForge 1.21.1 has **no** `RenderTooltipEvent.Post`; only `Pre`, `Color` and `GatherComponents`
exist, and none of the others know the final position. Do not go looking for one.

Two traps:

- **Re-entrancy.** Rendering a tooltip fires the same event again. The `rendering` flag is what stops
  the first hover recursing until the stack overflows. Do not remove it.
- **Use `AttributeUtil.applyModifierTooltips`, not `addAttributeTooltips`.** The latter also posts
  `AddAttributeTooltipsEvent`, which is where Apotheosis injects its gem-socket marker line — that
  duplicates the socket rows the panel adds itself. The chosen path still posts
  `GatherSkippedAttributeTooltipsEvent`, so affix-granted attributes stay hidden in the attribute
  block and are described by the affix lines instead.

The panel deliberately mirrors Apotheosis' own ordering (name → affixes → durability → attributes →
sockets) and is built with Apotheosis' own helpers, so it stays in step if their formatting changes.
It also carries its own caption line (`foxstweaks.affix_panel.caption`) right under the name, because
the panel uses the same dark tooltip styling as the main tooltip and can end up beside or touching it
— without a caption there is nothing to tell them apart. `FRAME` in `AffixTooltipHandler` carries
extra slack beyond vanilla's own padding for the same reason: `mainWidth`/`mainHeight` only measure
the plain content box, and a rarity-tier item's decorative Apotheosis border is drawn outside it, so
the plain-box geometry alone can still let that border visually touch the panel.

### Toggling enchantments and affixes (`enchant/`, `client/ToggleScreen`)

`key.foxstweaks.toggle_affixes` (unbound by default; the pack's only new key) opens `ToggleScreen`: the
item's own tooltip look (vanilla tooltip box via `TooltipRenderUtil`) made clickable. Top to bottom: a
strip of the six equipment slots (HEAD..FEET, MAINHAND, OFFHAND, each with an amber count of switched-off
entries), the item name, enchantments two per line, affixes one per line, gem sockets (read-only), a hint.
The box sizes itself to its content; when the item is taller than the screen only the affix block scrolls
(mouse wheel). The key also works with any container screen open (`ToggleKeyHandler#onScreenKey`): the
box opens over it, the parent dims behind, and closing returns to it (`ToggleScreen.parent`).

Rows come from data, never from reading tooltip text back: `EnchantToggle.list` and, for affixes,
`client/compat/ApothicRows` (label = `ApothicTooltip#tooltipBody`, the same live description the tooltip
shows). `ApothicRows` is the only door from the screen into Apotheosis (also `gems()`); it is only reached
after `ModList.isLoaded("apotheosis")`.

**Enchantments** (`enchant/EnchantToggle`): disabling really removes the enchantment from the item's
enchantment component (`EnchantmentHelper.updateEnchantments`) - a cosmetic flag would do nothing for
effects that run in vanilla or other mods - and stores id + level in `FoxsTweaksComponents.DISABLED_ENCHANTS`
so re-enabling restores the exact level. Re-enabling is refused when
`EnchantmentHelper.isEnchantmentCompatible` fails against the live set (Silk Touch + Fortune); the screen
shows that row locked with a red reason. `Config.ENCHANT_TOGGLE_BLACKLIST` (default: curses) is never toggleable.
Gated by `enchantToggle`.

**Affixes** (`compat/ApothicAffixToggle`): most affixes are Apotheosis' own behaviour and never pass through
this mod, so the only way to stop one is to remove it from Apotheosis' `ItemAffixes` component
(`Apoth.Components.AFFIXES`) via the public `ItemAffixes.Builder`/`AffixHelper.setAffixes` API, no mixin.
The removed id and level go in `FoxsTweaksComponents.DISABLED_AFFIXES` (our component, no Apotheosis type) so
re-enabling restores the level instead of re-rolling. Gated by `affixToggleGui`.

**Network**: one payload for both, `ToggleEntryPayload(slot, affix, id, enable)`, registered by
`ToggleNetwork` on an optional registrar (version "3"). The server acts on `player.getItemBySlot(slot)`;
the enchant branch needs `enchantToggle`, the affix branch needs `affixToggleGui` and Apotheosis loaded.
The screen also changes the client stack immediately so the row flips this frame.

**Tooltips**: a disabled entry is invisible once removed, so the normal item tooltip would hide it.
`EnchantTooltip` (enchants) and `AffixTooltipHandler` -> `ApothicTooltip#insertDisabledLines` (affixes,
`RenderTooltipEvent.GatherComponents`, priority `LOWEST`) put a red X line back where the entry used to be.
For affixes that means building the tooltip of a copy with the disabled affixes restored (the "ghost",
cached on `ItemStack.hashItemAndComponents`) and inserting after the nearest line above that also exists in
the real tooltip. The X and check are bitmap glyphs in the `foxstweaks:icons` font
(`ToggleIcons`; PUA U+E000 / U+E001): the default font has no emoji. The icon component must be an unstyled
root with siblings, otherwise appended text inherits the icon font and renders as boxes.

`AffixInstance#getName(true)` is the plain name ("Infernal"); `getName(false)` is the possessive fragment
Apotheosis stitches into the item title ("of Great Fortune"). Never match tooltip text against either - the
title contains them. Skip index 0 (the title) when matching ghost lines.

**Dev check**: `build.bat runToggleShot` (game dir `run-toggle/`; put Apotheosis, ApothicAttributes and
Placebo in `run-toggle/mods` to cover affixes and gems). It builds a flat creative world, an endgame sword, an
offhand pickaxe and a helmet, clicks real rows and tabs, checks the result on the server, screenshots GUI
scales 2-4 and the over-inventory case, and writes `run-toggle/foxstweaks-toggle-check.txt`.
`ToggleScreen.forcedMouse`, `reveal`, `centerOfRow` and `centerOfTab` exist only for it (a real cursor cannot
be moved on an unfocused window).

### Recovering the hovered item on a broken screen

`AffixTooltipHandler#recoverHoveredStack` falls back to the generic `AbstractContainerScreen`
`hoveredSlot` field (widened by the access transformer) when `RenderTooltipEvent.Pre#getItemStack()`
comes back empty. This works around Curios' `CuriosScreen#renderTooltip`, which calls the
`GuiGraphics#renderTooltip` overload that takes no `ItemStack` instead of the one vanilla's own
`AbstractContainerScreen` uses — see [TheIllusiveC4/Curios#536](https://github.com/TheIllusiveC4/Curios/issues/536)
under [Known upstream bugs](#known-upstream-bugs-not-ours--do-not-fix-them-here) below.

It reads a vanilla field rather than a Curios type, so it fixes any screen with the same bug and adds
no reference to Curios at all — no optional-dependency concerns here, unlike the Relics integration.
It is gated behind `Config.CURIOS_TOOLTIP_WORKAROUND` (`curiosTooltipWorkaround`, default on) so it
can be turned off once Curios ships [#625](https://github.com/TheIllusiveC4/Curios/pull/625) rather
than the two fixes interacting. It also only ever restores **our own** panel — Apotheosis' own
tooltip content reads the same broken event from Apotheosis' own code, which this cannot reach.

### Where the puzzle answer comes from

Nothing is hardcoded per relic. A relic declares its constellation in its ability template, which is
part of the item definition and therefore present on the client too — this is how Relics' own hint
button knows which line to reveal. `relics/client/ResearchSolver` reads it through the public
`IRelicItem` API:

```text
IRelicItem -> RelicData -> AbilitiesData -> AbilityData -> AbilityTemplate -> ResearchTemplate.getLinks()
```

Because it goes through the interface, relics added by *other* addons work with no extra data.

### Rarity compat patches (`apotheosis/RarityPatcher`)

An Apotheosis affix exists only at rarities it lists a value for. Mods that add rarities (Apothic
Ascension: 13 above Mythic; Ancient Reforging and Ragnarok: one `ancient` each) are therefore invisible
to every affix pack written before them. `RarityPatcher` fixes the JSON in memory:

- **Ragnarok -> Ancient Reforging.** Wherever `apotheosis_modern_ragnarok:ancient` appears as a rarity
  (map key or list entry) in an affix, `ancientreforging:ancient` is added with a copy of the same
  value. Without this, a gun reforged at the Ancient Reforging table matches no affix at all. Other
  AR-companion packs (`apothic_compats`, `wolfsancientiron`) ship separate `.../ancient/` affix files
  instead; copying in place was chosen here because it needs no extra files and cannot double weights.
- **Any pack -> Apothic Ascension.** Ascension hand-tunes only the vanilla affixes and deliberately
  does no "automatic foreign json mutation" (its own `apothic_ascension-compatibility.json`). For every
  other rarity map keyed at `apotheosis:mythic` (or Ragnarok's `ancient`, if present) we extrapolate
  13 new entries. `CURVE` is the fraction of the way from the top tier to Ascension's endpoint - it was
  read back out of Ascension's own data and is identical across its affixes. The endpoint multipliers
  are Ascension's *median* per attribute operation; they are heuristics, not Ascension's numbers, and
  the per-key rules (cooldowns shrink, durations grow, amplifiers/levels add, proportions cap at 95%)
  are ours. Unrecognised value shapes are copied unscaled, which still makes the affix available.

It runs from `mixin/DynamicRegistryMixin`, a `HEAD` injection on Placebo's
`DynamicRegistry#apply(Map, ResourceManager, ProfilerFiller)`: that is the one point where a
registry's JSON is fully merged (every mod's and datapack's files) but not yet decoded, and Placebo
offers no event there. The config is `required: false` and the target
only exists with Placebo, so a pack without it is unaffected.

Both patches are on by default (`ascensionCompat`, `ragnarokAncientReforging`). They change server
data only, which Placebo syncs to clients, so clients do not need this mod.

Not handled, deliberately: `rarity_override` files for third-party categories (Ascension's default
rarity rules apply there instead of the pack's own per-tier counts), boss stats (Ascension has its own
fallback), and `sort_index` collisions - Ascension `legendary`, Ancient Reforging `ancient` and
Ragnarok `ancient` all sit at 800.

### Ancient Reforging tier for any affix pack (`ancientReforgingCompat`)

`RarityPatcher#extend` adds two things from an affix's top tier: Ascension's 13 rarities and, separately,
`ancientreforging:ancient`. The latter sits at sort index 800, level with Ascension `legendary`, so it gets
`CURVE[0]`. It is skipped when the affix already lists it (Ragnarok's, via the alias). Found through a real
failure: Apothic Point Blank affixes stop at `apotheosis:mythic`, and the server log showed
`Failed to execute AffixLootRule (no affixes available) ...ancientreforging:ancient... apothic_pointblank:gun`.

A range with `min == max` and no step (Apothic PB's `{min: 3, max: 3}`) is one level; scaling widens it, and
`scaleRange` must then set the step to exactly the new width or Placebo rejects the affix (`Failed to
interpolate step function bounds`). Scaled ranges are only safe if `(max-min)/step` is whole to ~1e-4.

**Regression check** (no test framework here): run `RarityPatcher.alias` + `extend` over every
`*/affixes/*.json` in every jar of a server's `mods/` and apply Placebo's rule to each range. On the TNP pack
that was 1054 affixes, 447 patched, 0 invalid ranges. Worth redoing when the scaling code changes.

### Gun damage scaling (`apotheosis/GunDamageHandler`)

A gun's damage is fixed by its gun pack (TACZ) or its own base stat (Point Blank) and never grows with
rarity; Ascension's mobs are tuned for melee gear. `GunDamageHandler` multiplies
`LivingIncomingDamageEvent` amounts when the source is a player and either:

- the damage type is in `tacz:bullets` (TACZ's bullets plus Ragnarok's fire/ice) or Ragnarok's
  `bugfix/armor_piercing_parts` (TACZ splits one hit into normal + armor-ignoring events; miss the
  second and half the hit is unscaled - the tag also holds a mob type, hence the player check); or
- Point Blank is loaded, the target isn't the shooter, and the shooter's main hand holds a `GunItem`.
  Point Blank doesn't tag its damage type - `HurtingItem#hurtEntity` deals plain
  `player.damageSources().playerAttack(player)` - so it is recognised the same way Apothic-PB's own
  `GunDamageHandler` does it (verified by reading that mod's source): gate on the main-hand item, and
  exclude the shooter as target so an explosive launcher's self-splash isn't scaled as if it were a hit
  landed on someone else. The Point Blank type is confined to `pointblank/compat/PointBlankGunLookup`.

The multiplier uses the *held* stack's Ascension rarity and `RarityPatcher.CURVE`, so 1x below Legendary
and `gunDamageMaxMultiplier` at Apotheotic. The Apotheosis type is confined to `compat/ApothicRarityLookup`.
The default max (20x) is a guess, not measured against Ascension's mobs. It never touches anything a
tooltip reads - only the amount at the moment `LivingIncomingDamageEvent` fires.

### Nearby-storage crafting (`storage/NearbyStorage`, `storage/compat/Ae2NearbyStorage`)

Shared by the Point Blank printer and TACZ's gunsmith table (below) - a scan-a-box-and-pull utility,
the same idea as Ars Nouveau's scribe's table: chests, barrels, any block with an item inventory, plus
AE2 through `Ae2NearbyStorage` (see the reusable AE2 section further down). It knows nothing about
either gun mod; each feature's own mixins own that dependency and only ever touch `NearbyStorage`'s
generic `Source`/`count`/`take`/`tally` API.

A scan is centred on whatever `begin(level, pos, range)` was last called with; callers bracket the
whole span of a craft with `begin`/`end`, `active()` tells other code a scan is in scope, and each
feature gates calling `begin` behind its own config toggle (`NearbyStorage` has no toggle of its own).
`count`/`take` read that bracketed state; `tally(level, pos, range, wanted)` takes its own explicit
`level`/`pos`/`range` instead, since it is also called with no bracket in scope (from
`PrinterNetwork#tick`, and from `NearbyBackedItemHandler#wrap` below).

Prefers ME storage over the item handler at the same block (no double count), and sums plain
inventories but takes only the *best* ME source, so it can under-count split stock across two
configured interfaces, never over-count.

### Point Blank printer storage (`mixin/PointBlankInventoryUtilsMixin`, `mixin/PrinterBlockEntityMixin`)

Point Blank (closed source, All Rights Reserved) crafts server-side in `PrinterBlockEntity#createCraftingItem`
when the print finishes: `PointBlankRecipe#canBeCrafted` -> `InventoryUtils#hasIngredient`, then
`removeIngredients` -> `InventoryUtils#removeItem`. Both helpers take only a `Player` and read
`getInventory().items`. `PrinterBlockEntityMixin` marks the span of `createCraftingItem`
(`NearbyStorage.begin` with the printer's position); `PointBlankInventoryUtilsMixin` then tops up
`hasIngredient` and drains the shortfall in `removeItem` from whatever `NearbyStorage` finds within
range. Outside that span the client is a separate case, below. Both mixins target by string; the
compile-only Point Blank dependency (`pointblank_version`, a Modrinth version id) is only for the
`PointBlankIngredient` type. Written against Point Blank 2.2.0 - re-check the method names on update.

**The Craft button is enabled client-side.** `CraftingScreen` sets `craftButton.active` from
`CraftingContainerMenu#updateIngredientSlots`, which calls `hasIngredient` with the *client* player - so
without more, the button stays disabled and a server-side pull is never reached. The client cannot see the
storage, so `PrinterBlockEntityMixin` hooks `serverTick()V` and `PrinterNetwork#tick` sends a
`NearbyItemsPayload` (item -> count, for every item any Point Blank recipe asks for) every 10 ticks to
players within 8 blocks whose `containerMenu` is Point Blank's `CraftingContainerMenu`. `NearbyCounts`
keeps it for 3 s, and the client branch of the `hasIngredient` mixin counts it. The payload is `optional()`
and only registered when `pointblank` is loaded; `sendToPlayer` is skipped for a client without the channel.

Oddity found, not ours and not touched: `PointBlankRecipe#canBeCrafted` is `anyMatch(hasIngredient)`,
which reads as "craftable if any one ingredient is present".

### TACZ gunsmith table storage (`mixin/GunSmithTable{BlockEntity,Menu,Screen}Mixin`, `tacz/*`)

Every gun pack's own workbench (Applied Armorer, Ars Armorer, ...) is the *same* TACZ block entity
(`GunSmithTableBlockEntity`, type `tacz:workbench_a/b/c`), told apart only by a data-driven `BlockId`
recipe filter - so one patch on TACZ's own classes covers every pack's workbench, with no per-pack code.

TACZ's crafting is architecturally different from Point Blank's, and both differences shaped the
approach:

- **No block position reaches the craft.** `GunSmithTableMenu#doCraft(ResourceLocation, Player)` gets
  only the player - unlike Point Blank's printer, which is itself a block entity. So
  `GunSmithTableBlockEntityMixin` records which table each player last opened
  (`GunSmithTableBlockEntity#createMenu`, the only place `this.getBlockPos()` and the opening `Player`
  are both in scope) in `tacz/WorkbenchOpenTracker`, a plain per-player `BlockPos` map with no explicit
  cleanup (chisel: a stale entry can only be read via a craft packet with no menu open, which TACZ's own
  container-id check already rejects). `GunSmithTableMenuMixin#doCraft`'s `@Inject(HEAD)` reads it back
  to call `NearbyStorage.begin`.
- **One capability call, not two split helpers.** `doCraft` fetches
  `player.getCapability(Capabilities.ItemHandler.ENTITY, null)` *once*, then hands that single
  `IItemHandler` into a lambda (`lambda$doCraft$3`) that does all of TACZ's own ingredient matching and
  extraction by walking its slots directly by index (into an `Int2IntArrayMap`, first read-only to
  check sufficiency, then a real `extractItem` pass) - no `hasIngredient`/`removeItem`-style pair to
  hook individually, the way Point Blank's `InventoryUtils` has. So instead of hooking consumption,
  `GunSmithTableMenuMixin` `@Redirect`s that one `getCapability` call to return
  `tacz/NearbyBackedItemHandler#wrap` - the real handler plus one extra read-only virtual slot per
  distinct item type nearby (`NearbyStorage#tally`). TACZ's own logic then walks those slots completely
  unmodified: real slots first, then the extras. This is why nothing in `NearbyBackedItemHandler` or
  `WorkbenchOpenTracker` imports a TACZ type at all - the mixins are the only place doing so, and only
  by string target, so a pack without TACZ never applies them.

`begin`/`end` still bracket the *whole* `doCraft` call (not just the redirect), because extraction
happens later, inside the lambda captured from whichever handler the redirect returned.

**No Craft-button payload needed, unlike Point Blank** - `GunSmithTableScreen#addCraftButton` never
disables the button on ingredient sufficiency; clicking it always sends the packet and the server alone
decides pass/fail. But `getPlayerIngredientCount` (client-side) drives the ingredient-count *text*
rendered in the UI, reading only the local player's own `Inventory#items`. Left alone, that text would
say "not enough" for something the craft above would actually accept - easy to miss, since a player has
no reason to click Craft on something the game is telling them they can't afford.

Fixed the same way as the server side: `GunSmithTableScreenMixin` (client) `@Redirect`s the single
`Inventory.items` field read inside `getPlayerIngredientCount` to a list with nearby stock appended
(`NearbyCounts#asStacks`, one `ItemStack` per item type) - so TACZ's own unmodified
`Ingredient#test`/`ItemStack#getCount` counting logic adds nearby storage in as if it were more
inventory. Zero TACZ types touched here either, same reasoning as the server-side redirect.
`updateIngredientCount()` only ever calls `getPlayerIngredientCount` internally, so this is the one
place that needs it.

`NearbyCounts`/`NearbyItemsPayload` are the same classes the printer already used for its Craft-button
problem, moved out of `pointblank/` into `storage/` since they were never Point-Blank-specific; the
network channel id changed from `printer_nearby_items` to the now-accurate `nearby_items`.
`tacz/WorkbenchNetwork` sends the report - TACZ's table has no block-entity tick to piggyback on the
way the printer did, so this rides a `PlayerTickEvent.Post` listener instead (`@EventBusSubscriber`,
safe unconditionally since it touches no TACZ type either), filtered to players looking at a
`GunSmithTableMenu`.

Toggle: `taczWorkbenchNearbyStorage` / `taczWorkbenchStorageRange`. Written against TACZ
1.1.8-hotfix-r6 (jar read directly, no compile dependency needed - no TACZ Java type is ever referenced
outside the three mixins' target strings) - re-check the method names and the redirected call/field
descriptors on update. **Untested in-game as of writing** - built from the jar via `javap`, not observed
working.

### Reading AE2 storage from a patch (AE2 interface capability) - reusable

Verified against AE2 19.2.17 by reading its jar (`appeng.init.InitCapabilityProviders`,
`appeng.helpers.InterfaceLogic`). Reused by both the Point Blank and TACZ storage patches above; reuse
it again for any future patch that wants "items from the player's network".

- **An ME Interface has two capability views, and they differ.**
  - `Capabilities.ItemHandler.BLOCK` is only the interface's **9 stock slots** (AE2's
    `registerGenericAdapters` wraps `AECapabilities.GENERIC_INTERNAL_INV` = `InterfaceLogic#getStorage`).
    Empty on an unconfigured interface. A generic "scan for item handlers" loop - which is all Ars
    Nouveau's scribe's table does; it has **no** AE2 code - therefore sees stock slots, never the network.
  - `AECapabilities.ME_STORAGE` is `InterfaceLogic#getInventory`: the **whole network** when the interface
    has no config set (`hasConfig` false), or its local stock when it has one. Read this one.
- **Query it like any block capability:** `level.getCapability(AECapabilities.ME_STORAGE, pos, null)`; a part
  on a cable bus only answers per side, so fall back to the six `Direction`s. It is marked proxyable.
- **Counting/taking:** `MEStorage#getAvailableStacks()` (a `KeyCounter`; keys are `AEKey`, items are
  `AEItemKey`, `toStack()` for matching), `MEStorage#extract(key, amount, Actionable.MODULATE,
  IActionSource.empty())`. `Actionable.SIMULATE` for a dry run.
- **Never sum ME sources.** Every unconfigured interface on one network shows the entire network, so adding
  them counts it N times. Take the best one, or dedupe by grid.
- **Never read a block as both** `ME_STORAGE` and `ItemHandler` - an interface's stock slots are part of its
  ME view, so both would double count.
- **Addons need nothing extra** if they build on AE2's `InterfaceLogic` (ExtendedAE, Advanced AE and the
  like expose the same capability); not verified per addon.
- **Optional dependency rules apply:** keep `appeng.*` imports in one class that is only reached behind
  `ModList.get().isLoaded("ae2")` (`Ae2NearbyStorage` is the model), and expose it through an AE2-free
  interface (`NearbyStorage.Source`). Compile-only via `ae2_version` (a Modrinth version id).
- **Verified in-game** for the Point Blank printer, against an ExtendedAE ME Extended Interface with
  nothing configured. The TACZ gunsmith table path reuses the same `NearbyStorage`/`Ae2NearbyStorage`
  code and is expected to behave the same, but has not itself been separately observed working.

### TACZ ammo box reset (`tacz/AmmoBoxResetHandler`)

TACZ's ammo box (`IAmmoBox`/`AmmoBoxItemDataAccessor`, a real public TACZ API - unlike everything else
TACZ-related in this mod, which pokes private classes) locks to whichever ammo `setAmmoId` was last
called with (right-clicking it on a stack of that ammo) and offers no way to change it afterwards.
`AmmoBoxItem` has no `use()` override, so right-clicking it in the air currently does nothing at all -
free real estate. Sneak + right-click claims that gesture and removes the `AmmoId`/`AmmoCount` NBT keys
from the stack's `CUSTOM_DATA` component, which is exactly what an unset box looks like
(`getAmmoId`'s default falls back to `DefaultAssets.EMPTY_AMMO_ID` when the tag is absent).

Deliberately **not** using the real `IAmmoBox` API or a compile dependency: TACZ has no confirmed
Modrinth NeoForge/1.21.1 coordinate to point `compileOnly` at (its Modrinth listing only has Forge/1.20.x
builds), so this reaches TACZ by the ammo box item's runtime class name
(`com.tacz.guns.item.AmmoBoxItem`) and hardcodes the two NBT tag key strings (`AmmoId`, `AmmoCount`,
verified via `javap` against TACZ 1.1.8-hotfix-r6) instead - plain vanilla `DataComponents`/`CustomData`,
no TACZ type at compile time or runtime. No mixin needed either, since nothing is intercepted - this is
a normal event listener. Toggle: `ammoBoxReset`.

### EZActions icon picker (`mixin/IconPickerScreenMixin`, `client/compat/IconNameCache`)

EZActions' `IconPickerScreen` already caches its item index in statics for the session, but builds it
at 320 items/tick and names at 64/tick (60k items on a 1000-mod pack = 10s + 30s+), rebuilt every
launch. The mixin lifts those budgets (`@ModifyVariable` on `pumpVanillaBuild` /
`processHydrationBudget`), short-circuits `nextBackgroundHydrationEntry` (it rescans the full list
twice a frame once everything is hydrated), and wraps `safeName(Item)` with `IconNameCache`, a disk
cache. Each name is trusted only while its item's namespace (mod id) is at the version it was saved
under. Language and resource packs are deliberately ignored (a language switch needs the file deleted).

It targets the class **by string** (`@Mixin(targets=...)`) and every injection names a private member
without a descriptor, so EZActions is not a compile dependency and an EZActions update that renames
things fails soft (the picker just goes back to being slow) — the mixin config is `required: false`.
It sits in the `client` section. Against EZActions 2.0.3.5; re-check the member names on update.
Toggle: `iconPickerCache`.

### GuideME hotkey tooltip (`mixin/GuideMeHotkeyMixin`)

GuideME's `OpenGuideHotkey.handleTooltip` keeps one global state (`previousItemId`, `guidebookPages`,
hold progress). It refreshes that state only for the first tooltip built after each client tick
(`newTick`) and reuses it for every other tooltip that tick. So when any other mod builds a tooltip for a
different item every tick, the hovered item's "Hold [G] to open guide" line flickers, or shows the other
item's guide (upstream: GuideME#81, closed without a fix). The mixin hides the line on a tooltip for an
untracked item unless that tooltip is the tick's first. When the first tooltip is for an item with no
guide page, it puts the previous state back, including the unused tick, so an item with no guide can't
take over the hovered item's line. Targets by string with `@Shadow`s on GuideME's private statics, so it
needs no GuideME compile dependency. Against GuideME 21.1.19; re-check the field names on update. Client
section. Toggle: `guidemeTooltipFix`.

### Sophisticated Backpacks render info cache (`mixin/BackpackRenderInfoMixin`)

`BackpackRenderInfo.fromPhysicalStack` builds a new render info from the stack on every call, deep-copying
the render tag and decoding each upgrade item through the `ItemStack` codec. The item model override
(`BackpackItemOverrideList.resolve`), `BackpackItemStackRenderer` and `BackpackLayerRenderer` all call it
per draw, so backpacks in the hotbar or on a back cost ~2.5% of frame time in a spark profile. All three
only read the result, so the mixin caches it by the `sophisticatedcore:render_info_tag` component
instance (identity, Guava `weakKeys`, bounded to 256): components are immutable and replaced whenever the
render data changes, so a changed backpack gets a new key. Stacks without that component (legacy data,
which `deserialize` migrates onto the stack) are not cached. The component type is looked up by id in
`BuiltInRegistries.DATA_COMPONENT_TYPE`, so there's no Sophisticated Core/Backpacks compile dependency.
Against Sophisticated Backpacks 3.26.3; re-check that `fromPhysicalStack` still has only read-only callers
on update. Client section. Toggle: `backpackRenderInfoCache`.

### Hats field lookup cache (`mixin/HatsFreshAnimationsLookupMixin`)

Hats 3.0.0's `FreshAnimationsHatCompat.readString(Object, String)` walks a model part's class chain with
`Class.getDeclaredField(name)` and treats `NoSuchFieldException` as "try the superclass". Without Fresh Animations
it misses on nearly every part, every draw (`ModelPart.translateAndRotate` hook `captureFreshHead`, and
`HatRenderLayer.attachToHead` > `findVisualHeadChain`), so a spark profile of an ATM10 client showed
`Throwable.fillInStackTrace` at ~8-13% total and Hats at ~8% inclusive. The mixin `HEAD`-injects `readString`
(by string, `remap=false`), caches the resolved `Field` per (class, name) in a `ClassValue`, caches misses as a
sentinel, and reads only the live value. Same result as the original (first String-typed declared field up the
chain, else null); any unexpected exception falls through to the original. Against Hats 3.0.0; re-check the
method name/signature on update. Client section. Toggle: `hatsFieldLookupCache`.

### Shrink hotkey (`shrink/ShrinkNetwork`, `client/ShrinkKeyHandler`)

Shrink (gigabit101, 2.0.1.47) shrinks only on use of the Shrinking Device from the hand (sneak-use = self,
right-click a mob = that mob). `TOGGLE_SHRINK` (unbound) sends `ShrinkPayload` (channel version "2", optional; the
client checks `hasChannel` first, so a server without Fox's Tweaks just ignores the key). Plain press: target = the
living entity under the crosshair (`mc.hitResult`); if that is a block or miss, `ShrinkKeyHandler.pickLiving` picks again
along the view, clipping only at blocks with collision (`ClipContext.Block.COLLIDER`), so a shrunk mob inside grass
or flowers is still found; else `SELF` (-1). Shift + press: `OPEN_MENU`; the server opens
Shrink's own menu as `use` does (`shrink/ShrinkMenu`, Architectury `MenuRegistry.openExtendedMenu`). That menu reads
and saves the **main-hand** item in three places, each redirected to `ShrinkNetwork.deviceFor` (main hand if it is
the device, else `findDevice`) by a `require = 0` `@WrapOperation` on `Player.getItemInHand`:
`ShrinkDeviceContainerMixin` (`<init>`, energy bar, both sides), `ShrinkScreenMixin` (client, `buildGui`, starting
scale; it casts the stack to the device, so without the mixin a non-device main hand crashes the client) and
`ShrinkDevicePacketMixin` (`PacketShrinkDevice.lambda$handle$0`, the save). If a Shrink update moves those reads,
check that Shift still works with a non-device main hand before shipping. The server finds the device by id
`shrink:shrinking_device` (main hand, offhand, any Curios slot via `shrink/ShrinkCurios` when Curios is loaded, then
inventory). Toggle: a non-self target must be alive and pass `canInteractWithEntity(target, 3.0)` (vanilla's interact slack) (a far or stale id
is dropped, never turned into self); then `ItemShrinkDevice#use`'s logic: `hasPower` (creative always true),
then add `createModifier(getScale(stack))` when the scale attribute has no modifiers at all (Shrink's own
`isEntityShrunk` test), else remove `SHRINKING_DEVICE_ID`, then `usePower`. Those members are looked up by
reflection once; if a Shrink update renames them the key logs once and does nothing. Toggle: `shrinkHotkey`.
The device has its own Curios slot, pure data like the Temporal Pouch one: `curios/slots/shrinking_device.json` +
`curios/entities/shrinking_device.json` (behind `neoforge:mod_loaded` on `shrink`), tag
`data/curios/tags/item/shrinking_device.json` (`required: false`), icon `textures/slot/shrinking_device.png` (a
silhouette of Shrink's item texture), name `curios.identifier.shrinking_device`. Compact Machines' Personal
Shrinking Device is a different item with its own `psd` slot; it does not change size.
Dev check: `build.bat runShrinkCheck` (game dir `run-shrink/`, needs shrink, architectury, polylib, curios in its
`mods/`) presses the real keys (shrink on, off, survival without power, no device, device in the Curios slot on/off,
looked-at NoAI pig on/off, the pig at -0.9 hidden in tall grass grown back, out-of-reach pig id ignored, Shift path (`ShrinkKeyHandler.press(mc, true)`) opening
Shrink's menu with a stick in the main hand and the device only in the Curios slot, the screen's `SCALE` read from
that device, Shrink's own `PacketShrinkDevice` saving to it, settings screen) and writes
`run-shrink/foxstweaks-shrink-check.txt` + screenshots of Shrink's menu and the settings screen.

### Kaleidoscope Grilling skewer cache (`mixin/GrillingDataManagerMixin`, `mixin/SkewerRecipesMixin`)

Grilling's `GeneratedSkewerModelMixin` hooks `ItemRenderer.getModel` `RETURN` and calls
`SkewerRecipes.usesGeneratedModel(stack)` for every item drawn. That calls the private `recipes()`, which rebuilds
the whole recipe list from `GrillingDataManager.skewersForDisplay()`, which merges three maps into a new `HashMap`
and `Map.copyOf`s it. A base full of Functional Storage drawers paid that hundreds of times a frame: 6.6% of frame
time with shaders, 13% without, in a spark profile (ATM10 + DrunkGamblers, Nov 2026). Three caches, all by
identity, so nothing is hashed:

- `skewersForDisplay()` (`GrillingDataManagerMixin`, `HEAD` cancel, re-implements the merge) while the `SKEWERS`
  (datapack, replaced whole in `apply`) and `SCRIPT_SKEWERS` (KubeJS/synced, replaced whole in
  `commitScriptThreadingRecipes`/`replaceSyncedSkewerRecipes`) fields hold the same instances. `BUILT_IN_SKEWERS`
  is a constant.
- `recipes()` (`SkewerRecipesMixin`, `@WrapMethod`) by the display map instance, read through
  `GrillingDataManagerInvoker` before the original runs, so the list is never older than its key.
- `usesGeneratedModel` per `Item` for as long as the recipe list is the same instance; the answer only depends
  on the item's id. Every other `SkewerRecipes` helper gets the cached list too.

Everything targets by string with `@Shadow`s typed `Map`/`List`, so Grilling is no compile dependency. Common
section (the helpers also run on the server). Against Kaleidoscope Grilling 1.1.1; on update re-check that the two
maps are still only replaced whole, never mutated, and that `usesGeneratedModel` still reads only the item id.
Dev check: `build.bat runGrillingCheck` (game dir `run-grilling/`, needs kaleidoscope_grilling and
kaleidoscope_cookery in its `mods/`) checks parity for every item, rebuild on replace and timing, and writes
`run-grilling/foxstweaks-grilling-check.txt` (first run: 1638 items match, lookup 8152 ns → 192 ns).
Toggle: `grillingSkewerCache`.

### TACZ attachment tooltip numbers (`mixin/TaczAttachmentTooltipMixin`)

TACZ's attachment tooltip lists which stats an attachment changes ("+ ADS Time") but not by how much. Each
modifier type's `JsonProperty.initComponents()` adds `tooltip.tacz.attachment.<stat>.increase|decrease`
lines once, and `ClientAttachmentItemTooltip` reads them via `JsonProperty.getComponents()`. The mixin hooks
`getComponents` (RETURN) and returns a per-instance cached copy with the numbers appended: it matches each
line's translation key, picks that stat's `Modifier` out of `getValue()` (the value itself; `Pair` left/right
for recoil pitch/yaw and sound distance; the `InaccuracyType` map entry; or a `MoveSpeed` getter) and prints
addend in the refit screen's unit (s, kg, m, m/s, rpm, % for armor ignore), `percent` and `multiplier` as a
% change, and "scripted" when a `function` is set. TACZ types are reached only by reflection (`Pair` is
fastutil's), so no TACZ compile dependency; anything it can't read is left unchanged. Against TACZ
1.1.8-hotfix-r6; re-check the modifier classes' keys and value shapes on update. Client section. Toggle:
`taczAttachmentTooltipNumbers`.

### TACZ model fast path (`mixin/TaczBedrockCubeMixin`, `client/compat/TaczBedrockQuads`)

Ported from our TACZ fork (`watdafawx/TACZ-1.21.1`, branch `perf`) so the pack can run upstream TACZ. TACZ draws
every model cube through `BedrockCubeBox`/`BedrockCubePerFace#compile` each frame: a `Vector3f` per quad, a
`Vector4f` per vertex, and all six sides even when a side has zero area (empty per-face UV, flat side of a
zero-thickness cube) - about 25% of the default gun pack's quads. The mixin (both classes, by name) reads the
private `polygons` field reflectively once at constructor TAIL, keeps the non-zero-area quads as a flat
`float[]` (`@Unique foxstweaks$quads`) and replaces `compile` (HEAD, cancel) with an allocation-free loop. Null
copy = TACZ's own `compile` runs (toggle off at load, or the shape didn't match: one warn, then off for all
cubes). TACZ builds models on its `ClientAssetLoadDispatcher` worker pool, hence the volatile publication of the
cached reflection fields. Toggle `taczFastModelRender`, read when a model is built (F3+T to apply).

**Verify with `build.bat runTaczCheck`** (game dir `run-tacz/`, gitignored): put the upstream TACZ jar in
`run-tacz/mods` and set `EnableLazyClientAssetLoad = false` in `run-tacz/config/tacz-client.toml` (otherwise gun
models are built on first draw and the check sees only ~200 cubes). `client/dev/TaczModelCheck` (dev only,
system property `foxstweaks.taczCheck`) builds every gun display plus a few hand-made box-UV cubes, draws each
cube both ways under a rotated, non-uniformly scaled pose, and requires the fast path's quads to be TACZ's in
order (positions/normals to 1e-5 relative, colour/uv/light exact) with every skipped quad zero-area on screen.
Report: `run-tacz/foxstweaks-tacz-check.txt`. Against TACZ 1.1.8-hotfix-r7: 62,513 cubes, 375,078 → 280,880
quads, draw loop ~2x faster; a swapped normal-matrix term fails parity on ~49k cubes.

### EMI Slim (`emi/*`, `mixin/EmiSlim*Mixin`)

Merged in from the standalone EMI Slim mod (`watdafawx/emi-slim` 0.1.1, now retired). Against EMI 1.1.24.

- **Tag lists shared** (`emiShareTagLists`): `EmiTags#getValues` is wrapped so every `TagEmiIngredient` of a tag
  holds one `SharedTagList`. EMI and plugins mutate stacks they got from an ingredient (`setRemainder`,
  `setAmount`, `setChance`, `comparison`), so `EmiSlimStackMixin` calls `TagShare.beforeChange` first: the
  ingredient the stack was reached through (thread-local `LAST`, set in `getEmiStacks` of `TagEmiIngredient` and
  `ListEmiIngredient`) gets its own list still holding the stack, and the shared list gets an unchanged copy in
  that slot. `ListEmiIngredient`'s flat `fullList` is rebuilt when `TagShare.epoch` moved.
- **Component patches interned** (`emiInternPatches`): `ItemEmiStack`'s `componentChanges` goes through a weak
  Guava interner. Stacks themselves are never shared.
- **Toggles are latched per EMI reload** (`EmiSlim.latch`, from `EmiStackList#clear` HEAD), not read live:
  switching sharing off mid-load would stop owner tracking while shared lists exist. Sharing also stays off if
  `EmiStack`, `TagEmiIngredient` or `ListEmiIngredient` don't implement our interfaces, i.e. their mixins didn't
  apply; sharing without owner tracking would leak one recipe's remainder into every recipe of that tag.
- The `emi/` classes import EMI types and are only reached from EMI mixins.

**Verify with `build.bat runEmiSlimCheck`** (game dir `run-emi/`, gitignored, EMI jar in `run-emi/mods`).
`client/dev/EmiSlimCheck` (dev only, property `foxstweaks.emiSlimCheck`) makes a flat world and checks sharing,
that a change through an owner reaches only that owner (direct, picked-out stack, via `ListEmiIngredient`), patch
interning, and that real recipe tag inputs mostly share. Report: `run-emi/foxstweaks-emislim-check.txt`. Proven
able to fail: both toggles off fails the 5 sharing/interning checks; disabling owner tracking fails the 3 owner
checks. Measured as the standalone mod on TNP Limitless 8 (live heap 60 s after "Reloaded EMI"): 14,209 → 13,739
MB, `ItemEmiStack` 12.49 M → 6.35 M, `DataComponentPatch` 2.59 M → 0.16 M. The log line `EMI Slim: tag lists
built ...` after each EMI bake shows it working in a pack.

### Iris shader settings search (`client/compat/IrisSettingsSearch`)

Iris' shader pack screen nests its settings in sub-menus and offers no search, so finding one option
means remembering which menu it lives in. `IrisShaderScreenSearchMixin` adds an `EditBox` under the
settings list (list top +20, height -20, so nothing overlaps) and `IrisOptionListSearchMixin` replaces
the list's `rebuild` (`@Inject` on the `OptionMenuConstructor.constructAndApplyToScreen` call,
`cancellable`, `require = 0`) with search results while the query is not blank.

- Results are Iris' **own** option widgets gathered from every `OptionMenuElementScreen` (main screen
  plus `subScreens`), so a click changes the setting exactly as in its menu and still waits for
  Apply/Done. Rows are grouped under the menu each one lives in, capped at 80.
- Matching: every word must hit the option's name, its id (`COLORED_LIGHTING`), its description or its
  menu name - substring first, else letters in order (`clrlight`). A grey inline completion suggests the
  best prefix match; Esc clears the query before it closes the screen.
- One screen is open at a time, so the query lives in a static (`IrisSettingsSearch.query`) and is reset
  in the screen's constructor `TAIL`. The search box is only added while the settings (not the pack
  list) are open, i.e. `optionMenuOpen`.
- Iris types are compile-only (`iris_version`) and appear both in the mixins and in `IrisSettingsSearch`/
  `IrisSearchList`; both mixins are `remap = false` and `require = 0`, so an Iris update drops the
  feature instead of crashing. Toggle: `irisSettingsSearch`. Client section.

### FPS hotspots (`client/perf/FpsProfiler`, `FpsOverlayRenderer`, `FpsReportScreen`, `FpsActions`)

`/foxstweaks fps [seconds|stop|clear|report]` (or `/sparkc profiler`, via `SparkCommandHookMixin`) times every
`EntityRenderDispatcher#render` / `BlockEntityRenderDispatcher#render` (two mixins, one volatile read when off).
Data is per-instance `Hotspot`s plus per-type `Group`s; shares and "+FPS" need the whole-frame time, measured between
`RenderFrameEvent.Pre`s (gaps over 1 s ignored). Rules that are easy to break:

- **The local player and camera entity are never hotspots** (`playerMicros()` reports them): in first person only a shader
  pack's shadow pass draws them. Entities not in `mc.level` (`getEntity(id) != entity`) are dropped: GUI paper-dolls share ids.
- **Times are self time** (child stack per depth), so nested dispatcher calls are not counted twice.
- **Labels**: ranked, top 20 within 160 blocks, constant screen size (`0.003 * clamp(dist, 2, 120)`). Not every box has a label by design.
- **Chat buttons run client commands** (`/foxstweaks fps look|tp|show ...`, 1-based rank in `getInstances()`/`getGroups()`).
  The report screen opens a tick later (`pendingReport`) because the chat screen closes itself after a command.
- Teleport = real `/tp @s x y z facing ...` to a collision-free spot (`FpsActions.findSpot`), only when `hasPermissions(2)`.
- **Verified in game** (rig, `build.bat runFpsCheck`, `client/dev/FpsCheck`): scan, player excluded from hotspots, by-type groups, report screen at GUI scale 2 and 3, show type / item beam, `look` / `tp` / `show off` / `clear` through real client commands, ignore type. Labels use a drop shadow, not the font backdrop (it z-fought and dimmed the text tail). Not checked: label size under a shader pack.

### Tab performance cards (`perf/PerfOverlayRenderer`, `client/PerfOverlayMover`, `client/PerfOverlayScreen`)

The two cards (Server / Client) are drawn from `RenderGuiLayerEvent.Post(TAB_LIST)` and positioned by
`Config.perfOverlayPlacement`: `flank` (default) puts Server left of the player list and Client right, falling
back to centred below it when the list is too wide, and every other value (`topLeft` … `bottomRight`) pins the
pair to that edge or corner as a stacked block and ignores the player list entirely. `perfOverlayOffsetX/Y` move
the pinned block (`anchorX`/`anchorY` give the base position, `clampX`/`clampY` keep it on screen), and
`perfOverlayServerCard`/`perfOverlayClientCard` hide either card.

Moving it in game: `key.foxstweaks.move_perf_overlay` (unbound) — pressed while the Tab overlay is up it cycles
the seven presets and writes them with `Config.setPerfOverlayPosition` (which also `SPEC.save()`s), and pressed
anywhere else it opens `PerfOverlayScreen`, where the block is dragged (or arrow-keyed, Shift = 1px) and released;
release snaps to the nearest edge/corner preset and stores the remainder as a pixel offset, so the config stays
readable. A pinned placement also draws `Move: <key>` under the block, so the key is discoverable.

Minecraft hides and locks the cursor while no screen is open, which is why dragging happens in a screen rather
than on the HUD; there is no mouse position to drag with on the Tab overlay itself.

Dev check: `build.bat runConfigCheck` opens `PerfOverlayScreen`, drags the block, and asserts the config took the
new placement. The cards' own rendering is not covered — it needs a live world and a held Tab.

### Mob & Chunk Loader and Apotheosis tiers (`anchor/`, `compat/ApothicAnchorTier`, two mixins)

The anchor (`SpawnAnchorItem`, the craftable dummy, and `/foxstweaks anchor add|remove|list|tp`) registers a
force-tick ticket per chunk square and answers the proximity checks `ChunkMap`, `ServerLevel` and
`NaturalSpawner` ask (`ChunkMapAnchorMixin`, `ServerLevelAnchorMixin`, `NaturalSpawnerAnchorMixin`), so a loaded
farm keeps spawning, random-ticking and not despawning with nobody around.

**Apotheosis has no such notion.** It takes the world tier from the nearest player *in the dimension*
(`ApothMobEvents#finalizeMobSpawns`, `getNearestPlayer(x,y,z,-1,false)`), and that one value drives everything:
tier augments, elites and invaders. With nobody in the dimension the handler bails out at its first line; with
someone 600 blocks away it uses their tier and rejects invader (boss) spawns as too far. `AnchorTier` therefore
hands Apotheosis a stand-in player at the spawn that carries the anchor's tier:

- `AnchorTier.replace(real, level, pos)` — keeps the real player when one is within
  `spawnAnchorsTierPlayerRange` (128), otherwise asks `compat/ApothicAnchorTier` for a stand-in for the nearest
  tier-carrying anchor.
- `ApothicAnchorTier.forAnchor` uses `FakePlayerFactory.get(level, profile)` and sets
  `Apoth.Attachments.WORLD_TIER` directly — **never** `WorldTier.setTier`, which sends a packet, runs tier
  augments and awards a statistic on any `ServerPlayer`. The stand-in is moved to the spawn position, so
  Apotheosis' own distance checks pass, and marked creative so `AdventureConfig.bossAutoAggro` does not target it.
- **Profile UUID matters.** `WorldTier.getTier(FakePlayer)` looks the real player up by UUID and overwrites the
  attachment, which is how "follow the owner" works for free. A tier pinned with `/foxstweaks anchor tier` must
  therefore use an anchor-derived UUID (`UUID.nameUUIDFromBytes("foxstweaks:anchor:" + name)`), or an online
  owner's live tier would silently replace the pin — that bug cost a debugging round.

**Three hooks, all `require = 0` and targeted by string** (`ApothMobEventsTierMixin`, `ApothSurfaceTypeTierMixin`):

| Hook | Why |
|---|---|
| `ApothMobEvents#finalizeMobSpawns`, the `getNearestPlayer` call | the tier/elite/boss decision itself |
| `ApothMobEvents#delayedEliteMobs`, `getPlayerByUUID` + `getNearestPlayer` | elites transform on entity join, by UUID; a stand-in is not a real player, so an offline owner's elite would never transform (it keeps `apoth.miniboss` as a String) |
| `SurfaceType#skyOrSameVerticalSlice`, its own `getNearestPlayer` | that method dereferences the result with no null check. Unreachable while Apotheosis' bail-out protects it, but hook 1 deliberately gets past the bail-out, so a roofed farm with nobody online would NPE (Overworld's default surface rule is this one). Answering with the stand-in also makes its 8-block vertical-slice test pass. |

`AdventureEvents#applyMissedTierAugments` is *not* hooked: it only runs for mobs that never went through
`finalizeMobSpawns`, which is not the farm path.

**Config**: `spawnAnchorsApotheosisTier` (default on) and `spawnAnchorsTierPlayerRange` (128, 0 = the anchor
always wins). **Commands**: `/foxstweaks anchor tier <name> <haven|frontier|ascent|summit|pinnacle|owner|off>`
(`owner` follows the placer again), and `anchor list` shows each anchor's tier and mode. **GUI**: the loader screen
has a `Tier:` button that cycles off → haven → … → pinnacle → follows owner (disabled and labelled
`needs Apotheosis` without it) and a `Name: shown/hidden` toggle for the floating nametag
(`SpawnDummyEvents.applyState` sets `setCustomNameVisible`; the tag is `foxstweaks_anchor_hide_name`). Both controls
send `UpdateAnchorPayload`, whose registrar is version **"2"** since the payloads grew those fields; a v1 client
simply cannot open the screen and nothing breaks. An unscoreable tier string in an update is *ignored* (the anchor
keeps its own) rather than rejecting the whole update, so radius/name/status still apply - that distinction cost a
round: rejecting the update made the name toggle look broken on a pack without Apotheosis. Anchors that existed
before 1.36.0 carry no tier until one is set; a player-placed dummy captures the placer and their tier on
placement (`SpawnDummyEvents.initDummy`, tags `foxstweaks_anchor_owner|owner_name|tier|follow_owner`).
`SpawnDummyEvents.applyState` rebuilds the anchor record on every GUI click, so it must carry those tags over or
a radius change silently clears the owner. Screen code must stay on the **client thread**: opening it from a
`onServer(...)` block makes the render thread draw a screen whose `minecraft` field is still null (that crashed
the tier check once).

**Not carried**: luck, game stages, and the owner's invader cooldown only when they are online. That last one is
deliberate — the stand-in shares the owner's cooldown instead of handing each anchor a fresh one, so an offline
farm cannot spawn invaders faster than the owner could standing there.

**Telling the owner when it breaks** (`anchor/AnchorTierNotice`, `compat/ApothicHookProbe`): because every hook is
`require = 0`, an Apotheosis update that renames or moves a target would otherwise turn the feature off without a
word. On `ServerStartedEvent` the mod probes the three targets by reflection (class present, method name and
event parameter unchanged), resolves `Apoth.Attachments.WORLD_TIER` and `WorldTier.values()`, and:

- logs `Spawn anchor Apotheosis tier support fits Apotheosis <version> (N anchor(s) carrying a tier)` when healthy;
- logs a `WARN` naming the part that moved when not, and sends that same line once per operator
  (permission 2+, once per session) in chat on join - `[Fox's Tweaks] Apotheosis <version> no longer fits ...`;
- still speaks up if the feature is switched off or Apotheosis was removed while anchors carry a tier
  (info level, no chat).

The probe verifies declarations, not bytecode: a method that kept its name and signature but lost the
`getNearestPlayer` call inside it looks fine to it, which is what `runAnchorTierCheck` is for. `-PfakeBreak`
(`-Dfoxstweaks.anchorTier.fakeBreak=true`) makes the probe report a fabricated break so the warning and the chat
line can be checked without waiting for a real update; it must be removed from `AnchorTier.probe` only if the probe
itself is rewritten.

**Dev check**: `build.bat runAnchorTierCheck` (game dir `run-anchor-tier/`; put Apotheosis, ApothicAttributes and
Placebo in its `mods/`). `client/dev/AnchorTierCheck` builds a roofed farm, pins the anchor to Pinnacle, moves the
only player 600 blocks away and runs the exact call Dreadful Dirt makes
(`EventHooks.finalizeMobSpawn(..., MobSpawnType.NATURAL, null)`), then asserts the `apotheosis:pinnacle/armor`
modifier appears (300/300), that an invader spawns (`apoth.boss`), that turning the tier off stops it, and that it
still works with the player in the Nether. It also clicks the GUI's `Tier:` button and asserts the anchor data
changed, which is the only coverage of that payload with Apotheosis present. `-PtierOff`
(`-Dfoxstweaks.anchorTier.off=true`) runs the same check with the path disabled and must fail all three tier
assertions - it does. Report: `run-anchor-tier/foxstweaks-anchortier-check.txt`. `runAnchorCheck` (game dir
`run-anchor/`, no mods) covers the rest of the screen: it clicks the name toggle and asserts the tier button is
disabled without Apotheosis.

Two things that cost time and are easy to trip over again: **spawn the test mobs across a floor, not stacked in
one block** — Apotheosis' boss placement runs `checkSpawnObstruction`, which is false when a mob already occupies
the spot, so a single-block pile reports zero bosses and looks like a broken hook; and **`Apotheosis.LOGGER.debug`
is filtered**, so its `APOTH_DEBUG_MOBS` branch prints nothing useful — reproduce the gates in the check instead
(the `invader sample` line does that).

### Holograms (`hologram/*`, `client/Hologram{Client,Renderer,Screen}`)

A hologram is a block drawn in world as decoration: no collision, no light, no block entity, no tick, nothing
to break. That is not a special case - it is the whole design. `HologramSpec` is one saved row, `HologramData`
is a per-dimension `SavedData`, and `HologramRenderer` draws it with a single `BlockRenderDispatcher#renderSingleBlock`
call at `AFTER_TRANSLUCENT_BLOCKS`. An idle hologram costs nothing; a visible one costs one model draw.

**Everything is server-authoritative and nothing in the world exists to click.** `HologramPick` ray-tests the
player's eye/look against the stored `AABB`s, and both sides run it: the server to decide what a right-click did,
the client to draw the outline. That is why there is no marker entity (unlike the Mob & Chunk Loader's armor
stand) and no client-sent id the server has to trust.

**The state picker is generic, and that is the point.** `HologramStates` turns a block name plus
property/value pairs back into a `BlockState` and lists its properties one at a time. Never enumerate
`BlockState`s: redstone wire has 1296 of them and glow lichen 1024. AE2's controller is the shape this was
built for - `state` (`offline`/`online`/`conflicted`) x `type` (`block`/`column_x|y|z`/`inside_a|b`), a plain
multipart model, with the pulsing lights baked into `controller_lights.png.mcmeta`. Its animation therefore
runs anywhere the model is drawn, with no AE2 code and no block entity renderer. Verified against AE2 19.2.18.

**Not covered (next phase):** block entity renderers. `HologramScreen` filters the picker to
`RenderShape.MODEL` blocks, so chests/signs/ber-only blocks are simply absent rather than drawing nothing.
A BE tier needs a fake `ClientLevel`, `BlockEntityType#create(pos, state)` and manual ticking; treat it as
risky and keep it behind its own toggle.

**Live preview, no packet per click.** The open editor pushes its edited copy through
`HologramClient#setPreview`, which the world renderer substitutes for the saved row by id. `Done` sends
`HologramActionPayload` (registrar `"1"`, `optional()`), `Delete` sends the same payload with `DELETE`, and
closing with Esc drops the preview. The client is never trusted for identity: the server rebuilds the spec
from the stored row and only takes the presentation fields.

**The editor is two small edge panels, and the middle of the screen is deliberately empty.** One window
wide enough for a block grid plus every control covered half the screen at a normal GUI scale, which
defeats the point of editing something you are looking at. So: block grid + its blockstate properties on the
left, values on the right (`Look` / `Motion` / `Display` tabs), and the hologram visible between them. Both
panel widths and the grid's column count come from the screen width (`HologramScreen#init`), and the dev
check asserts the gap stays >= 90px, because a layout that only fits one GUI scale is a bug waiting for
someone to play at scale 4.

Two more things that are not cosmetic:

- **`renderBackground` is overridden to a light tint on purpose.** Vanilla's screen background blurs the
  world, which makes the live preview unreadable. If a future change reinstates `renderBackground`, the
  editor goes back to being useless. (Drawing into the blur stratum also washes the panel out, since the
  blur is applied at the next flush.)
- **Every numeric row takes the mouse wheel**, and one `Step` multiplier (x1/x2/x5/x10) scales all of them:
  the natural increment differs per field (size 0.25, angles 15 degrees, range 8 blocks), so scrolling must
  be fast when roughing in and fine when fixing. Property rows in the block panel cycle under the wheel too.
  `Display > Preview in front` additionally draws the edited hologram a few blocks in front of the camera
  (`HologramRenderer#drawAnchoredPreview`), for when the real one is behind you or out of view.

**Effects are pose and colour maths, not a shader.** `HologramEffect` is a list of layers (TINT, FLICKER,
TREMOR, WOBBLE, JUMP) and `HologramAnim` is every curve as a pure function of time, kept out of the renderer
so the dev check can assert the maths directly. Deliberate limits, all of them because a real post-process
would need a framebuffer per hologram and would fight every shader pack:

- **No alpha fade.** Most block render types do not blend, so alpha is ignored. `FLICKER` hides whole frames
  instead, and the walk-in animation is geometry (grow + settle), not transparency.
- **TINT is a vertex-colour multiply** (`client/TintedBuffer` wraps the `MultiBufferSource` and scales
  `setColor`). One multiply per vertex, no second pass, no render-type swap.
- **No true distortion/warp.** `WOBBLE` oscillates scale and `TREMOR` jitters rotation; both read as a
  wobbling projection without needing to sample the screen.

**Walk-in reveal is client-side.** `appearRadius` (0 = always visible) plus a fixed 0.6 s grow-in:
`HologramClient#updateAppear` advances a per-hologram progress toward whether any player is inside the
radius, and the renderer scales/raises the hologram by the eased progress. Nothing is sent to the server -
each client animates its own copy, which is the only correct behaviour when different players are near
different holograms.

**Measured cost** (`runHologramCheck`, rig, 1920x1080, 48 holograms all in view at once):
`48 plain 132 us/frame`, `48 with 4 effect layers each 135 us/frame`, `after removal 4 us` - i.e. ~2.7 us of
CPU per visible hologram, and the effect layers are inside the noise. Measure the pass, not the frame: the
frame sat at 8.44 ms in every round because the window is vsynced/GPU-bound, which made whole-frame timing
useless. `HologramRenderer#lastPassNanos` is what the benchmark reads.

**Content parts: one hologram can hold a block, an entity and particles at once.** `HologramPart` is a list on
the spec, and offsets are **fractional blocks** (`float`), unlike the hologram's own `BlockPos` - that is the
whole reason the type exists, because "a zombie standing on a pedestal, with sparks at its feet" cannot be
expressed with three holograms a block apart, and three separate holograms cannot stay in step when they
flicker or grow in. The parent keeps transform, effects and appear radius; a part only says what it is, where
it sits, how big and which way it faces (`HologramPart.MAX_PARTS` = 4).

- **The base block stays special** (`block` + `props`, driven by the block grid). A hologram whose base is
  `minecraft:air` is legal and draws only its parts - that is how you make an entity-only display
  (`/foxstweaks hologram add minecraft:air`, then add an entity part). `HologramStates#blockName` exists for
  that: `nameOf` answers `""` for air, which would make `fromTag` reject the row.
- **Entity parts are real entities that are never added to the level** (`client/HologramEntities`): one cached
  instance per type, `setPos` + a synthetic `tickCount` for idle animation, `setNoGravity`, and
  `EntityRenderDispatcher#prepare` called before `render` because nametag renderers read the camera. No AI, no
  ticking, no collisions, nothing saved. Creating an instance per draw would allocate at frame rate.
- **Particle parts spawn from the client tick, not the render pass** (`HologramClient#emitParticles`): particles
  are stateful, so adding them per frame would multiply the count by the frame rate. Every other tick is 10/s
  per part. Only `SimpleParticleType` entries are offered; block/item particles need an options object the
  editor cannot fill in yet.
- **Editor**: the Parts tab has one "Add" row (wheel picks the entry, right-click changes block/entity/particle,
  click adds) and one row per part (wheel = 0.25-block vertical nudge, click = cycle centre/4 sides, right-click
  = remove). New parts start on top of the base (`offsetY = scale`) because a 2.5x hologram swallows anything
  placed at y=1.

**Batching was measured, not assumed - and it would not pay.** 48 identical blocks cost ~120 us/frame of CPU;
the same 48 with 48 *distinct* models cost ~160-188 us. The difference is more quads per model and more render
types, not model lookup, so batching by state (which can only merge identical states) has nothing to give back.
Per-draw cost is vertex emission: ~2.5 us per visible hologram. What *does* pay is **frustum culling**
(`event.getFrustum().isVisible(spec.box())` before any pose work): 48 holograms behind the player cost ~2 us,
i.e. nothing. The dev check measures all four rounds and fails if the numbers move the wrong way.

**Block entities: the hologram carries data, not just a block id.** Chests, signs, beds, banners, shulker boxes
and most modded machines draw **only** through a `BlockEntityRenderer`, so `renderSingleBlock` gives nothing.
`HologramSpec#data` is a `CompoundTag` captured with `saveWithoutMetadata` from the block the wand was pointed
at, and `client/HologramBlockEntities` turns it back into an instance on the client:

- created via `EntityBlock#newBlockEntity(pos, state)` (not `BlockEntityType#create`, which needs the type id),
- `setLevel(clientLevel)` so renderers that ask for a level work, `loadWithComponents(data, registries)`,
- cached per hologram id and rebuilt when the tag's hash changes,
- ticked by hand through `EntityBlock#getTicker` so chest lids open and machine LEDs blink,
- rendered by calling `BlockEntityRenderer#render` **directly**: the dispatcher's own `render` translates by
  `be pos - camera`, which fights the pose the hologram already built. The renderer expects a pose at the
  block's corner, exactly like a model, so both paths share the same pose.

`Holograms.MAX_DATA_BYTES` (64 KB) caps what is kept: a chest hologram otherwise copies its whole contents
into the world file and syncs them to every client for a decoration. The picker now offers
`ENTITYBLOCK_ANIMATED` blocks, which it previously filtered out entirely.

**Verified**: a chest with diamonds in it round-trips its contents and renders; the same block placed next to
its hologram looks identical. **Not verified**: capability/grid-backed state. AE2's drive captures only
`inv` + `priority` (its LED status is derived at render time from the grid, not saved), so a *powered* drive's
lights reproducing is still open - that needs a networked AE2 setup to settle, and is the per-mod work this
approach cannot avoid. Its casing builds, renders and draws through its own renderer.

**Config**: server `hologramsEnabled`, `hologramsMaxPerDimension`, `hologramsMaxRenderDistance`; common
`hologramRenderCap` (a client-side ceiling over every hologram's own range). All four need labels, which the
config-check dev run asserts.

**Dev check**: `build.bat runHologramCheck` (game dir `run-hologram/`; put AE2 + GuideME in `mods/` for the
`ae2:controller` half). `client/dev/HologramCheck` creates a hologram through the real command, opens the real
editor through the real command, asserts the panel gap, the wheel+Step arithmetic, the property cycle, the
effect layers and the appear radius, saves, asserts the server took the change, walks out of and back into the
appear radius, then benchmarks 48 holograms (plain, with four effect layers, and after removal) and writes
the report. Screenshots: editor tabs, effect tab, world views, AE2. Report + screenshots: `run-hologram/`.
The screen exposes `current()`, `currentProperties()`, `devCycleProperty`, `devSetScale`, `devSetSpin`,
`devTab`, `devStepIndex`, `devRowCount`, `devScrollRow`, `devRemoveEffect`, `devGridCols` and `devFreeWidth`.
Screenshots land in `run-hologram/screenshots/`, not the run root.

**Animations (1.41.0).** Layers and groups hold a list of `HologramEffect`s that mixes FX, motion (SPIN, SWAY, BOB, ORBIT,
SLIDE, BREATHE, HEARTBEAT, FLIP, BOUNCE) and entity animations (WALK, ATTACK, HURT, DEATH, SNEAK); each has strength, speed,
axis and phase. `HologramAnim.motion(effects, seconds)` is the pure maths, `HologramEntities.applyAnimations` drives the
entity pose. Legacy `spin`/`hover` fields still render (saves are not migrated; the editor offers a convert button).
Entity age **must** come from the hologram clock for both ticks and partial tick, never the game timer's partial tick,
or idle animation stutters. The editor opens the studio camera framed on the hologram (`HologramCamera.frame`, key F);
chat opens through `HologramChatScreen`, which restores the same editor instance. `runHologramCheck` covers the editor UI
(`devClick` presses real buttons) and frame smoothness (`HologramRenderer.lastEntityTicks`).

**Triggers (1.42.0).** `HologramEffect.trigger()` (`Trigger`: ALWAYS, DAY, NIGHT, RAIN, THUNDER, JUKEBOX, MUSIC, MOB_DIED; NBT key
`when`, only written when not ALWAYS). `client/HologramTriggers.resolve(effects, spec, seconds)` turns an effect list into what to
draw now: strength scaled by an eased gate, dropped when the gate is shut, returns the same list when nothing has a trigger. It is
called wherever effects are read (renderer groups/layers/entity anims, particle emitter). State is per effect instance (identity), so
pasted copies that share an instance share state. A SPIN keeps its own clock (gate-time) so it holds its angle while shut. Jukebox
follows `SoundSource.RECORDS` sounds (the client block entity does not carry the disc), music follows `SoundSource.MUSIC`, both via
`PlaySoundEvent` and pruned by `SoundManager.isActive`; deaths are scanned from `deathTime` only while a MOB_DIED effect is drawn.
The rig has no audio device, so the check injects the record with `HologramTriggers.devRecord`.

**Creator (1.43.0).** `client/HologramCreatorScreen` is the default editor (`HologramEditorPrefs.startInCreator`, saved looks
live there too). It owns no state: it wraps a `HologramScreen` instance and edits through that screen's package-private
`creator*` bridge methods, so undo, preview and saving stay in one place; Studio ›/< Simple swap screens on the same instance.
It is immediate-mode: every control registers a `Hit` per frame with its own `clipTop`/`clipBottom`, and a click outside the
clip is ignored - without that, a half-scrolled row stole clicks from the footer (Done stopped working). Looks replace FX only,
never motion. Axis chips colour by `axisLabel` (X red, Y green, Z blue), not by axis index. PLACE (collapsed by default, so the panel does not grow at GUI 3) and the "Whole hologram" root row go through `creatorPlace`/`creatorSelectRoot`; never call `creatorEffects`/`creatorScale` while the root is selected, they force the target back to a layer. The 3D gizmo (1.47.3) is `HologramScreen`'s own `HologramGizmo`,
reached through `creatorGizmo*` bridge methods (mode row under the top bar, keys 1-4, Esc undoes a drag); it acts on the selected layer. Any
new editor feature must go through the bridge too, or the Creator falls behind the Studio again. Not yet checked in a live client. `HologramCamera.isActive` has
to list every hologram screen (Studio, chat, picker, Creator) or the studio camera switches off when one opens.
**Pictures (1.47.3):** `client/HologramAssetScreen` (library grid from `HologramAssets.requestListing`, tiles draw `HologramAssets.texture`, click = `creatorAddLayer(HologramLayer.image(..))`) and `client/asset/HologramAssetImport` (path, `file:` or http(s) -> worker thread -> `HologramAssets.uploadBytes`, so the server validates as for any upload). A file dropped on the Creator opens it and uploads (`Screen#onFilesDrop`). Fitting runs in game too: `client/asset/HologramAssetFit` (ImageIO, same reader as the server) scales/re-encodes oversized stills and thins GIFs into an FTAS bundle (`MODE_BUNDLE`) against `AssetLimits.current()`; it falls back to the original bytes when it cannot read them. Limits come from the library listing (`dimension`/`fps`/`seconds`/`maxBytes`, `HologramAssetImport.serverLimits`), else the synced config; the server still validates. Sources up to 64 MiB are opened (video 16 MiB); WebP is refused up front. The library screen has a search box and two-step right-click delete (`requestDelete`). Checked by the 5000x3000 PNG, fast GIF, WebP, search and delete steps in `runHologramCreatorCheck`. `HologramCamera.isActive` lists it.
Dev check `build.bat runHologramCreatorCheck` (`run-hologram-creator/`); `devClick`/`devDrag` press controls by tooltip
prefix and return false for a control outside its clip, so a check must `devReveal` (scroll) first. `devPinsStudio` keeps
the other hologram checks on the Studio.

**Block browser** (`client/HologramPickerScreen`, + `HologramBlockColors`, `HologramPickerFamilies`, `HologramPickerFavorites`):
opened by Browse / B and the Creator's +; `returnTo(screen)` decides where Esc and a pick go back to. Favourites and recent
are JSON on the client. Dev check `runHologramPickerCheck` (`run-hologram-picker/`).

**Grab** (`client/HologramGrab`, `hologram/HologramGrabber`, `HologramGrab(Reply)Payload`): a mode over the world, entered from
the Studio or Creator (`begin(editor, returnTo)`); the client sends only what was clicked (block pos, entity id, or two
corners), the server reads the world, checks range, loaded chunks, `hologramsEnabled`, a per-player token bucket and the
caps, and replies with layers that `HologramScreen#addGrabbedLayers` adds. Entity layers carry NBT
(`HologramEntities` loads it). Dev check `runHologramGrabCheck` (`run-hologram-grab/`).

**Structures**: `HologramLayer.Kind.STRUCTURE`; the data is `HologramStructure` (palette of block states + index array +
per-position BE tags), saved under `structure` instead of `blockEntity`, and the layer id is its display name
("Structure WxHxD"), not a registry id. Server config `hologramStructureMaxDimension`/`MaxBlocks`/`MaxBytes`
(32 / 4096 / 256 KB), enforced on grab **and** on every editor/web save. `client/HologramStructureMesh` caches
neighbour-culled VBOs per render type, rebuilt on content or light change, freed on removal and resource reload; SCANLINE
and GRADIENT need per-vertex colour and fall back to uncached emission. `-Dfoxstweaks.hologram.uncachedStructure=true`
forces the naive path for comparison (16³ mixed: ~100 µs cached vs ~2,700 µs). Dev check
`runHologramStructureCheck` (`run-hologram-structure/`). Not verified: shader packs, modded BE models.

**Fluids** (1.44.0). Fluids never go through block models, so they compile separately into the same per-render-type
VBOs: `BlockRenderDispatcher#renderLiquid` over `StructureBlockGetter` (outside the structure is air, biome tint from
the hologram's `anchorPos`, which is part of the cache key), with `TranslatingFluidConsumer` undoing
`LiquidBlockRenderer`'s section-relative `(pos & 15)` coordinates. Water goes to `RenderType.translucent()`, lava to
`solid()`, so atlas animation runs without rebuilds. Translucent VBOs keep `MeshData.SortState` and re-upload only the
index buffer when the camera moves more than 0.5 blocks in structure space. The uncached path (SCANLINE/GRADIENT,
forced) and standalone BLOCK layers emit through `PoseFluidConsumer` into `RenderTypeHelper.getEntityRenderType(..., false)`
(writing `OverlayTexture.NO_OVERLAY`), so `TintedBuffer` multiplies biome tint by the hologram FX. A waterlogged block's
water spreads into air, so test scenes must wall it in. `runHologramStructureCheck` compares a 9x5x5 water/lava/kelp/
waterlogged scene against the real build (99% water / 98% lava pixel overlap) and benchmarks a half-water 16³ (~65 µs,
0 rebuilds). Not verified: shader packs on fluids, modded fluid renderers, fluid faces at the structure boundary.

**Web companion** (`client/web/*`, `client/HologramWebBridge`, `web/`, protocol in `docs/hologram-web-protocol.md`): a client-only
HTTP + WebSocket server on 127.0.0.1, a new port and 192-bit token per session, Host and Origin checked, 1 MB frames,
rate limited, dies with the editor. The game stays the viewport and the authority: the browser sends whole specs, which
`HologramWebJson` validates like any packet, and NBT never comes from the browser - a layer refers to its data by an
opaque key from what the session sent (a structure layer without its key is rejected). The Preact/Vite app's build output
is committed under `assets/foxstweaks/web/`: after changing `web/src`, run `npm ci && npm run build` in `web/` and commit
both. Dev check `runHologramWebCheck` (`run-hologram-web/`) speaks the real protocol over a socket. Protocol versions:
2 timeline, 3 image layers and assets, 4 audio modulations and the meter, 5 icons, 6 asset delete (`HologramWebSession.PROTOCOL` and
`web/src/protocol.ts` must match). Planned next: WaterMedia when installed, audio tracks.

**Icons (protocol 5).** `client/web/HologramWebIcons` draws the content browser's, outliner's and inspector's pictures:
`icons {kind, ids}` -> one PNG sheet (64 px cells, 16 columns) + id -> cell map, request/answer like `assetThumb`, no
streaming. Blocks and entities render on the render thread (`RenderFrameEvent.Post`, so never during the world pass) into a
`TextureTarget` the size of the sheet, one viewport per cell, one read-back per request, 32 blocks / 6 entities per frame
under a 4 ms cap; PNG encoding runs on a worker thread. Entities reuse `HologramPickerPreview.drawEntityUnclipped`: the
picker's own `drawEntityIcon` scissors through `GuiGraphics.enableScissor`, which converts with the window's GUI scale and is
wrong off-screen. Particles are a CPU copy of the first sprite frame (no GL) from the `TextureManager`'s particle atlas, not `Minecraft#getTextureAtlas`, which does not know it. `RenderTarget#clear` ends with `unbindWrite`, so `bindWrite` must come after it or the first frame's icons land in the window and the sheet cells stay blank. A failed type is logged once and mapped to
`-1`, which the page shows as the old letter badge. The page side is `web/src/icons.tsx` (`Icon`: one `IntersectionObserver`,
only on-screen tiles ask) + the cache and 120 ms debounced batching in `store.ts`; a page older than 5 never asks. Dev check:
`runHologramWebCheck` requests diamond block, chest, pig, flame and more over the socket, decodes each sheet and asserts size,
transparent background and colours; it writes `run-hologram-web/web-icons-{block,entity,particle}.png`. Not verified:
shader packs (Iris may treat the off-screen target oddly), modded entity renderers.

**Edit sessions** (`hologram/HologramEditSessions`). The save/delete/preview distance gate in `HologramNetwork.handle` is
`BASE_LIMIT` (`REACH + 8` = 14) with no session, and `max(BASE_LIMIT, ServerConfig.hologramsEditRange())` while the
player has that hologram id open in that dimension. A session starts in `openEditor` or on the first PREVIEW from within
`BASE_LIMIT`; it ends on SAVE, DELETE and CANCEL (a refused SAVE or DELETE too, since the client closes the editor
anyway), on logout, dimension change, server stop, and after `IDLE_TICKS` (10 min) with no accepted packet. A new editor
path must call `openEditor`, or the player is held to 14 blocks again. A refused non-PREVIEW save sends a chat line.
**Text fit:** `HologramCreatorScreen` records fits (`TextFit`, `devFits()`) and `HologramCreatorCheck.assertFits` asserts
them; GUI 3/4 checks must set a 1920x1080 window (854x480 caps the GUI scale at 2) and assert the real scale.
**Own GUI scale:** `client/HologramEditorScale` swaps `Window#setGuiScale` while an editor screen (`isEditor`, also used by `HologramCamera.isActive`) is open: `ScreenEvent.Opening` runs before the new screen lays out, and a tick check resizes after a window resize/options change. Pref `HologramEditorPrefs.guiScale` (0 game, 1-4); a new editor screen class must be added to `isEditor`. Checked in `runHologramCreatorCheck` (game 3, editor 2).
**Names:** `HologramBlockNames.disambiguate` is the one place duplicate block names get "(wall)" or "(path)" (picker and
`HologramWebRegistries`). `HologramSearch.rank` and `rank` in `web/src/browser.tsx` must stay equivalent.

**Timeline (1.44.0).** `HologramSpec.timeline` (`hologram/HologramTimeline`, NBT key `timeline`) holds tracks that target
ROOT, a group id or a layer **uid** (`HologramLayer.uid`, `[A-Za-z0-9_-]{1,24}`; the spec gives missing or repeated uids a
fresh `lN`). Effect tracks address an effect by index; `web/src/anim.ts` shifts them on reorder and delete. A key's easing
shapes the segment that leaves it; CUBIC is CSS cubic-bezier (x clamped 0..1, y -2..3). `HologramTimelineEval` is pure and
allocation-free per frame; it binds track slots once per spec instance (cached on the timeline object, not part of
equality), and the renderer reads keyed values as *replacements* for the static base, then adds `HologramAnim.motion` on
top. Do not fold motion into the timeline or the reverse. The clock is `client/HologramTimelinePlayer`: game time, frozen
by the editor Pause, restarted by the start trigger. The web `playhead` message overrides it for the editor's hologram
only and is never saved. Caps (`hologramTimelineMaxTracks`/`MaxKeys`) are enforced in `HologramWebJson.readTimeline` and
`HologramNetwork.timelineProblem` (every save path). Registrar "4". `HologramRenderer.devTraceId`/`DEV_TRACE` exist only
for `runHologramWebCheck`'s composition assert. Not verified in a real browser: the dock UI, graph editor, auto-key.

**Assets** (`hologram/asset/*`, `client/asset/HologramAssets`, details in `hologram/asset/README.md`). Stored as
`<world>/foxstweaks/assets/index.json` plus immutable `blobs/<sha256>`; a blob is a canonical FTAS bundle (PNG frames +
millisecond delays), so the hash dedupes. PNG/JPEG/GIF uploads are validated (magic, dimensions before decode, pixel and
frame budgets), downscaled and re-encoded on the server. `foxstweaks:asset` payload (own optional registrar "2"):
30,000-byte sequenced chunks (serverbound packets are capped at 32 KB), lazy downloads, one upload per player per second,
40 packets/s, 30 s expiry. File system errors are logged and sent to the client only as "Asset storage error" (their
message carries absolute server paths). `Holograms` retains/releases assets on save and removal; release never deletes.
Client: hash-verified 200 MiB disk LRU, 32 MiB compressed in RAM, `HologramAssets.texture(hash, seconds)` returns null
while loading and reuses one `DynamicTexture` per asset. JPEG frames (every converted video) decode through LWJGL's `STBImage`, never
ImageIO: ImageIO's JPEG reader failed in a real pack with an `Error` that left the frame blank and the thumbnail
unanswered, while working in dev runs (1.46.1). `runHologramImageCheck` asserts the JPEG bundle's thumbnail and texture. SERVER section `assets`. Dev check `runHologramAssetCheck`
(`run-hologram-asset/`). Not verified: non-op multiplayer, cache eviction under stress; ImageIO normalisation runs on the
server thread and can stall an upload tick.

**Video conversion** (`media/*`, plain Java, no Minecraft classes except `MediaConfig`, so `build.bat mediaSelfTest` runs
it in a bare JVM against a real ffmpeg). `FfmpegLocator`: `mediaFfmpegPath` (exe, folder or bare name), then PATH, then
common install folders; a candidate counts only after `ffmpeg -version` answers within 5 s, and the answer is cached.
`VideoConverter.convert` sniffs the container (mp4, EBML, GIF, AVI), runs ffmpeg from an argument list (never a shell)
with `-t`, `fps`, `scale` and `-frames:v` caps, polls the temp folder for the byte cap (`-fs` caps each image2 file, not
the total), kills the process tree on timeout, and moves frames out only on full success. One conversion at a time, queue
of 4. Server section `media` (`mediaFfmpegEnabled`, `mediaFfmpegPath`, `mediaConvertTimeoutSeconds`). Only mp4 is
exercised by the self test; rotation metadata is untested.

**Image layers (1.44.0).** `HologramLayer.Kind.IMAGE`: the id is the asset's hash, and the settings are string props
parsed and clamped by `hologram/HologramImage` (`w`, `h` where 0 keeps the aspect ratio, `billboard` FIXED/FACE/FACE_Y,
`sides`, `play`, `speed`, `start`); the layer's `fullbright` flag means emissive. `HologramRenderer` draws one textured
quad per layer from `HologramAssets.texture(hash, t)`, with `t` from the timeline's clip time when there is a timeline
(`mediaClock`), so a web `playhead` scrubs animations. Every save path runs `HologramNetwork.imageProblem` (every hash in
the library, at most `hologramImageMaxLayers`). Web protocol 3 adds `assetCaps`, `assets`, `assetThumb` and chunked
`upload*` messages (`client/web/HologramWebAssets`); the browser never talks to the server directly, and scripts must wait
a second between uploads. Dev check `runHologramImageCheck` (`run-hologram-image/`): PNG, GIF and frame-bundle uploads
through the real protocol, NBT round trip, screenshots at two times (GIF changes, PNG does not), billboard after a camera
move, timeline scale, caps, 48-hologram benchmark (~5.9 µs each). The mp4 step is skipped when rig has no ffmpeg. Not
verified: the Images tab and WebCodecs in a real browser, non-op uploads, shader packs, dedicated servers.

**Uploads of any size (1.46.0).** The page never refuses a file for being big or long: `web/src/fit.ts` fits it to the
caps first. Stills the server can read as they are (PNG/JPEG under `maxFileBytes`, inside the 4096 px / 4 MP decode guard)
go unchanged; anything else is scaled to `maxDimension` and re-encoded (PNG, JPEG when PNG is too big and there is no
alpha). Animations (GIF, animated WebP/PNG/AVIF, through `ImageDecoder`) and every video the browser can decode become an
FTAS bundle: frame rate drops first (to 10), then size (to 128 px), then both further, measured on 6 sample frames and
then on the real encode. Frames are JPEG unless the source has alpha (PNG). It aims at 85% (JPEG) / 75% (PNG) of
`maxFileBytes` because the server re-encodes each frame, and retries at 70% then 45% when the server still answers with a
byte-limit error. Longer than `maxSeconds`: the Images tab's trim dialog picks start + length first. Server ffmpeg is
only used for a video this browser cannot decode (then 16 MiB input, first `maxSeconds`). Delays must be counted the
server's way, `ceil(1000/fps)` each: 150 frames of 67 ms is 10.05 s and a 10 s cap refuses it (that bug shipped in the
old browser converter). Verified headless (Chrome + the server's real `AssetFrames.sanitize`): 5000x3000 20 MB PNG, 40 s
720p MP4 cut to 15-25 s, 6 MB 20 s GIF cut, alpha WebP, a 300 KB cap forcing 128 px / 9 fps. Not verified: an upload
through a live game, Firefox/Safari (no `ImageDecoder` there means a GIF over the cap keeps only its first frame).
**Animated WebP without `ImageDecoder` (1.47.1):** `web/src/webp.ts` parses the RIFF (VP8X animation flag, ANMF frames), re-wraps each
frame as a still WebP (VP8X + frame chunks) for `createImageBitmap`, and `fit.ts#openWebpAnimation` composites them (dispose/blend,
replay from frame 0 when seeking back). The server never reads WebP. Parser checked on a Pillow-made file; the browser decode
path is not checked in Firefox.

**Entity life (1.44.0).** `client/HologramEntityLife` samples animation fields without calling `tick()` or `aiStep()`:
walk/run from the hologram clock, FLY/SWIM set ground/water/swimming flags and wing/tail/tentacle fields, and public
`AnimationState` fields (idle, fly, roar, sniff, croak, roll-up, sit) are restarted at the clock-derived age. Entity
instances are per owner and layer, never shared (shared instances made one layer's pose leak into another). Interpolation
endpoints (`xo`, `oFlap`, `...O`) must come from the clock too, or backward seeks and tick boundaries jump; reset the
axolotl's model history before sampling. `offered(id)` decides which mob-specific choices the editors show. The access
transformer widens the fields it writes. Dev check `runHologramEntityAnimCheck` (`run-hologram-entity-anim/`) verifies
real model poses, backward seeks, tick boundaries and instance isolation; the mob matrix is in
`docs/hologram-entity-animations.md`. Not verified: GeckoLib/AzureLib mobs.

### Music-reactive holograms (`client/audio/*`, `hologram/HologramAudioMod`)

**Tap**: mixins on the sound engine feed streamed (`AnalysingAudioStream`) and static (`StaticSoundCache`) buffers into
`AudioTap`, a ring buffer per playing sound with its position and category (RECORDS = the Jukebox/Note Blocks slider, so
any mod playing through vanilla's sound engine in that category counts; MUSIC). Nothing is copied or analysed unless a
visible hologram or an open audio screen asked for that source this frame; `runHologramAudioCheck` asserts the idle path
(no analyse calls, no copy, capture idle).

**Analyser** (`AudioAnalyser`): 1024-point FFT, Hann window, 60 Hz hops -> level, bass, mid, high, plus a beat from
spectral flux with an adaptive threshold, all auto-gained to 0..1. **System audio** (`SystemAudio`): WASAPI loopback
(`WasapiCapture`) with OpenAL capture as the fallback. Off by default, per player in `config/foxstweaks-audio.json`
(device, on/off, listen at player or hologram); only the five levels ever exist, nothing is recorded or sent.
`devUseFake` swaps in a synthetic backend for checks.

**Modulation** (`HologramAudioMod`, evaluated by `HologramAudioEval`): `value += amount x signal` with attack/release
envelopes, an optional beat step and a clamp; several on one value add up. Effect modulations live on the
`HologramEffect`; root/group/layer modulations are timeline audio links. Presets (`HologramAudioMod.Preset`) only touch
the params they use, so applying one twice changes nothing; FLASH_BEAT and GLOW both drive brightness, so the later one
wins. Server caps `hologramAudioMaxPerValue` (4) and `hologramAudioMaxPerHologram` (64), enforced on editor and web saves.
Web protocol 4 adds the modulation fields and a `meter` message (20 Hz, levels only).

**Not covered**: mods that play sound outside vanilla's `SoundEngine` (WaterMedia/VLC, Simple Voice Chat); only system
audio hears them. A SPIN with both a trigger and an audio link keeps the trigger's clock.

**Dev check**: `build.bat runHologramAudioCheck` (game dir `run-hologram-audio/`, no extra mods): analyser bands on
generated tones, a 120 BPM click track, the live path through the fake backend, presets, NBT/web round trips, caps, the
Creator/Studio/panel/settings UI, server save, the over-cap refusal and the lost-device status with Retry. Controls are
laid out per frame, so a check can click a control only one frame after it appears. Rig has no audio device: a real
jukebox and real system audio are verified only by hand.

### 3D model layers (`hologram/model/*`, `hologram/HologramModelSettings`, `client/HologramModels`)

A model is an asset like a picture: uploaded as `AssetPayload.MODE_MODEL` (3), sanitised on the worker thread
(`AssetStore.prepareModel`), committed with metadata type `MODEL` (`frameCount` = animation count, no delays) and
refcounted by `AssetNetwork.imageUses`. The stored blob is **FTM1** (`ModelData`, pure Java, hard caps: 512 nodes,
65,536 vertices, 131,072 triangles, 8 textures at 2048 px / 8 MiB, 8 skins, 256 joints, 64 animations, 4096 channels,
120k keys, 600 s); the original file is never kept. Nothing under `hologram/model/` imports Minecraft, so
`build.bat modelSelfTest` runs the readers, round trip and posing in a bare JVM.

- **Model**: node tree (parents first, TRS with quaternion rotation), prims (pos/nrm/uv/16-bit idx, optional 4 joints +
  weights, texture index, colour, alphaMode, doubleSided), skins (joint nodes + column-major inverse binds), animations
  (channels that REPLACE the rest value of translation/rotation/scale; LINEAR or STEP; ONCE/LOOP/HOLD).
- **`GlbReader`**: glTF 2.0, `.glb` or self-contained `.gltf` (data URIs). Triangles only, bounds-checked accessors,
  CUBICSPLINE sampled to its values, textures re-encoded to PNG by `ModelTextures` (ImageIO, ≤ 2048). Ignored: morph
  targets, sparse accessors, cameras, lights, KHR extensions.
- **`BlockbenchReader`**: units are 1/16 block; a group node sits at `origin - parentOrigin`; cube geometry is
  `from/to - cube origin`; an unrotated, unanimated cube merges into its group node. Euler order is ZYX
  (`ModelPose.eulerZyx`); animation Euler is baked to quaternion keys (extra keys for turns over 60° per segment).
  Face corners: u runs along the viewer's right seen from outside; up TL = (from.x, from.z), down TL = (from.x, to.z).
  A vanilla block-model `.json` (no outliner) is rewritten to that shape by `adaptVanilla` and drawn untextured (its textures are
  resource names). Catmull-Rom/Bezier play as linear. **The Euler order and face orientation were derived, not compared with a real
  Blockbench render.**
- **`ModelPose`**: `evaluate(anim, time)` then `pose(node, prim, outPos, outNrm)` (rigid or skinned), `bounds`, `wrap`.
- **Layer**: `HologramModelSettings` (`h` height in blocks, 0 = native; `anim` name; `play`; `speed`; `start`) in the
  layer's string `props`, normalised in `HologramLayer`'s constructor. The server never loads the model, so
  `HologramSpec`'s reach box is a generous column of the drawn height. `HologramNetwork.MAX_MODEL_LAYERS` = 4.
- **Drawing**: `HologramModels.draw` keeps one runtime per asset hash (decoded model, `DynamicTexture`s, scratch arrays;
  8 kept, freed in `HologramAssets.clearMemory`), poses every prim on the CPU per frame and emits through the layer's
  own buffer (`entityCutout`/`NoCull`/`Translucent` per texture, white texture when untextured), so tint, glow and
  shader packs behave like other layers. Clock is `mediaClock`, so the timeline playhead scrubs animations. A faint
  box shows while the asset loads. Bottom-centred, scaled so the rest-pose height is `h`.
- **Editors**: `HologramAssetScreen` (3D tile, `HologramLayer.model`), `HologramAssetImport` (`.glb/.gltf/.bbmodel` or
  sniffed content, no image fitting), Creator `renderModelShape` (animation cycle, loop, speed, height). Web: `upload.ts`
  mode `model`, library tile, `ModelSection` in `inspector.tsx`; the library listing adds `animations` names once the
  game has decoded the model (first listing may be empty: reload). Web protocol 7. No face-camera option on models yet.
- **Dev check**: `build.bat runHologramModelCheck` (game dir `run-hologram-model/`, run on rig with `-Wait`): uploads a
  Blockbench cube and a skinned glb through the real asset channel, refuses garbage, checks stored metadata, client
  decode, 12 triangles drawn, and that the picture changes between two animation times. Report
  `run-hologram-model/foxstweaks-hologram-model-check.txt`. Uploads must be ≥ 1 s apart (server rate limit).
  Not verified: textured glb, skinned meshes in motion on screen, shader packs, GeckoLib-style rigs, large models.

### Search box assist (`client/search/*`, `mixin/EditBoxSearchAssistMixin`)

One mixin on vanilla `EditBox` (every mod's search field is one, or a subclass) instead of a patch per mod.
`SearchAssist.isSearchBox` decides: the box's message, hint or their translation keys, or its class name,
contain "search"/"filter"/"find" (`searchAssistExtraBoxes` adds names, `searchAssistSkipBoxes` removes them;
default skip: `ShaderPackScreen`, which has its own). Found by scanning ATM10 and TNP Limitless 8 jars: ~150
mods use `EditBox` near a "search" string (EMI, JEI, AE2/RS terminals, Create, Patchouli, Modonomicon, creative...).

- **Autocomplete**: after each change `SearchVocab.complete` sets `EditBox#setSuggestion` (grey text; vanilla
  only draws it with the cursor at the end). Tab takes it. A suggestion another mod set is never replaced
  (`foxstweaks$ours` remembers ours).
- **Did you mean**: `SearchVocab.correct` fixes each plain word of 4+ letters that is not a known word (and
  is not a still-growing prefix on the last word): same first letter, optimal-string-alignment distance 1
  (2 from 8 letters), most common candidate wins. Any search syntax (`@ # $ / " - |` ...) skips the whole
  query. Shown under the focused box; Tab takes it.
- **Autocorrect** (`searchAssistAutocorrect`): `@ModifyArg` on the `responder.accept` call inside
  `onValueChange` hands the listener the corrected text; the box keeps what was typed. Boxes that read
  `getValue()` themselves only get the suggestion. `getValue` is deliberately not hooked: mods write it back.
- **Vocabulary** (`SearchVocab`): item, entity, mob effect and mod display names in the current language,
  rebuilt when `Language.getInstance()` changes. It does not know mod-specific words (setting names, quest
  titles), so those boxes get fewer suggestions; a word absent from it is never "corrected" unless a known
  word is within the distance above.
- Targets the private `EditBox#onValueChange(String)`, `keyPressed`, `renderWidget` (Mojang names, checked
  with `javap`); `require = 0`, so an upstream rename drops the feature silently. Client section, toggle
  `searchAssist`.
- **Dev check**: `build.bat runSearchAssistCheck` (game dir `run-search/`, no extra mods, run it on rig). Needs no
  world: `client/dev/SearchAssistCheck` types into real `EditBox`es on the title screen and checks the listener text,
  suggestion, did-you-mean, Tab, search syntax, prefix words and a non-search box. Report
  `run-search/foxstweaks-search-check.txt` (21 checks, all PASS).
- **Real-mod checks**: `runSearchEmiCheck`, `runSearchJeiCheck`, `runSearchAe2Check` (`client/dev/SearchAssistModsCheck`, reflection
  only; game dirs `run-search-emi|jei|ae2`, drop the mod jars in each `mods/` first: EMI; JEI; AE2 + GuideME). They build a
  flat creative world, open the viewer (AE2: place an `ae2:terminal` part, open it), type `diamnod`, then `iron ing`, check
  the corrected text reaches the mod (EMI/JEI results, AE2 repo search string) and that Tab completes, and write
  screenshots + `foxstweaks-search-<mode>.txt`. All PASS (EMI, JEI, AE2). Screenshots in README come from these (`docs/search-assist/`).
- **Prefixes**: EMI, JEI (default prefix mode) and AE2 agree: `@` mod, `#` tag, `$` tooltip (JEI adds `%` tab, `^` colour, `&` id; AE2 `^` id). `SearchVocab.completePrefixed` completes after the prefix with that kind of thing only: `@` mod ids, `#` item tag paths (rebuilt when the tag count changes; empty on the title screen), `$` the most common name word. `correctMods` fixes `@` ids only. The mod checks type `@minec`, `#ingo`, `$diam` in a world (all complete). Other prefixes are left alone.
- **Gotchas**: EMI sets its own suggestion to `""`, so empty counts as unset. AE2's box has no "search" label: the default
  `searchAssistExtraBoxes` is `["MEStorageScreen"]`, matched against the screen's class chain (parents too). JEI's box shows the corrected
  filter text. An AE2 terminal with no network has zero results, so that check uses the repo search string instead.

### Sophisticated recipes in EMI (`client/compat/SbEmiFill`)

EMI's "+" (and its craft keybinds, both of which end in `EmiRecipeFiller`) refused Sophisticated
Backpacks' tier upgrades and dyeing with *"Current workstation does not support recipe"*:
Sophisticated Core shows those recipes **split**, one EMI recipe holding only the ingredients, another
only the results, so EMI's handler never sees a complete 3x3 craft.

`SbEmiFill.forFill` builds a plain `EmiCraftingRecipe` from the split halves and hands it to EMI
instead: `CraftingSpecEmiRecipe`'s `gridInputs` + `spec.getOutputStacks(variants)`, or
`GroupedCraftingEmiRecipe`'s `recipeHolder.value().getInputSlots()/getResultStacks()`. The replacement
is cached per EMI recipe (weak keys, so an EMI reload drops it).

- **Self-disabling:** only recipes from
  `net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.emi.` that still return
  `supportsRecipeTree() == false` are swapped. Once Sophisticated Core ships whole recipes nothing
  matches. Anything unexpected (renamed fields, a grid that isn't 9 slots, empty ingredients) leaves the
  recipe alone and warns once.
- The Sophisticated Core side is reached by reflection and by class-name prefix only; the EMI side is
  `compileOnly` (`emi_version`) and the two mixins (`SbEmiFillButtonMixin`, `SbEmiFillerMixin`) target
  `RecipeFillButtonWidget`/`EmiRecipeFiller` by string, `require = 0`. Toggle: `sbEmiFill`. Client.

### TrashSlot on slotless menus (`mixin/TrashSlotGuiHandlerMixin`)

Waystones' settings screens are `AbstractContainerScreen`s over a menu with no slots. TrashSlot still
draws its slot there, and GuiTween's `renderSlot` hook then calls `menu.getSlot(index)` on the empty
list and crashes. `TrashSlotGuiHandlerMixin` `@Inject`s `TrashSlotGuiHandler.onScreenDrawn` at `HEAD`
(cancellable, by string target, `require = 0`) and skips drawing when the open container menu has no
slots - a screen with no slots has nothing to trash. No config: it is three lines of crash guard, not a
feature. Client.

### GAG Temporal Pouch curio (`gag/TemporalPouchCurio`, `gag/TemporalPouchNetwork`, `client/TemporalPouchKeyHandler`)

The slot is pure data: `data/foxstweaks/curios/slots/temporal_pouch.json` and `.../curios/entities/temporal_pouch.json`
(both behind a `neoforge:mod_loaded` condition on `gag`, which Curios honours) plus the item tag
`data/curios/tags/item/temporal_pouch.json` holding `gag:time_sand_pouch` (`required: false`). Icon:
`textures/slot/temporal_pouch.png`, picked up by Curios' blocks-atlas `slot/` source. Slot name lang key:
`curios.identifier.temporal_pouch`.

GAG's `TemporalPouchItem` only gains time in `inventoryTick`, which Curios never calls, so
`TemporalPouchCurio` (an `ICurioItem` registered with `CuriosApi.registerCurio` in common setup, only when both
GAG and Curios are loaded) runs `ItemStack.inventoryTick` from `curioTick` every 20 ticks. GAG's own
anti-stacking check (zero the smaller of two pouches) only scans the main inventory, so the equipped pouch
skips ticking while another pouch is in the inventory.

The hotkey (`key.foxstweaks.use_temporal_pouch`, unbound) sends an empty `UsePouchPayload` when the client
is looking at a block. The server ray-traces itself (`player.pick` at `blockInteractionRange`, then
`canInteractWithBlock` and `mayInteract`), finds the equipped pouch with `findFirstCurio`, and calls
`useOn` with a `UseOnContext` built on the curio stack: `TemporalPouchItem.useOn` takes its stack from
`ctx.getItemInHand()`, so no hand swapping is needed. Curios types live only in `TemporalPouchCurio`;
`TemporalPouchNetwork` reaches it behind `isAvailable()`. Curios is a `compileOnly` dependency; GAG is not
needed at compile time (looked up by id). Against GAG 5.2.0 and Curios 9.5.1. Toggle: `temporalPouchCurio`
(filling + hotkey; the slot itself is data and stays).

### Ars Nouveau source from simulated growth and kills (`ars/compat/ArsSourcelinks`, three mixins)

Ars' Agronomic and Vitalic sourcelinks listen for `CropGrowEvent.Post` and `LivingDeathEvent`. Crops in
pots never grow in the world, and a simulated kill has no death, so those listeners never fire. Each
mixin reports its event through `SourcelinkEventQueue.addManaEvent`, the call Ars' own listeners make,
with the same amounts (crop 20, magic plant 45, sapling 50, magic sapling 100, death 200). That keeps the
15-block range, the "is it full" check and the particle as Ars' own. The queue gives each event to the
**first** sourcelink of that type in range, so a test with two sourcelinks near one source can look like
one of them gets nothing.

- **`BotanyPotsCropMixin`** (`botanyPotsSource`) hooks `BasicCrop#onHarvest`. That is the only `Crop`
  implementation in the pack (`BlockDerivedCrop` extends it without overriding), and every pot mod
  (Botany Pots, Tiers, Power Pots, Dimensional Pots, Botany Gardens) calls it, so one hook covers them
  all. `BotanyPotContext` has no position. Botany Pots' own `BlockEntityContext` gives the pot, and any
  other context (Botany Gardens' `CellContext`) is asked for its loot `ORIGIN`. Botany Gardens calls
  `onHarvest` once per loot roll, so its yield upgrades also raise the source.
- **`ApotSpawnerKillMixin`** (`apotspawnerSource`) hooks the `HEAD` of `SpawnerEventHandler#generateDeathRewards`
  (once per simulated kill). The last parameter is a private type, so the arguments are taken with
  MixinExtras `@Local(argsOnly = true)`. The caller catches `RuntimeException` and logs
  `Virtual kill failed for spawner at ...`, so a throw in our code shows up there.
- **HNN and its addon machines** all go through `ars/compat/HnnSimulations#finished(machine, modelStack)`.
  Every one of them updates the data model stack once per finished simulation, so each mixin grabs that
  stack and nothing else. The helper reads the mobs from it: a plain HNN model holds one, and ExtraHNN's
  merged model holds up to four in its `extrahnn:extra_data_model` component, read by registry id so
  ExtraHNN is not a compile dependency. Only `EntityDataModel` counts, since a block data model is not a
  kill, and each mob is still checked against Ars' vitalic death blacklist.
  - `SimChamberMixin` (`hostileNetworksSource`): `@ModifyArg` on `setIters` in HNN's
    `SimChamberTileEntity#serverTick`, which runs in both inference and training mode.
  - `ExtraHnnSimMixin` / `ExtraHnnModelingMixin` (`extraHnnSource`): the same, in ExtraHNN's
    `UltimateSimChamberTileEntity#setResult` and `SimulationModelingTileEntity#result`. The target is
    only the name `setIters`, so it matches both `DataModelItem`'s and `ExtraDataModelItem`'s. The
    Ultimate chamber's version multiplier (more drops per run) does not add deaths, but a 4-mob merged
    model counts 4, which matches the 4 prediction matrices it uses up.
  - `HniSimChamberMixin` (`hostileNeuralIndustrializationSource`): `@ModifyExpressionValue` on
    `getUpdatedModel` in `onCraft` of both Hostile Neural Industrialization machines, the electric one
    and the multiblock. `setIters` itself lives in HNI's interface default method, which a mixin cannot
    hook, and the return value is a plain `ItemStack`, so no Modern Industrialization type is needed.

Ars types appear only in `ArsSourcelinks`, and HNN types only in `HnnSimulations` and HNN-only mixins.
The mixins target by string and do nothing until `ArsSourcelinks.loaded()` is true. Compile-only:
`ars_nouveau_version`, `botanypots_version` (Modrinth version ids) and `hnn_version` (Shadows' maven).
A wrong target **method** name crashes the game at load even with `required: false`, because
`defaultRequire` is 1 (that happened with `SimulationModelingTileEntity`, whose method is `result`, not
`serverTick`). A missing target **class** only logs a warning.

**Verified on a headless dev server** (`runServer` with RCON, blocks set up by NBT): 5 wheat harvests in
a hopper pot gave 100 source, 2 in a garden cell gave 40, 5 HNN simulations gave 1000, 41 ApotSpawner
kills gave 8200, 5 ExtraHNN Ultimate runs of a 4-mob model gave 4000, 2 Simulation Modeling runs gave
400, and 4 HNI electric chamber recipes gave 800. The HNI multiblock was only checked for the mixin
being applied, not run. Tested against Ars 5.13.1, Botany Pots 21.1.44, HNN 6.5.1, ApotSpawner 1.2.0,
ExtraHNN 2.2.5 and HNI 1.0.16. Re-check the hooked member names when any of these update.

### ApotSpawner XP fluid (`mixin/ApotSpawnerXpFluidMixin`)

When Create Enchantment Industry is installed, ApotSpawner's `ExperienceFluidCompat#exportedFluid()` switches
the spawner's fluid output to CEI's `create_enchantment_industry:experience` (1 mB per XP) instead of its own
`apotspawner:liquid_experience` (20 mB per XP). CEI's fluid is not in `c:experience`, so Just Dire Things'
Experience Holder (and any other tank that checks `c:experience`) refuses it. Tagging it with KubeJS would not
fix that: the holder always counts 20 mB as 1 XP, so it would keep only 1/20 of the XP. The mixin makes
`exportedFluid()` return ApotSpawner's own fluid. `millibucketsPerXp()` derives the rate from the same method,
so it becomes 20 too. The spawner stores XP as points, not fluid, so switching loses nothing. The catch: CEI
machines can no longer take XP straight from a spawner. Toggle: `apotspawnerOwnXpFluid` (default on).
Verified on the dev server by draining the spawner's fluid capability straight into the holder's. With the
toggle on, 1000 mB of `apotspawner:liquid_experience` became 50 XP in the holder and 50 XP left the spawner.
With it off, CEI's fluid was offered and 0 was accepted. Against ApotSpawner 1.2.0, CEI 2.4.2 and Just Dire
Things 1.5.7.

### ApotSpawner missing items (`mixin/ApotSpawnerMissingItemsMixin`)

ApotSpawner keeps each spawner's stored loot (`NeoForgeData.apotspawner.items`) and drop statistics
(`...statistics.items`) as raw NBT, and `SpawnerStorage` re-parses every entry on each read through
`root()`. Its item handler reads constantly while a hopper or pipe is attached. An entry whose item came
from a removed mod never parses and is never dropped, so it logs `Tried to load invalid item` on every
read. On a live server that was ~1,300 lines a minute from 13 spawners. The mixin hooks `root()`'s
`RETURN` and, the first time each spawner is read this session (a weak set of block entities), removes
every entry that fails `ItemStack.parse`. `root()` returns the live tag, so the removal is in place and
`setChanged()` saves it. Toggle: `apotspawnerDropMissingItems` (default on).
Verified on the dev server with a spawner holding `pointblank:gunmetal_nugget` plus diamonds, a hopper
underneath. With the toggle on, each bad entry logged once and was gone, and the diamonds went on into the
chest. With it off, 167 errors in 10 s and the entry stayed. Against ApotSpawner 1.2.0.

### Client vs server

- The **auto-solve button** is client-only. It invents no packet: it calls
  `AbilityResearchScreen#addLink`/`removeLink`, which send Relics' own `PacketManageLink`. The server
  applies those without validating correctness, so the button works against any Relics server.
- **Solve-on-pickup requires the mod server-side.** `ResearchData.complete()` writes a
  server-authoritative data attachment and refuses anything that is not a `ServerPlayer`.
  Singleplayer runs an integrated server in-process, so a client-only install still works there.
- The mod adds **no blocks and no entities**. It does add two craftable items with recipes (the Mob & Chunk
  Loader dummy and the Hologram Wand) and ships some data: the
  `foxstweaks:overworld_copy` dimension (datapack, behind the `overworldCopy` load condition), the two
  Curios slot definitions (behind `neoforge:mod_loaded`), and the stale-mixin guard. Config is `COMMON`
  plus a `SERVER` file (the portal frame, instant/flat/dye keys). Every network
  payload is registered `optional()` - `NearbyItemsPayload` (printer/TACZ counts), `ToggleEntryPayload`,
  `UsePouchPayload`, `AutoLevelPayload`, `ShrinkPayload`, `PortalColorsPayload` - and each network class
  is only reached behind `isAvailable()`/`hasChannel()` checks. Keep it that way: optional payloads are
  what let a client without this mod join, and a client-only install join a server without it. (The
  rarity patches rewrite data as it loads; they add no content and need nothing on the client.)

## Testing

`/foxstweaks testhotbar [sockets]` (op, `0–16`, default 4) fills hotbar slots 1–8 with relics rolled
at the highest loot rarity and filled with random `PERFECT`-purity gems. It also decorates a full
vanilla diamond kit (sword + full armor) the same way and equips it, in slot 0 and the armor slots -
a control group that is guaranteed a `LootCategory`, so it keeps getting affixes even on a Relics
install that doesn't (see below).

It doubles as a diagnostic: each line reports the `LootCategory` the item resolved to. Apotheosis
refuses to affix or socket anything mapping to no category, so `no loot category` lines explain
relics not receiving affixes - if the vanilla gear affixes fine alongside them, Apotheosis itself is
working and the gap is specific to Relics' items (see `apothic_compats` in
[Local setup](#local-setup)).

### Stale mixin guard

MixinSquared (the one bundled dependency) finds `StaleMixinCanceller` through
`META-INF/services` and asks it about each mixin **when the target class loads**, not at startup -
so the `Disabled mixin` line appears late (JEI's bookmark overlay loads on world join). The guard only
cancels when a target class exists, and a method/field owner in the same three-segment package root
as a target (`mezz/jei/common`, `appeng/api/...`) is missing from every class loader (Mixin's lookup
only sees the game layer; jar-in-jar libraries live elsewhere) - Mixin resolves every such owner while preparing,
so those mixins would crash anyway. It must touch nothing but Mixin and ASM: it runs before
Minecraft classes exist.

Dev runs load this mod from `build/classes`, so jar-in-jar is skipped: drop
`mixinsquared-neoforge-<version>.jar` into `run/mods/` to exercise the guard there.

## Known upstream bugs (not ours — do not "fix" them here)

- **Relics, creative inventory.** Solving fails with *"Working with a relic in this inventory is not
  possible"*. `DescriptionHandler` records the hovered slot as an index into whichever menu is open,
  and creative's `ItemPickerMenu` puts the hotbar at indices 45–53, while the server validates
  against `InventoryMenu` (0–45). Index 45 hits the offhand; 46–53 are out of range. Dragging the
  stars by hand fails identically.
- **apothic_compats, load order.** ~11 `affix_loot_entries` for its own curio items fail to parse
  with *"Items without a valid loot category are not permitted"*. Its curio `LootCategory`
  predicates are tag-based, and Apotheosis parses reloadable registries before item tags are bound.
  This is parse-time only; runtime affixing is unaffected.
- **Curios, screen tooltips.** While the Curios inventory screen (`CuriosScreen`) is open, hovering a
  slot shows neither our affix panel nor any of Apotheosis' own tooltip additions. `CuriosScreen`'s
  override of `renderTooltip` calls the `GuiGraphics#renderTooltip` overload that takes no
  `ItemStack`, so the hovered stack never reaches `RenderTooltipEvent.Pre#getItemStack()` - exactly
  the check `AffixTooltipHandler` bails out on. Tracked upstream as
  [TheIllusiveC4/Curios#536](https://github.com/TheIllusiveC4/Curios/issues/536), with a fix pending
  in [#625](https://github.com/TheIllusiveC4/Curios/pull/625). We work around the half of this we can
  reach - see [Recovering the hovered item on a broken screen](#recovering-the-hovered-item-on-a-broken-screen).

## Conventions

- Match the surrounding style: blank line after a guard clause, `var` where the type is obvious.
- Comments explain **why**, especially where the code looks odd — the re-entrancy flag, the
  `applyModifierTooltips` choice, the two AT lines. Those exist because of a specific trap; a future
  reader will otherwise "simplify" them back into a bug.
- The panel and the auto-solve button reuse Relics' and Apotheosis' own textures and helpers rather
  than reimplementing them. Prefer that over lookalike art or copied formatting.

### AE2 EMI quick fill (`mixin/Ae2EmiQuickFillMixin`, `mixin/EmiCraftablesScanMixin`, `client/compat/EmiCraftableScan`)

Replaces the "AE2: EMI Quick Fill" mod (`ae2_emi_quick_fill` 1.0.0), which froze the client whenever an
ME terminal was open. AE2's `AbstractRecipeHandler#canCraft` runs its own transfer check only for EMI's
`FILL_BUTTON` context; EMI's craft binds (shift-click etc.) use `CRAFTABLE`, which falls to EMI's plain
inventory check, so they can't fill a pattern encoder. That mod redirected `CRAFTABLE` → `FILL_BUTTON`
everywhere, including `EmiPlayerInventory#getCraftables` - which asks `canCraft` for every recipe that
takes any stack in the inventory (with AE2 exposing the network: most of the pack), and reruns on every
inventory change, i.e. every ME repo update, while the craftables sidebar is visible. Each call became a
full AE2 transfer. Here the swap is the same, but `EmiCraftablesScanMixin` (`@WrapMethod` on
`getCraftables`) flags the rebuild and the swap is skipped inside it. Craft binds still go through
`getPreferredRecipe`/`performFill`, which get AE2's check. Both target by string; EMI is `compileOnly`
only for the `EmiCraftContext.Type` enum. Against AE2 19.2.17 / EMI 1.1.24. Client. Toggle: `ae2EmiQuickFill`.

### Performance patches (`mixin/TransmogInventoryMixin`, `ArchitecturyEventInvokeMixin`, `CuriosCacheHandlerMixin`, `GpuShiftClassifierMixin`)

Four patches found by spark-profiling a large pack (TNP Limitless 8). Each targets its mod by string (or, for
Transmog, by MixinSquared `@TargetHandler` with `require = 0`), so a pack without that mod never applies it, and
each reads its toggle through `Config.enabled(...)`, which returns false (original path) while the COMMON config
is not loaded yet - Architectury events fire during mod construction, before it is.

- **Transmog** (`transmogInventoryFastPath`, client): Transmog's `getInventory` hook copies all 41 slots into a new
  `Inventory` per call while rendering; ~10% of frame time. Skipped when no stack has `transmog:appearance_item`,
  since the copy would then equal the original. Against Transmog 1.6.0.
- **Architectury** (`architecturyFastEvents`, both sides): `EventFactory.invokeMethod` unreflected and
  `invokeWithArguments`-ed per listener per fire. Replaced by `Method#invoke`, cause unwrapped from
  `InvocationTargetException` and sneaky-rethrown so callers see the same exception as before; an
  `IllegalAccessException` falls back to the original. Against Architectury 13.0.11.
- **Curios Performance Fix** (`curiosCacheHandlerFix`, client): `getFromHandler` rebuilds its snapshot whenever the
  handler instance differs, and Curios makes a new `CurioInventoryCapability` per lookup. The handler argument is
  swapped for one remembered per wearer for up to 1 s (the cache's own `SAFETY_REFRESH_NANOS`). Against CPF 1.0.4.
- **GPUShift** (`gpushiftClassifierCache`, client): `ParticleCategoryClassifier.classify` result cached per particle
  class in a `ClassValue`; calls with a null `ModPresence` are not cached. Against GPUShift 1.2.8-bugfix.2.

Considered and left out: skipping Iris' redundant `glBindFramebuffer` calls needs the current binding, and a
`glGet` for it stalls under NVIDIA threaded optimization; AsyncParticles' O(n) `anIterator.remove` under Smart
Particles' limit stops mattering once `particleLimit` is raised.

### Lootr vs Modern Chickens (`mixin/ChickensRadiationLootMixin`, `mixin/LootRollLoggerMixin`, `debug/LootRollLog`)

Structure chests were left as plain chests full of loot instead of becoming Lootr chests. Lootr converts only
while a container's loot table is still attached; vanilla rolls the table the first time anything calls
`getItem`. Modern Chickens' Mekanism radiation compat (`MekanismRadiationCompat.onLevelTick`, once a second, every
container in loaded chunks) read them through `RadioactiveContentHelper.hasRadioactiveItems` seconds after
generation. The patch answers false for a `RandomizableContainer` whose loot table is unrolled (it is empty, so
nothing radioactive). Verified on TNP Limitless 8: 3 fresh villages, 57 Lootr containers, 0 pre-rolled.
Toggle: `chickensRadiationKeepLoot`. Against Modern Chickens 2.7.8.

`debugLogLootRolls` (default 0) logs a stack trace for the first N loot rolls of any `RandomizableContainer`,
with the player (if any) and level type - the tool that found this. State lives in `LootRollLog` because an
interface mixin may not declare fields (Mixin rejects it with `InvalidInterfaceMixinException`).

### Taverns & Adventurers rumor locate (`mixin/AdventurersGuildRumorLocateMixin`)

`RumorService.locate(ServerLevel, List, BlockPos)` (every rumor, dungeon and map path ends there) calls
`ChunkGenerator#findNearestMapStructure` on the server thread; DrunkGamblers crashed three times on 2026-11-26 with the
watchdog parked in `StructureCheck.tryLoadFromStorage` / ReTerraForged `CellSampler`. The mixin `@WrapOperation`s that one
call: one daemon worker thread runs it, the tick waits up to `tavernsLocateWaitMs` (4000), a timeout returns null (the mod
then tries another rumor) and a search still running makes later ones return null at once. The mod is closed source:
decompiled with `javap -c` from 0.4.5. `require = 0`, so an update that moves the call just drops the fix.

**The tick must not just block on the worker.** The search loads chunks, and `ServerChunkCache#getChunk` called off-thread
queues the load onto the chunk cache's main-thread executor and joins it, so a blocked tick deadlocks it (proven: 60 s with a
plain `Future.get`). `managedBlock` alone is not enough either: the server polls the chunk cache's queue only while the tick
still has time (`haveTime`), so the wait loop also calls `level.getChunkSource().pollTask()`.

Dev check: `build.bat runRumorCheck` (headless, game dir `run-rumor/`, gitignored; put the Taverns jar in `run-rumor/mods` and
edit its `neoforge.mods.toml` range to `[21.1.200,)` because the dev NeoForge is older than the jar wants; `eula.txt`,
`max-tick-time=-1` in `server.properties`). `dev/RumorLocateCheck` calls `locate` by reflection: worker path finds a village
(~0.8 s vs 0.6 s direct), a 1 ms wait gives up in 2 ms, a follow-up is refused at once, searches work again once the stuck one
ends, wait 0 uses the original call. Report `run-rumor/foxstweaks-rumor-check.txt`, all PASS. Not verified: the real hang
(chunk IO that never finishes) - it could not be reproduced here, so whether 4 s is the right wait on the live pack is open.
