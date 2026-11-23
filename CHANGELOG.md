# Changelog

## 1.34.4

- Fixed `/foxstweaks debug particles` and client-side relic particle suppression missing particles: `ClientLevel` overrides `addParticle` without calling `Level#addParticle`. Moved both to a new `ParticleEngineMixin` hooking `ParticleEngine#createParticle` and `ParticleEngine#add`, catching every client-side particle (including S2C packets and direct `ParticleEngine#add` calls).

## 1.34.3

- Fixed `ScrollShelfHandlerMixin` failing to apply at runtime (`@Shadow field this$0` descriptor mismatch on `ScrollShelfBlockEntity$1`), which left Scroll Shelf's O(N²) `getAvailableScrolls()` uncached. Also skips cache invalidation on simulated `insertItem`/`extractItem` calls.

## 1.34.2

- Renamed the Spawn Anchor item/GUI/nametag to **Mob & Chunk Loader** with clearer multi-line item tooltips and GUI subtitle explaining what it does (force-loads chunks, random ticks Dreadful Dirt/crops, activates spawners, prevents mob despawning).
- Added client-only `/foxstweaks debug particles [seconds]` command: logs the exact particle ID, calling class/method, and visible active MobEffects for particles spawned within 3 blocks of the player (printed to chat + `latest.log`).

## 1.34.1

- Scroll Shelf performance fix: caches `ScrollShelfBlockEntity`'s available-scrolls list until the shelf mutates. Previously `getStackInSlot(i)` rebuilt the full list on every slot lookup, making Logistics Networks / pipe stacked insertions O(N²) (300ms–1.25s server stalls, ~70% of server tick in a spark profile). Toggle: `scrollShelfCache` (default on). Server side.
- Performance overlay: moved JMX CPU sampling (`CpuSampler`) onto a 1 Hz background daemon thread so Windows PDH queries never stall the server or client tick thread (dropped from ~4.75% to 0%).

## 1.34.0

