# Hologram QA, Fox's Tweaks 1.43.0

Branch `qa/holograms-1.43.0` (from f10671d). Run on rig, 1920x1080 window, muted, flat world.
Web editor driven in an isolated Edge over CDP. Shader run: Iris 1.8.14-beta.1, Sodium, Complementary Reimagined.
Screenshots: `docs/qa/holograms-1.43.0/`. Driver: `build.bat runHologramQa` (dev only, `client/dev/HologramQaDev`).

Commits on this branch: `aeba6e7` QA driver, `a7b5ef5` web narrow layout + favicon, `131b9fe` save-refused feedback, `fc2c900` driver commands.

## Summary by severity

| # | Sev | Target | Issue | Status |
|---|---|---|---|---|
| 1 | High | 2 | Water and lava missing from structure holograms | open, needs fluid rendering |
| 2 | High | 1/2 | Editor save silently dropped when player is >14 blocks from the hologram | feedback added (131b9fe), root cause open |
| 3 | Med | 1 | Web layout broke below 720 px (inspector and Save off-screen) | fixed a7b5ef5, verified |
| 4 | Low | 1 | Favicon 403 console error | fixed a7b5ef5, verified |
| 5 | Low | 3 | Creator text overflow / truncation at GUI 3 and 4 | open, cosmetic |
| 6 | Low | 1 | Picker ranking, duplicate sign names, controls stay after "Session ended" | open, nits |

## Target 1: web editor (real browser)

PASS
- Outliner: hide/show for layers and groups (confirmed in game: `game-hidden.jpg`, `game-shown.jpg`), create group, rename group, reorder with the Move up/down buttons.
- Inspector: scale edit reaches the game. Effect stack: add, reorder, remove. Colour badges differ per family (fx / motion / entity).
- Content picker: search, click to replace a layer (group kept), search with `@mod`. See `web-04-search.png`.
- Undo/redo through buttons and Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y, state matches the game.
- Save (Ctrl+S and button): game saves, page shows "Saved" overlay.
- Game closes the editor: page shows "Session ended ... editor closed" within a few seconds, no hang (`web_closed.png`).
- Wrong token, wrong Host, wrong Origin, path traversal: all 403.
- 1920 wide: three panels, no overflow (`web-02-1920.png`).

ISSUES
1. **Narrow layout (fixed).** At 500 px the inspector and Save were off-screen, page scrolled sideways. Before: `narrow500.png`. After: `web_500_new.png`, scrollWidth == clientWidth, Save fully visible. Fix in `web/src/styles.css` (`@media (max-width: 720px)` stacks panels), bundle rebuilt.
2. **Favicon 403 (fixed).** Browser requested `/favicon.ico`, server answered 403, console error. Added `<link rel="icon" href="data:,">` in `web/index.html`. No new console errors after the fix.
3. **Silent save loss (High, partly addressed).** Steps: grab a structure with the editor camera/player far from the hologram (grab mode moves the player), press Done. Expected: saved or a message. Actual: `HologramNetwork.handle` returns at the `distSq > (REACH+8)^2` check, change lost, no log. Same silent path for structure data over the `ServerConfig` limits. Now logs a WARN and shows a chat line (131b9fe). Root cause open: an open editor session should not be gated on where the player stands, or Done should be refused in the client with a visible message. Location: `hologram/HologramNetwork.java`, distance gate.
4. **Nits.** Picker ranks "Dark Oak Stairs" above "Oak Stairs" for query `oak stairs` (`web/src/browser.tsx` ranking). Sign and wall-sign variants show the same display name (e.g. two "Acacia Sign"). Undo/Redo/Save controls stay visible but disabled after "Session ended". Not a bug: Undo/Redo disabled state is correct.

NOT TESTED
- Outliner drag-and-drop reorder (HTML5 DnD does not fire from CDP mouse events), double-click rename of a layer, solo (S button) effect in game, picker "+ add layer" via the button.

## Target 2: structures

Setup: house 13x6x7 (238 blocks): stone brick, oak planks and logs, stairs, glass, stained glass, chest with items, sign, water and lava sources plus flows, slab, fence. Grabbed with grab mode, structure layer next to the real build.

PASS
- Stairs, slabs, fences, glass, stained glass, planks/logs, stone brick draw with correct models and orientation.
- Face culling: no inner faces visible, no holes between blocks. Transparency: glass and stained glass blend correctly, also under shaders.
- Under Iris + Sodium + Complementary Reimagined: structure and plain block hologram light and shadow like normal blocks, day and night. No flicker, no z-fighting seen across day, night, top-down views. See `game-sh_day_wide.jpg`, `game-sh_night_wide.jpg`, `game-sh_night_block.jpg`, `game-top_holo.jpg`.
- Night: holograms darken with the world and are not emissive (expected: no hologram light). Lava of the real build lights the grass, the hologram does not.

ISSUES
1. **Fluids missing (High).** Steps: grab a box holding water and lava. Expected: fluids in the hologram like the real build. Actual: none; the hologram shows only the stone under them. Compare `game-s_overview3.jpg` (hologram left, real right) and `game-top_holo.jpg` / `game-top_real.jpg`. Cause: `HologramStructureMesh.java:235` skips every state whose `RenderShape != MODEL`, and fluids/waterlogging are never drawn separately. Fix: render fluid states through the fluid renderer into the mesh (and waterlogged blocks), behind the existing cache.

NOT VERIFIED
- Chest contents and sign text inside the hologram: house is roofed, both screenshots only show the outside. Not confirmed either way.
- Complementary only; BSL not tried. Fluid and BE-model behaviour under shaders unknown because fluids are absent.

## Target 3: Creator at GUI 2, 3, 4 (1920x1080)

At 1920x1080 GUI 4 is a 480x270 layout, the tightest case; 4K at GUI 4 gives 960x540 (same as 1080p at GUI 2), so it is covered by the GUI 2 result.

PASS
- GUI 2 (`game-cr_gui2.jpg`): everything visible, nothing overlaps the hologram, 2 panels at the edges.
- GUI 3 (`game-cr_gui3.jpg`): Reveal section scrolls under the footer (reachable by wheel), hologram centred and clear.
- GUI 4 (`game-cr_gui4.jpg`): panels 535 px and 757 px wide, 600 px free in the middle, hologram unobstructed. Motion/Reveal reachable by scrolling. Top bar buttons fit.

ISSUES (Low, cosmetic)
1. GUI 3: "Nothing yet: pick a look, FX or motion." runs past the right edge of the panel (clipped by the scrollbar). `HologramCreatorScreen` stack-empty hint, needs wrap.
2. GUI 4: layer name truncated to "amethyst_blo" without an ellipsis; "+ Save" in the Looks grid touches the tile border; Motion tiles lose their text labels (icon only) when the row is clipped.
3. GUI 2/3: the bottom hint bar ("R-drag look ...") overlaps the hotbar at GUI 2 (hotbar draws over it). Minor.

## Needs bigger work
1. Fluid rendering in structure holograms.
2. Save gating by player distance while the editor is open.
3. Drag-and-drop, rename and solo in the web outliner need a manual pass (or a DnD-capable driver).
