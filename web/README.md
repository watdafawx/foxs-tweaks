# Hologram web editor

The browser half of the hologram editor: a Preact + TypeScript app that talks to the game over a local
WebSocket. The game serves it from the mod jar; see `docs/hologram-web-protocol.md` for the wire format and
the security rules.

## Build

```sh
cd web
npm ci
npm run build
```

`npm run build` typechecks (`tsc --noEmit`) and writes the bundle to
`../src/main/resources/assets/foxstweaks/web/` (`index.html`, `assets/app.js`, `assets/app.css`). That output
is committed, so `build.bat build` needs no Node: rebuild here and commit the output whenever `web/src`
changes.

File names are fixed (no content hashes) because the jar is the only cache and the server sends
`Cache-Control: no-store`.

## Running it

Open a hologram editor in game (Studio or Creator) and press **Web**, or run `/foxstweaks hologram web <id>`.
The game opens `http://127.0.0.1:<port>/<token>/` in the default browser and copies the link to the clipboard.

`vite dev` cannot talk to the game: the server only accepts a WebSocket whose `Origin` is its own
`http://127.0.0.1:<port>`. Rebuild and reopen instead; the game reads the files from the jar (or from
`build/resources` in a dev run) on each session.

## Layout

| File | What |
|---|---|
| `src/protocol.ts` | message and spec types, mirrored from `client/web/HologramWebJson.java` |
| `src/store.ts` | connection, state, edit coalescing (one frame), undo/redo/save/cancel, playhead + transport, auto-key |
| `src/anim.ts` | timeline maths ported from `HologramTimelineEval` (easing, bezier, OKLab tint, sampling) and track helpers |
| `src/timeline.tsx` | timeline dock: tracks, ruler, keyframes, key inspector, graph editor, shortcuts |
| `src/ui.tsx` | number fields (drag to scrub, type, arrow keys), vectors, toggles, colour |
| `src/outliner.tsx` | layer/group tree: drag reorder, visibility, solo, rename |
| `src/inspector.tsx` | root, group and layer inspectors, effect stack |
| `src/browser.tsx` | searchable block/entity/particle picker |
| `src/main.tsx` | shell, header, keys |

## Timeline

The dock along the bottom animates the hologram, its groups and layers over a clip of 0.5 to 120 s.

- **Tracks**: select something in the outliner (or the hologram itself), then **+ Track** on its row picks a
  property: offset, rotation, scale, visible, tint, or the strength of one of its effects.
- **Playhead**: drag the ruler to scrub; the game draws that exact frame (preview only, nothing is saved).
  Space plays from the playhead, **Live** gives the clock back to the game (game time and the start trigger).
- **Keys**: click a diamond to select, Shift/Ctrl-click or drag a box for more. Drag to move: snaps to frames
  (1/20 s), other keys, the playhead and the clip ends; hold Alt for no snapping. Double-click a lane to add a
  key there, `K` adds or removes one at the playhead on the focused track, `Del` deletes, `Ctrl+C`/`Ctrl+V`
  copy and paste at the playhead, `Left`/`Right` nudge a frame (Shift: 10), `,`/`.` step the playhead.
- **Key inspector** (right): time, value and the easing out of the key (linear, hold, ease in/out/in-out,
  bezier with four handle numbers).
- **Graph** shows the focused track's curve; drag keys up/down/sideways and drag the handles. Dragging a handle
  turns that key's easing into a bezier.
- **Auto-key**: with it on, changing an animatable value in the inspector creates its track and keys it at the
  playhead. A property that already has a track is always keyed at the playhead, as in After Effects.

Every change is an ordinary edit, so Ctrl+Z / Ctrl+Shift+Z undo it and the game validates it.
