# Hologram web editor protocol (version 1)

The web editor is a second client of the in-game hologram editor. The game stays in charge: it owns the
preview, undo history and saving, and it validates everything the browser sends. The browser only proposes
whole specs and asks for actions.

Code: `client/web/` (`WebServer`, `WebSocketConnection`, `HologramWebSession`, `HologramWebJson`,
`HologramWebRegistries`), `client/HologramWebBridge` (adapter to `HologramScreen`), browser app in `web/`.

## Session

- A session starts when **Web** is pressed in the Studio or the Creator, or with
  `/foxstweaks hologram web [id]` (a client command; it opens the editor first if needed).
- One session at a time, bound to one editor. Pressing Web again in the same editor reuses it; another editor
  replaces it.
- It ends when the editor closes (Done, Esc, Delete), on `save` or `cancel` from the browser, when the player
  leaves the world, when the game shuts down, or after 5 s with no screen open (grab mode excepted, it hides the editor on purpose). Each connected browser then gets
  `closed` and the port closes. The token dies with it; a new session gets a new port and token.

## Transport and security

- One `ServerSocket` on `127.0.0.1`, port chosen by the OS. HTTP/1.1 (GET only) and RFC 6455 WebSocket on the
  same port, so page and socket share one origin.
- URL: `http://127.0.0.1:<port>/<token>/`. WebSocket: `ws://127.0.0.1:<port>/<token>/ws`.
- `<token>`: 24 bytes of `SecureRandom`, base64url without padding (192 bits). Compared in constant time.
  Every request without it is `403`.
- `Host` must be exactly `127.0.0.1:<port>` (DNS rebinding). Duplicate `Host`/`Origin` headers are refused.
- The WebSocket upgrade must carry `Origin: http://127.0.0.1:<port>`; anything else (missing, `localhost`,
  another site) is `403`.
- Static files only from `assets/foxstweaks/web/` in the mod file. Paths must match a strict pattern (letters,
  digits, `_ - .`, at most five segments, known extensions), no `%`, no `\`, and the resolved path must stay
  under that folder. Anything else is `403`/`404`.
- Headers on every response: `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Cross-Origin-Resource-Policy: same-origin`. The page
  also gets a CSP: `default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'
  data:; font-src 'self'; connect-src ws://127.0.0.1:<port>; base-uri 'none'; form-action 'none';
  frame-ancestors 'none'`.
- Limits: 24 open sockets, 4 WebSocket clients, 16 KB request head, 1 MB per message (close `1009`),
  120 messages/s with a burst of 240 (excess is dropped with a `rate` error at most once a second; 600 drops
  closes with `1008`), 512 queued outbound messages (overflow closes with `1008`).

## Messages

Text frames, one JSON object each, with a `type`. Unknown fields are ignored; unknown types get an `error`.
Types `timeline`, `asset` and `audio` are reserved for later versions.

### Game -> browser

| type | fields | when |
|---|---|---|
| `hello` | `protocol` (1), `mod`, `modVersion`, `minecraft`, `hologram` (id), `registries` | on connect |
| `state` | `rev`, `ack`, `canUndo`, `canRedo`, `spec` | on connect, after every handled message, and whenever the spec changes in game |
| `selection` | `kind` (`root`/`group`/`layer`), `layer`, `group`, `soloLayer`, `soloGroup` | on connect and when it changes |
| `error` | `seq`, `code` (`invalid` incl. malformed JSON, `unknown` type, `rate`, `internal`), `message` | a message was refused |
| `pong` | `seq` | answer to `ping` |
| `closed` | `reason` (`saved`, `cancelled`, `editor closed`, `left the world`, `game closed`, `replaced`, ...) | session ends |

`ack` is the highest `seq` the game has handled from this connection. The browser keeps its local copy while
`ack` is below the last `seq` it sent, so a late `state` cannot undo a keystroke.

`registries`: `blocks` (`id`, `name`, `blockEntity?`, property refs), `properties` (shared property table:
name and values), `entities` (`id`, `name`), `particles` (ids of simple particle types), `effects` (per type:
`label`, `short`, `family` fx/motion/entity, which fields it uses, amount `min`/`max`/`step`/`decimals`),
`triggers`, `presets`, `revealStyles`, `limits` (max layers/groups/effects, name length, and a `{min,max}`
range per numeric field).

### Browser -> game

Every message may carry `seq` (increasing integer); the game echoes it as `ack`.

| type | fields | effect |
|---|---|---|
| `edit` | `spec`, `gesture` | replaces the editor's working copy with `spec`; a new `gesture` id starts a new undo step, the same id (a drag, a scrub, held arrow keys) extends it |
| `select` | `kind`, `layer`, `group` | selects in the in-game editor |
| `solo` | `layer`, `group` | sets solo (`-1`/`null` clears it) |
| `undo`, `redo` | | the editor's own undo stack |
| `save` | | same as Done in game; ends the session with `saved` |
| `cancel` | | discards every change since the editor opened; ends with `cancelled` |
| `ping` | | `pong` |

## Spec

```jsonc
{
  "id": "amethyst_block_1", "pos": [0, -59, 2], "owner": "...",           // read only
  "root": { "offset": [0, 0, 0], "rotation": [0, 0, 0], "scale": 1 },
  "renderDistance": 64, "maxRenderDistance": 128,                         // max is read only
  "reveal": { "radius": 0, "seconds": 0.6, "style": "Rise" },
  "groups": [ { "id", "name", "visible", "offset", "rotation", "scale",
                "spin", "spinAxis", "hover", "hoverPeriod", "fullbright", "effects" } ],
  "layers": [ { "kind": "BLOCK|ENTITY|PARTICLE", "id", "props": { "facing": "north" }, "group": "",
                "visible", "offset", "rotation", "scale", "spin", "spinAxis", "hover", "hoverPeriod",
                "fullbright", "particleRate", "particleSpeed", "effects",
                "data": { "key": "1a2b-3c4", "bytes": 812, "snbt": "..." } } ]
}
```

Effect: `{ "type", "strength", "speed", "color", "color2", "axis" (0 Y, 1 X, 2 Z), "phase", "trigger" }`.

## Validation

`HologramWebJson.read` rebuilds the spec from scratch and rejects the whole edit (`error`, nothing changes) on:
unknown block/entity/particle id, a property or value the block does not have, wrong JSON types, more than the
layer/group/effect limits, unknown effect type or trigger. Numbers are clamped to the `limits` ranges, names are
trimmed to 32 printable characters, `id`/`pos`/`owner` are always taken from the game's copy, and the render
distance is capped at the server's maximum. An empty layer list gets a stone layer back.

**Block-entity and entity data never comes from the browser.** The game sends `data.key`
(`hex(hash)-hex(size)`), a byte count and a read-only SNBT preview; the browser sends back only the key.
The game maps keys to tags it sent this session (128 most recent); an unknown key means no data. So a page
cannot inject NBT, and a forged key just drops the data.
