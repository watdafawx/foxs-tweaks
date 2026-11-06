# Changelog

## Unreleased

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
