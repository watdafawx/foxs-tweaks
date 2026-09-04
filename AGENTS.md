# AGENTS.md

Guidance for AI agents working in this repository.

## What this is

A NeoForge **1.21.1** mod (`foxstweaks`, "Fox's Tweaks") that is really two unrelated
quality-of-life features sharing a jar:

1. **Relics integration** — auto-completes the constellation "star puzzle" research, both on pickup
   and via a button on the research screen.
2. **Apotheosis integration** — a second tooltip panel, shown while a key is held, listing an item's
   affixes, attributes and gem sockets without touching the main tooltip.

**Both parent mods are optional.** The mod must load and behave correctly with either, both, or
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

### Client vs server

- The **auto-solve button** is client-only. It invents no packet: it calls
  `AbilityResearchScreen#addLink`/`removeLink`, which send Relics' own `PacketManageLink`. The server
  applies those without validating correctness, so the button works against any Relics server.
- **Solve-on-pickup requires the mod server-side.** `ResearchData.complete()` writes a
  server-authoritative data attachment and refuses anything that is not a `ServerPlayer`.
  Singleplayer runs an integrated server in-process, so a client-only install still works there.
- The mod registers **no network payloads, registries or datapack content** — only a `COMMON` config.
  Keep it that way: it is what lets a client-only install join a server without this mod.

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
