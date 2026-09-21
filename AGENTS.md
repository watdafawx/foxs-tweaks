# AGENTS.md

Guidance for AI agents working in this repository.

## What this is

A NeoForge **1.21.1** mod (`foxstweaks`, "Fox's Tweaks") that is really five unrelated
quality-of-life features sharing a jar:

1. **Relics integration** — auto-completes the constellation "star puzzle" research, both on pickup
   and via a button on the research screen.
2. **Apotheosis integration** — a second tooltip panel, shown while a key is held, listing an item's
   affixes, attributes and gem sockets without touching the main tooltip.
3. **Apotheosis rarity compat** — server-side data patches that make third-party affix packs work
   with Apothic Ascension's rarities, and Ragnarok's gun affixes work with Ancient Reforging.
4. **Gun damage scaling** — server-side; guns of Apothic Ascension rarities deal more damage.
5. **EZActions icon picker cache** — client-side mixin that makes its icon picker open fast.

**Every parent mod is optional.** The mod must load and behave correctly with either, both, or
neither installed. This is the single most important invariant in the codebase — see
[Optional dependencies](#optional-dependencies).

## Building

```bat
build.bat build
```

Do **not** call `gradlew` directly unless you know a JDK is on `PATH`. `build.bat` points
`JAVA_HOME` at a portable JDK in `toolchain/` and then delegates to `gradlew`.

Two environment facts that will otherwise waste your time:

- **This machine has no system JDK**, only the JREs bundled with the Minecraft launcher. A JRE has
  no `javax.tools` compiler, so NeoForm cannot recompile Minecraft and the build dies with a
  confusing `JavaCompiler is null` NPE. Hence `toolchain/`.
- **The Maven hosts publish AAAA records but IPv6 does not route here.** `gradle.properties` sets
  `systemProp.java.net.preferIPv4Stack=true`; without it dependency resolution stalls until it
  times out and falls back.

`libs/` and `toolchain/` are gitignored and absent from a fresh clone — see [Local setup](#local-setup).

## Local setup

**Compiling needs nothing manual.** Every `compileOnly` dependency in `build.gradle` (Relics, Curios,
OctoLib, Apotheosis, Placebo, ApothicAttributes, Architectury) resolves from a real Maven repository:
Shadows-of-Fire's own maven for the Apotheosis family, TheIllusiveC4's own maven for Curios,
Architectury's own maven, and Modrinth's Maven mirror (`https://api.modrinth.com/maven`, group
`maven.modrinth`, artifact = Modrinth slug, version = the Modrinth *version id*, not the version
number) for Relics and OctoLib, which have no maven of their own. `build.bat build` downloads all of
it on first run, same as any other Gradle dependency. NeoForge mod jars for 1.21.1 ship in Mojang
mappings, which is why a production jar works as a compile dependency with no remapping.

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
- **All Apotheosis references are confined to `client/compat/ApothicTooltip` and
  `compat/ApothicTestGear`.** Keep it that way; touching an Apotheosis class from anywhere that gets
  loaded eagerly will throw `NoClassDefFoundError` on a pack without it.

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

### Gun damage scaling (`apotheosis/GunDamageHandler`)

A TACZ gun's damage is fixed by its gun pack and never grows with rarity; Ascension's mobs are tuned
for melee gear. `GunDamageHandler` multiplies `LivingIncomingDamageEvent` amounts when the source is a
player and the damage type is in `tacz:bullets` (TACZ's bullets plus Ragnarok's fire/ice) or Ragnarok's
`bugfix/armor_piercing_parts` (TACZ splits one hit into normal + armor-ignoring events; miss the second
and half the hit is unscaled - the tag also holds a mob type, hence the player check). The multiplier
uses the *held* stack's Ascension rarity and `RarityPatcher.CURVE`, so 1x below Legendary and
`gunDamageMaxMultiplier` at Apotheotic. The Apotheosis type is confined to `compat/ApothicRarityLookup`.
The default max (20x) is a guess, not measured against Ascension's mobs.

### Point Blank printer storage (`mixin/PointBlankInventoryUtilsMixin`, `mixin/PrinterBlockEntityMixin`, `pointblank/PrinterStorage`)

Point Blank (closed source, All Rights Reserved) crafts server-side in `PrinterBlockEntity#createCraftingItem`
when the print finishes: `PointBlankRecipe#canBeCrafted` -> `InventoryUtils#hasIngredient`, then
`removeIngredients` -> `InventoryUtils#removeItem`. Both helpers take only a `Player` and read
`getInventory().items`. `PrinterBlockEntityMixin` marks the span of `createCraftingItem` (giving
`PrinterStorage` the printer's position); `PointBlankInventoryUtilsMixin` then tops up `hasIngredient`
and drains the shortfall in `removeItem` from any `Capabilities.ItemHandler.BLOCK` within range (the
same generic scan as Ars Nouveau's `ScribesTile#takeNearby`) - plus AE2, see below. Outside that span
the client is a separate case, below. Both mixins target by string; the
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

AE2 is read through `pointblank/compat/Ae2PrinterStorage` (loaded only when `ae2` is); `PrinterStorage`
prefers ME storage over the item handler at the same block (no double count), and sums plain inventories
but takes only the *best* ME source, so it can under-count split stock, never over-count. Why AE2 needs
its own path, and how to reuse it for other patches: see the next section.

Oddity found, not ours and not touched: `PointBlankRecipe#canBeCrafted` is `anyMatch(hasIngredient)`,
which reads as "craftable if any one ingredient is present".

### Reading AE2 storage from a patch (AE2 interface capability) - reusable

Verified against AE2 19.2.17 by reading its jar (`appeng.init.InitCapabilityProviders`,
`appeng.helpers.InterfaceLogic`). Reuse this for any patch that wants "items from the player's network".

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
  `ModList.get().isLoaded("ae2")` (`Ae2PrinterStorage` is the model), and expose it through an AE2-free
  interface (`PrinterStorage.Source`). Compile-only via `ae2_version` (a Modrinth version id).
- **Untested in-game as of writing.** The AE2 path was built from the jar, not observed working.

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

### Client vs server

- The **auto-solve button** is client-only. It invents no packet: it calls
  `AbilityResearchScreen#addLink`/`removeLink`, which send Relics' own `PacketManageLink`. The server
  applies those without validating correctness, so the button works against any Relics server.
- **Solve-on-pickup requires the mod server-side.** `ResearchData.complete()` writes a
  server-authoritative data attachment and refuses anything that is not a `ServerPlayer`.
  Singleplayer runs an integrated server in-process, so a client-only install still works there.
- The mod registers **no registries or datapack content** — only a `COMMON` config, and one **optional**
  payload (`NearbyItemsPayload`, only when Point Blank is loaded). Keep it that way: optional payloads
  are what let a client without this mod join, and a client-only install join a server without it. (The
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