- Spawn Anchor: craftable armor-stand dummy (`foxstweaks:spawn_anchor` — Eye of Ender, 2 Gold Ingots, Armor Stand, 3 Obsidian) that force-loads chunks around it, runs random ticks (Dreadful/Delightful Dirt, crops), activates vanilla & Apotheosis spawners, allows natural mob spawns, and prevents mob despawning — with no fake-player side effects (doesn't block sleeping, quests, or Tab list).
- Right-click a placed Spawn Anchor dummy to open its GUI: live chunk-grid preview, ON/OFF toggle, `-`/`+` chunk radius (`1..spawnAnchorsMaxRadius`), and Pick Up button (or Sneak + Left-click anytime).
- Op commands (`/foxstweaks anchor add <name> [radius] | remove <name> | list | tp <name>`) and server config (`spawnAnchorsEnabled`, `spawnAnchorsMaxRadius`). Persists across restarts.
- Dev check: `build.bat runAnchorCheck` (game dir `run-anchor/`).

## 1.33.0

- Relics: added a native **Show relic particles** toggle to the **Relic Options** tab (right under *Show visual effects for flawless relics*). Turning it off suppresses particles spawned by that relic while worn (`curioTick`, client and server, across Relics, Reliquified Artifacts, MoreRelics, and any Curios relic addon) without affecting its abilities or stats. Stored on the relic item; optional network channel. Toggle: `relicParticleToggle` (default on).

## 1.32.0

- Tab performance overlay: holding Tab shows two live performance cards flanking the player list — **Server** on the left (TPS for 5s/10s/1m/5m/15m, tick duration sparkline + min/med/p95/max ms, CPU, heap & 1m GC pauses, loaded chunks/entities, and top 4 active dimensions with proportional bars) and **Client** on the right (FPS + 1% low, live frame-time sparkline + percentiles, CPU/GPU, heap & 1m GC, ping, view distance & client entities). Automatically falls back below the Tab list when the screen is too narrow to flank, and shows only the Client card on servers without Fox's Tweaks. Zero network traffic while Tab is closed (optional channel).
- Optional compact pinned mini-HUD toggled via the new "Toggle Performance Mini-HUD" key (Controls, unbound by default).
- Client/common toggles: `perfTabOverlay` (default on), `perfMiniHud` (default off). Server toggles (`serverconfig/foxstweaks-server.toml`): `perfOverlayEnabled` (default on), `perfOverlayOpOnly` (default off).
- Dev check: `build.bat runPerfShot` (game dir `run-perf/`).

## 1.31.3

- Fixed a client crash (`IllegalStateException: Pose stack not empty`) when breaking Farmer's Delight Canvas Rugs (or any block whose breaking overlay is cancelled early by a mixin) alongside Block Manipulator. Client side.

## 1.31.2

- Removed Drag Move (added in 1.31.0, fixed in 1.31.1). It is gone entirely: no keys, no settings, no client code. Everything else is as in 1.30.0. Update to drop it.

## 1.30.0

- Search boxes in AE2 terminals now suggest from what the network holds: items in stock first, then items it can auto-craft, then every name in the game. "Did you mean" also corrects to network items first. Toggle: `searchAssistContext`. Client side.
- Fixed a crash (`NullPointerException ... "cached" is null`) when typing a 4+ letter word with no close match in a search box.

## 1.29.1

- Search boxes: after a search prefix, only that kind of thing completes: `@` mod ids, `#` tag paths, `$` a tooltip word (Tab accepts). A misspelled `@mod` id still gets a "Did you mean". Other prefixes (`%`, `^`, `&`) are left as typed. Client side.

## 1.29.0

- Search boxes: `@mod` and `#tag` words now autocomplete too (Tab accepts): `@ae` finishes to the mod id, `#ingo` to a tag path, and a misspelled mod id gets a "Did you mean" fix. EMI, JEI and AE2 all use `@` for mod, `#` for tag and `$` for tooltip text; `$` words are left as typed. Client side.

## 1.28.0

- Search boxes: every search box (EMI, JEI, AE2 terminals, Refined Storage, creative inventory and any other whose label or class says search/filter/find) now shows a grey autocomplete of item, mob, effect and mod names (Tab accepts), and a "Did you mean" line for misspelled words (Tab accepts). With autocorrect on, the box's listener also gets the corrected text, so "diamnod" still finds diamonds. Client side. Toggles: `searchAssist`, `searchAssistAutocorrect`; `searchAssistExtraBoxes` / `searchAssistSkipBoxes` add or exclude boxes by class name.

## 1.27.0

- Relics: auto-level can now watch several abilities on one relic. Left-click the star to mark or unmark an ability; right-click or Shift-click it to switch the mode. "In order" (default) finishes the first marked ability before the next. "Round robin" gives each marked ability one upgrade in turn, so upgrade points and experience are shared evenly. Auto rank-up now waits until every marked ability is done. Relics you had marked before keep their one ability. Both client and server need this version (the auto-level packet changed). Toggles unchanged: `relicAutoLevel`, `relicAutoLevelMinXp`, `relicAutoRankup`.

## 1.26.1

- Hats: stops its Fresh Animations compatibility from throwing thousands of `NoSuchFieldException`s per frame while rendering models with a head (chests, mobs, players). It looked up a model part's name by reflection each draw, and a missing field is an exception every time, even without Fresh Animations. Each lookup is now remembered, misses included. In an ATM10 spark profile that exception cost was around 8-13% of the render thread. Client side, needs a restart. Toggle: `hatsFieldLookupCache`.

## 1.26.0

- Overworld copy: the portal frame, instant teleport, flat surface and dye settings are now per-world server settings (`<world>/serverconfig/foxstweaks-server.toml`), so the server's values apply and clients can't diverge. They start at their defaults: set the frame again if you changed it. `overworldCopy` (on/off) stays in `config/foxstweaks-common.toml`.
- Overworld copy: the default portal frame is now stone bricks (was crying obsidian).
- New command `/foxstweaks portal frame [set|add|remove|reset] <block>` changes the frame on a running server and saves it (op level 2).
- New command `/foxstweaks portal regenerate [cancel]` (op level 4) queues a regeneration of the overworld copy for the next server start. Refused while a player is in the copy. Same seed, so the terrain comes back and everything built there is gone.

## 1.25.2

- Relics: a relic's tooltip now says "Right-click to research" while right-click open is on and works (inventory screens, empty cursor). The matching key is now called "Open Relic Research" in Controls.

## 1.25.1

- Shrink: "Toggle Shrink" now finds a mob hidden in grass, flowers or other blocks you can walk through. A shrunk mob standing in grass couldn't be grown back with the key; the key hit the grass and changed your own size instead. Walls still block it.

## 1.25.0

- Shrink: the Shrinking Device gets its own Curios slot, and the "Toggle Shrink" key now also uses it from there. Not to be confused with Compact Machines' Personal Shrinking Device, which has its own slot and doesn't change your size.
- Shrink: "Toggle Shrink" now shrinks or unshrinks the mob or player you're looking at (within reach), or yourself when you aren't looking at one. Same power cost as using the device on them.
- Shrink: Shift + "Toggle Shrink" opens Shrink's own device menu to set the scale, wherever the device is (Curios slot or inventory, not just the main hand). Shrink's menu now also reads and saves that device instead of whatever is in your main hand.

## 1.24.0

- Shrink: new "Toggle Shrink" key (Controls, unbound by default) shrinks or unshrinks you with a Shrinking Device from anywhere in your inventory, same power cost and scale as sneak-using it. Needs Fox's Tweaks on the server. Toggle: `shrinkHotkey`.
- Settings screen: Fox's Tweaks now has an in-game settings screen, from the Mods list "Config" button or the new "Open Fox's Tweaks Settings" key (unbound by default).
- Dev check: `build.bat runShrinkCheck` (game dir `run-shrink/`).

## 1.23.2

- Kaleidoscope Grilling: no longer rebuilds its skewer recipe list for every item drawn. Rooms full of drawers, item frames or shelves lost 6-13% of frame time to it. Results are kept until datapacks reload or the server sends new skewer recipes. Toggle: `grillingSkewerCache`.
- Dev check: `build.bat runGrillingCheck` (game dir `run-grilling/`).

## 1.23.1

- Overworld copy: now generates with its own private copy of the vanilla overworld noise settings, density functions and noises (`foxstweaks:vanilla_overworld`), so a datapack that replaces `minecraft:overworld` (ReTerraForged and similar) no longer changes the copy.
- Overworld copy portals under shader packs: the portal texture is now vanilla purple with a low red channel. Packs like Complementary read the raw texture and ignore the block tint, so the portal came out solid white. Dye colours show without shaders; shader packs that ignore tint show every portal purple.

## 1.23.0

- Toggle enchantments: any enchantment on any equipped item can be switched off and back on at its exact level (Silk Touch vs Fortune, and so on). Switching off really removes it; a red X line shows where it was in the item tooltip. Curses are not toggleable (`enchantToggleBlacklist`). Re-enabling is refused while an incompatible enchantment is active. Toggle: `enchantToggle` (on by default).
- New one-key toggle screen replaces the affix overlay: the item's own tooltip look, clickable. Slot strip (head, chest, legs, feet, main hand, offhand) on top, enchantments two per line, affixes one per line with their real descriptions, gem sockets shown read-only. Sizes itself to the item; very long items scroll the affix block. Also opens over the inventory and any container screen. `key.foxstweaks.toggle_affixes` is the only key (unbound by default).
- Disabled affix and enchantment lines now show a drawn red X instead of the "❌" emoji, which the default font could not render.
- Dev check: `build.bat runToggleShot` (game dir `run-toggle/`).

## 1.22.0

- Overworld copy: a second, default overworld (`foxstweaks:overworld_copy`, vanilla noise settings, overworld dimension type and standard biomes) for packs whose main overworld uses a custom world type, so everything vanilla and other mods do to a normal overworld still generates somewhere. Reached by a portal: a frame of the configured block (default crying obsidian), lit with flint and steel or a fire charge, with the same size rules as a Nether portal (2-21 wide, 3-21 tall, corners optional). The portal leads to the other overworld at the same coordinates and back. Uses vanilla portal blocks and a vanilla frame block, so the mod stays optional on clients. Toggle: `overworldCopy` (on by default). Frame blocks or `#tags`: `overworldCopyPortalFrame`.
- Overworld copy: right-click a portal with a dye to colour it (`overworldCopyPortalDye`); needs the mod on the client to be seen, others see purple. Teleport is instant by default (`overworldCopyInstantTeleport`).
- Overworld copy: a portal built on arrival goes on the surface, on the flattest ground within 32 blocks, not in a cave. Optional `overworldCopyFlatSurface` (off by default) also levels a ~9x9 pad of surface around it with the biome's own surface block; terrain, caves and everything else are left as generated.
- Overworld copy: vanilla Nether travel ignores these portals when looking for a portal to arrive at.
- Dev check: `build.bat runPortalShot` (game dir `run-portal/`, no extra mods).

## 1.21.0

- Relics: right-click a relic in any inventory (empty cursor) to open its description screen at once, no more holding the research key. There is also an "Open Relic Description" key under Controls (unbound by default). Toggle: `relicRightClickOpen`.
- Relics: auto-level. A new star toggle under the upgrade / reroll / reset buttons on an ability's screen marks the ability that relic levels up by itself whenever you have the experience and upgrade points, costing exactly what a manual upgrade costs. Works on relics in your inventory and Curios slots; needs this mod on the server. Toggles: `relicAutoLevel`, `relicAutoLevelMinXp` (experience points always left over, default 0).
- Relics: auto rank-up. A relic with an auto-level ability ranks up once it is at max level and that ability can take no more upgrades. Ranking up resets ability levels and unspent points, as in Relics itself. Toggle: `relicAutoRankup`.
- Relics: abilities that are researched and high enough level are unlocked automatically, without the five card clicks. Applies to every carried relic. Toggle: `relicAutoUnlock`.

## 1.20.2

- Fixed: FTB Library's sidebar buttons (ours and FTB's own) did nothing on Accessories' default screen, and their tooltips drew an empty box. owo-lib's UI layer swallowed the mouse release FTB clicks on, and clipped the tooltip text. Both are worked around now; other screens are unaffected.

