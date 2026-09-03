# Fox's Tweaks

Quality-of-life tweaks for gear mods on NeoForge 1.21.1, built as **independent optional
integrations** — install whichever parent mods you use and the rest stays dormant. Neither parent
mod is required.

Built against NeoForge `21.1.248`, Minecraft `1.21.1`, Relics `0.12.8`, Apotheosis `8.7.0`.

## Relics — no more star puzzle

**Solves relics as you get them** (on by default). Any relic you pick up, or are already carrying,
has its research completed for every ability. Set `autoSolveOnPickup = false` in
`config/foxstweaks-common.toml` to turn this off and solve by hand instead.

This only clears the *research* gate. Ability unlocks still cost relic levels and upgrade points
exactly as they normally do; no progression is skipped.

**An auto-solve button**, for when that setting is off. A second shelf appears directly below the
hint button, carrying a purple star instead of a light bulb. Clicking it draws the entire correct
constellation at once: wrong lines are erased first, then every missing line is connected, and
Relics itself finishes the research on the last one — same particles, sounds and completion jingle
as solving it manually.

### How it finds the answer

It does not ship a table of solutions. A relic declares its constellation in its own ability
template, which is part of the item definition and therefore loaded on the client as well as the
server — that is how Relics' hint button already knows which line to reveal.

`ResearchSolver` reads that template through the public `IRelicItem` API:

```text
IRelicItem -> RelicData -> AbilitiesData -> AbilityData -> AbilityTemplate -> ResearchTemplate.getLinks()
```

Because it goes through the interface rather than any particular relic, **every relic is handled the
same way** — the ones Relics ships, ones added by other addons, and ones added by future updates.

## Apotheosis — affixes and gem sockets on demand

Hold **Left Ctrl** (rebindable: Options → Controls → *Fox's Tweaks*) over any item and a second
tooltip appears beside the main one, listing its affixes and gem sockets. The main tooltip is never
touched.

This exists because Apotheosis hides affix-granted attribute lines from the normal tooltip (via
`GatherSkippedAttributeTooltipsEvent`) since it renders its own affix block instead — and on a relic
that block is easy to miss, while Relics has already claimed Shift for opening its description
screen. Rather than fight either mod over the same tooltip, the information gets its own panel.

**It applies to any item from any mod** — the only condition is that the item actually has affixes
or sockets. There is no relic check.

The panel is built from Apotheosis' own helpers (`AffixHelper.streamAffixes`,
`AffixInstance.getDescription`, its `dotPrefix`/`starPrefix` styling and its real `SocketComponent`),
so sockets render as actual gem icons and the lines match what Apotheosis would have drawn. If they
change their formatting, this follows.

### `/foxstweaks testhotbar [sockets]`

Op-only. Fills your hotbar with random relics, each rolled at the highest loot rarity Apotheosis has
loaded and filled with random gems at `PERFECT` purity — for exercising the tooltip without hunting
for affixed gear. `sockets` is `0–16` (16 is the real ceiling: `SocketHelper.setSockets` clamps
there), default 4.

It doubles as a diagnostic. Each line reports the `LootCategory` the relic resolved to:

```text
Jellyfish Necklace - apothic_compats:necklace, 4 affixes, 8/8 gems
Jellyfish Necklace - no loot category - cannot be affixed or socketed
```

Apotheosis refuses to affix or socket anything mapping to no category, so the second form is the
direct explanation for relics not getting affixes.

## Which side needs what

| Feature | Needs | Side |
| --- | --- | --- |
| Solve on pickup | Relics | Server (integrated server counts) |
| Auto-solve button | Relics | Client |
| Affix/socket tooltip | Apotheosis | Client |

Research is kept in a server-authoritative data attachment keyed by `<item id>#<ability id>`, and
`ResearchData` refuses to write unless handed a `ServerPlayer` — so solving on pickup needs this
installed server-side. Because that key is per *item type* rather than per stack, solving is
one-and-done per relic type per player: later sweeps short-circuit on `isResearched`.

