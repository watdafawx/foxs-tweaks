# AGENTS.md

Guidance for AI agents working in this repository.

## What this is

A NeoForge **1.21.1** mod (`foxstweaks`, "Fox's Tweaks") that is really nine unrelated
quality-of-life features sharing a jar:

1. **Relics integration** — auto-completes the constellation "star puzzle" research, both on pickup
   and via a button on the research screen.
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
- **All Apotheosis references are confined to a handful of `compat`/`client/compat` classes** —
  `client/compat/ApothicTooltip`, `compat/ApothicTestGear`, `compat/ApothicRarityLookup`,
  `compat/ApothicAffixToggle` and `client/compat/AffixToggleScreen`. Keep it that way; touching an
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

### Per-affix toggles (`compat/ApothicAffixToggle`, `client/compat/AffixToggleScreen`)

`key.foxstweaks.toggle_affixes` (unbound by default) opens the held item's own tooltip as a
clickable overlay — hover an affix line and click to disable or re-enable it. Any item, not just
the few Apotheosis itself ships a toggle for (its own mining-radius affix, etc). There is no
separate list to keep in sync with the tooltip (Stoneforming's icon row included: that line is a
`TooltipComponent`, not text, so a string-matched highlight against a custom menu could never find
it).

Most affixes (Vein Mining, Sculk Affinity, the lot) are Apotheosis' own behaviour and never pass
through this mod at all, so a cosmetic "ignore this affix" flag would do nothing for them. The only
way to actually stop one from firing is to remove it from Apotheosis' own `ItemAffixes` data
component (`Apoth.Components.AFFIXES`) — `ApothicAffixToggle.setEnabled` does exactly that, via the
public `ItemAffixes.Builder`/`AffixHelper.setAffixes` API, no mixin needed. The removed id and level
are kept in `FoxsTweaksComponents.DISABLED_AFFIXES` (a component of our own, holding no Apotheosis
type) so re-enabling restores the exact level instead of re-rolling it, and so the affix tooltip
panel (`ApothicTooltip`) can still list a disabled affix, struck through — `streamAffixes` alone
would never see it once removed.

This is authoritative item state, not rendering, so the overlay mutates the client stack this frame
(so the tooltip rebuilds immediately) and sends `ToggleAffixPayload` (a plain id + bool, no
Apotheosis type) to the server, which does the actual removal/restoration against the sender's
main-hand stack. `affixToggleGui` gates both sides — the server needs it for a toggle to take
effect, the client needs it for the key and overlay to exist. `AffixToggleNetwork` is registered
unconditionally like `storage.NearbyItemsNetwork`; its handler lambda is the only thing that
reaches `ApothicAffixToggle`, guarded by the same `ModList.isLoaded("apotheosis")` check used
everywhere else.

A disabled affix is invisible everywhere by default once removed from `ItemAffixes` — including
Apotheosis' own normal tooltip, which reads that component directly and has no idea foxstweaks ever
touched it. `AffixTooltipHandler`'s `RenderTooltipEvent.GatherComponents` hook (priority `LOWEST`,
so after Apotheosis has swapped its marker lines for components) puts a "❌ " line back **where the
affix used to be**: `ApothicTooltip#insertDisabledLines` builds the tooltip of a copy with the
disabled affixes restored (the "ghost", cached on `ItemStack.hashItemAndComponents`), finds each
affix's line in it, and inserts after the nearest line above it that also exists in the real
tooltip. That works for attribute-block lines ("+3.25 Luck") too, without hardcoding Apotheosis'
ordering. Anchors that are Apotheosis marker text in the ghost (`APOTH_STONEFORMING_MARKER`,
`APOTH_SOCKET_MARKER`) are matched against the component that replaced them.

`AffixInstance#getName(true)` is the affix's plain name ("Great Fortune" / "Infernal");
`getName(false)` is the possessive fragment Apotheosis stitches into the item's own display name
("of Great Fortune"). Never use either as a tooltip match candidate — those fragments live inside
the item title, so `contains()` lights up the name line instead of the affix. Match the live
description, an `AttributeProvidingAffix` modifier line ("+7.75 Fire Damage"), or our ❌ line.
Skip index 0 (the title) in `bindAffixes` / `highlightLine` for the same reason.

The overlay is a `Screen` that draws nothing of its own except a title/hint and a hover outline:
`renderBackground` is empty (vanilla menu blur would otherwise sit on top of the tooltip), and
`render` calls `graphics.renderTooltip` on the live main-hand stack. Hit-testing walks the same
`GatherComponents` list the tooltip just drew, matching text lines by description / attribute
modifier / ❌ body, and Stoneforming by `StoneformingComponent` rather than the
`APOTH_STONEFORMING_MARKER` sentinel. Clicking a non-affix line is a no-op; Escape still closes.

The hovered line's ▶ is applied by **element index** (`AffixTooltipHandler.highlightIndex`), not by
matching text: the overlay measures with the index at -1, then draws the same stack, so both gathers
yield the same list. A text line is restyled; a component line (Stoneforming's icon row) is wrapped in
`client/HighlightedTooltipComponent`, which draws the ▶ and delegates to the wrapped component's own
renderer. It holds only a vanilla `TooltipComponent`, so it's safe to register unconditionally.

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
