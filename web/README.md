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
| `src/store.ts` | connection, state, edit coalescing (one frame), undo/redo/save/cancel |
| `src/ui.tsx` | number fields (drag to scrub, type, arrow keys), vectors, toggles, colour |
| `src/outliner.tsx` | layer/group tree: drag reorder, visibility, solo, rename |
| `src/inspector.tsx` | root, group and layer inspectors, effect stack |
| `src/browser.tsx` | searchable block/entity/particle picker |
| `src/main.tsx` | shell, header, keys, timeline placeholder |