## 1.20.1

- Fixed: the stale mixin guard switched off Accessories' compat-layer config mixin, so Accessories failed to load ("No endec available for class ...SlotIdRedirect") and took dependent mods with it. The guard took a call into endec (a library owo-lib bundles) for a missing owo-lib internal. It now only treats a class as the patched mod's own when the first three package segments match, and checks every class loader before calling one missing.

## 1.20.0

- EMI Slim is now part of Fox's Tweaks: EMI shares one item list per tag across its recipes and keeps
  identical item data once, for several hundred MB less memory on big packs. Remove the separate
  `emislim` jar. Toggles: `emiShareTagLists`, `emiInternPatches` (replace the old
  `-Demislim.shareTags` / `-Demislim.internPatches` switches; apply on the next EMI reload).

## 1.19.0

- TACZ: gun, attachment and ammo models draw faster. Invisible zero-area sides are dropped when a
  model loads (about a quarter of the default gun pack's quads), and drawing no longer allocates per
  frame. Looks identical. Replaces the performance patch in our TACZ fork, so upstream TACZ can be
  used. Toggle: `taczFastModelRender` (F3+T to apply).

## 1.18.1

- Author is now watdafawx (was mtop); Java packages moved from dev.mtop to dev.watdafawx. No gameplay changes.

## 1.18.0

- Inventory Profiles Next: IPN's editor and settings buttons can now sit in FTB Library's sidebar,
  next to the FTB icons, instead of floating over each screen. Shift/Ctrl/Alt+click the editor button
  to toggle IPN for the current screen or container. Toggle: `ipnSidebarButtons`.
- Fixed a possible error when a screen opened before the config had loaded.
- Earlier 1.1x additions: search box for Iris shader settings (1.17.0), EMI "+" fills Sophisticated
  Backpacks upgrade recipes (1.16.0), Modern Chickens no longer fills structure chests before Lootr
  (1.15.0), and four performance patches found with spark (1.14.0).
