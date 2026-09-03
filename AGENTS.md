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

`libs/` holds the jars compiled against. They are **third-party mods and are deliberately not
committed** (Relics is licensed All Rights Reserved). Populate it by copying from a modpack
instance that has them:

- `relics-*.jar`, `curios-*.jar`, `OctoLib-*.jar` — for the Relics half
- `Apotheosis-*.jar`, `Placebo-*.jar`, `ApothicAttributes-*.jar` — for the Apotheosis half

All are `compileOnly`. NeoForge mod jars for 1.21.1 ship in Mojang mappings, which is why a
production jar works as a compile dependency with no remapping.

To run a dev client, drop those same jars (plus `architectury-*.jar`, which OctoLib requires) into
`run/mods/` and run `build.bat runClient`.

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

`/foxstweaks testhotbar [sockets]` (op, `0–16`, default 4) fills the hotbar with relics rolled at the
highest loot rarity and filled with random `PERFECT`-purity gems.

It doubles as a diagnostic: each line reports the `LootCategory` the relic resolved to. Apotheosis
refuses to affix or socket anything mapping to no category, so `no loot category` lines explain
relics not receiving affixes.

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

## Conventions

- Match the surrounding style: blank line after a guard clause, `var` where the type is obvious.
- Comments explain **why**, especially where the code looks odd — the re-entrancy flag, the
  `applyModifierTooltips` choice, the two AT lines. Those exist because of a specific trap; a future
  reader will otherwise "simplify" them back into a bug.
- The panel and the auto-solve button reuse Relics' and Apotheosis' own textures and helpers rather
  than reimplementing them. Prefer that over lookalike art or copied formatting.
