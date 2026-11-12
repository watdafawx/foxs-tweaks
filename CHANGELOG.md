# Changelog

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