The auto-solve button is client-only. It invents no packet: it calls `AbilityResearchScreen#addLink`
/ `#removeLink`, the same public methods the screen uses when you drag a line between stars, so any
server already running Relics accepts it unchanged.

## Notes for future me

**Optional dependencies are wired by hand.** FML loads `@EventBusSubscriber` classes whether or not
their dependencies exist, so anything touching Relics is registered programmatically from the mod
constructor behind a `ModList.isLoaded` check (`RelicsIntegration`, and `RelicsClientIntegration`
for the client-only screen classes). Apotheosis needs no such care — those handlers hang off vanilla
events and only reach Apotheosis classes inside method bodies, which classload lazily.

**The tooltip panel depends on no mixin and on no other mod.** NeoForge 1.21.1 has no
`RenderTooltipEvent.Post` — only `Pre`, `Color` and `GatherComponents`, none of which report the
tooltip's final geometry. But `Pre` exposes `getTooltipPositioner()`, so `AffixTooltipHandler`
reproduces vanilla's measurement from `GuiGraphics#renderTooltipInternal` (note the `-2` height when
there is exactly one component) and runs the very positioner about to be used. That yields the exact
final position with no mixin. An earlier version hooked Relics' `TooltipDisplayEvent` for this,
which worked but tied a general-purpose feature to Relics being installed.

**Rendering a tooltip re-fires the tooltip event**, so there is a re-entrancy guard. Without it the
first hover recurses until the stack overflows.

**Known Relics bug (not ours):** solving in the *creative* inventory fails with "Working with a
relic in this inventory is not possible". `DescriptionHandler` captures the hovered slot as an index
into whichever menu is open, and creative's `ItemPickerMenu` puts the hotbar at indices 45–53 — but
the server validates against `InventoryMenu`, which only has 0–45. Index 45 hits the offhand and
46–53 are out of range. Dragging the stars by hand fails identically. Use the survival inventory, or
leave `autoSolveOnPickup` on, which bypasses the packet path entirely.

**Known apothic_compats bug (not ours):** on world load Apotheosis rejects ~11 `affix_loot_entries`
for apothic_compats' own curio items with "Items without a valid loot category are not permitted".
`ModLootCategories` registers those categories with tag-based predicates, and the entries are parsed
before item tags are bound. It is parse-time only — at runtime tags are bound and
`LootCategory.forItem` resolves normally.

## Building

```bat
build.bat build
```

`build.bat` points `JAVA_HOME` at the JDK in `toolchain/` and calls `gradlew`. That folder exists
because this machine had no JDK, only the JREs bundled with the Minecraft launcher, and a JRE has no
`javax.tools` compiler — NeoForm cannot recompile Minecraft without one. It is a plain unpacked
Temurin 21; nothing is installed system-wide and deleting the folder undoes it. With a JDK 21
installed, `gradlew build` works directly and `toolchain/` can go.

Output: `build/libs/foxstweaks-neoforge-1.21.1-1.0.0.jar`.

Also needed here: `systemProp.java.net.preferIPv4Stack=true` in `gradle.properties`, because the
Maven hosts publish AAAA records but there is no working IPv6 route on this machine and the JVM
stalls until it falls back.

### `libs/`

None of the parent mods are on a public Maven, so `libs/` holds the jars they are compiled against,
copied out of the modpack instance: Relics, Curios, OctoLib, Apotheosis, Placebo, ApothicAttributes.
They are `compileOnly` and `.gitignore`d — at runtime the real mods are already loaded. NeoForge mod
jars for 1.21.1 ship in Mojang mappings, which is why production jars work as compile dependencies.

### Access transformer

`src/main/resources/META-INF/accesstransformer.cfg` widens `Screen#addRenderableWidget`, which is
`protected` and unreachable from an addon's package. That is what lets the auto-solve button be a
first-class widget on Relics' screen instead of something drawn over the top of it.

It also widens the same method on `CreateWorldScreen`, the one class that overrides it. Java forbids
an override from being less visible than the method it overrides, so widening only the parent makes
Minecraft itself fail to recompile with *"attempting to assign weaker access privileges"*. Both
lines are needed together.
