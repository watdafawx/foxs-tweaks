# Hologram web editor protocol (version 3)

The web editor is a second client of the in-game hologram editor. The game stays in charge: it owns the
preview, undo history and saving, and it validates everything the browser sends. The browser only proposes
whole specs and asks for actions.

Code: `client/web/` (`WebServer`, `WebSocketConnection`, `HologramWebSession`, `HologramWebJson`,
`HologramWebRegistries`, `HologramWebAssets`), `client/HologramWebBridge` (adapter to `HologramScreen`), browser app in `web/`.

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
  data: blob:; media-src blob:; font-src 'self'; connect-src ws://127.0.0.1:<port>; base-uri 'none';
  form-action 'none'; frame-ancestors 'none'`. `data:` carries asset thumbnails; `blob:` is the page's own
  object URL for a video it converts locally. Nothing is ever fetched from another origin.
- Limits: 24 open sockets, 4 WebSocket clients, 16 KB request head, 1 MB per message (close `1009`),
  120 messages/s with a burst of 240 (excess is dropped with a `rate` error at most once a second; 600 drops
  closes with `1008`), 512 queued outbound messages (overflow closes with `1008`).

## Messages

Text frames, one JSON object each, with a `type`. Unknown fields are ignored; unknown types get an `error`.

**Version 2** (1.44.0) adds layer `uid`s, the spec's `timeline`, the `playhead` message, `timelineCaps` in
`hello` and `registries.timeline`. A version 1 page still works against a version 2 game as long as it sends
back the `uid`s and `timeline` it was given (a missing `timeline` removes the timeline).

**Version 3** (1.44.0) adds the `IMAGE` layer kind, `assetCaps` in `hello`, and the asset messages (library
listing, thumbnails, chunked uploads; see [Assets](#assets)). A version 2 page still works: it sends back an
image layer's `props` as it got them, and it never sends asset messages.

**Version 4** (1.44.0) adds audio modulations (effect `audio`, timeline `audio`) and the `meter` message. A version 2 or 3 page
that sends back what it was given keeps them; one that rebuilds effects from scratch drops them.

### Game -> browser

| type | fields | when |
|---|---|---|
| `hello` | `protocol` (4), `mod`, `modVersion`, `minecraft`, `hologram` (id), `registries`, `timelineCaps` (`maxTracks`, `maxKeys`: this server's caps), `assetCaps` (v3, see [Assets](#assets)) | on connect |
| `state` | `rev`, `ack`, `canUndo`, `canRedo`, `spec` | on connect, after every handled message, and whenever the spec changes in game |
| `selection` | `kind` (`root`/`group`/`layer`), `layer`, `group`, `soloLayer`, `soloGroup` | on connect and when it changes |
| `error` | `seq`, `code` (`invalid` incl. malformed JSON, `unknown` type, `rate`, `internal`; v3: `upload`, `assetThumb`), `message` | a message was refused |
| `pong` | `seq` | answer to `ping` |
| `playhead` | `seq`, `mode`, `time` | answer to `playhead` (no `state` follows: nothing in the spec changed) |
| `assets` | v3: one library page, or `error` | answer to `assets` |
| `assetThumb` | v3: `id`, `data` (`data:image/png;base64,...`) | answer to `assetThumb` |
| `uploadStarted`, `uploadAck`, `uploadProgress`, `uploadDone` | v3, see [Uploads](#uploads) | during an upload |
| `meter` | v4: `source`, `level`, `bass`, `mid`, `high`, `beat` (each 0..1, 3 decimals), `system` (system audio status text) | once per client tick (20 Hz) while this connection asked for it; never carries samples |
| `closed` | `reason` (`saved`, `cancelled`, `editor closed`, `left the world`, `game closed`, `replaced`, ...) | session ends |

`ack` is the highest `seq` the game has handled from this connection. The browser keeps its local copy while
`ack` is below the last `seq` it sent, so a late `state` cannot undo a keystroke.

`registries`: `blocks` (`id`, `name`, `blockEntity?`, property refs), `properties` (shared property table:
name and values), `entities` (`id`, `name`, `animations`: offered entity-effect type names), `particles` (ids of simple particle types), `effects` (per type:
`label`, `short`, `family` fx/motion/entity, which fields it uses, amount `min`/`max`/`step`/`decimals`),
`triggers`, `presets`, `revealStyles`, `limits` (max layers/groups/effects, name length, and a `{min,max}`
range per numeric field), `timeline` (`length` range, `modes`, `eases`, `props` with `min`/`max`/`stepped`).

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
| `assets` | v3: `offset` | one page of this world's asset library |
| `assetThumb` | v3: `id` | a small PNG of the asset's first frame |
| `upload`, `uploadChunk`, `uploadCancel` | v3, see [Uploads](#uploads) | uploads a file to the library |
| `meter` | v4: `on` (bool), `source` (`AUTO`/`JUKEBOX`/`MUSIC`/`SYSTEM`, optional) | starts or stops the level stream for this connection. Sent without `seq` by the page, since nothing in the spec changes. Asking for it keeps the game's analysis awake (as an open audio panel in game does) |
| `playhead` | `mode` (`play`/`pause`/`live`), `time` (0..600 s) | preview only, never saved: `pause` draws this hologram's timeline at `min(length, time)`; `play` runs it from `time` on real time in the clip's play mode; `live` hands the clock back to the game. Dropped when the session ends |

## Spec

```jsonc
{
  "id": "amethyst_block_1", "pos": [0, -59, 2], "owner": "...",           // read only
  "root": { "offset": [0, 0, 0], "rotation": [0, 0, 0], "scale": 1 },
  "renderDistance": 64, "maxRenderDistance": 128,                         // max is read only
  "reveal": { "radius": 0, "seconds": 0.6, "style": "Rise" },
  "groups": [ { "id", "name", "visible", "offset", "rotation", "scale",
                "spin", "spinAxis", "hover", "hoverPeriod", "fullbright", "effects" } ],
  "layers": [ { "kind": "BLOCK|ENTITY|PARTICLE|STRUCTURE|IMAGE", "id", "props": { "facing": "north" }, "group": "",
                "visible", "offset", "rotation", "scale", "spin", "spinAxis", "hover", "hoverPeriod",
                "fullbright", "particleRate", "particleSpeed", "effects",
                "data": { "key": "1a2b-3c4", "bytes": 812, "snbt": "..." },
                "uid": "l3" } ],                                            // v2
  "timeline": {                                                          // v2, optional / null
    "length": 4, "mode": "LOOP|PINGPONG|ONCE", "start": "ALWAYS",       // start: any effect trigger name
    "tracks": [ { "target": "ROOT|GROUP|LAYER", "id": "l3",              // group id / layer uid; "" for ROOT
                  "prop": "OFFSET_X|OFFSET_Y|OFFSET_Z|ROT_X|ROT_Y|ROT_Z|SCALE|VISIBLE|TINT|EFFECT",
                  "effect": 0,                                           // EFFECT only: index into the effects
                  "keys": [ { "t": 0, "v": 1.5, "ease": "LINEAR|HOLD|EASE_IN|EASE_OUT|EASE_IN_OUT|CUBIC",
                              "bezier": [0.33, 0, 0.67, 1] } ] } ],      // bezier: CUBIC only
    "audio": [ { "target": "ROOT|GROUP|LAYER", "id": "l3",             // v3, optional
                 "param": "OFFSET_X|...|SCALE|VISIBLE|BRIGHT|RATE", ...audio modulation fields } ]
  }
}
```

Timeline semantics: a key's `ease` shapes the segment that leaves it (the last key's is unused). `bezier` is
CSS `cubic-bezier(x1, y1, x2, y2)` in segment space: x clamped to 0..1, y to -2..3. `TINT` values are packed
`0xRRGGBB` and blend in OKLab; `VISIBLE` is 0/1 and always steps. `ROT_Y` is the spec's yaw
(`rotation[0]`), `ROT_X` pitch, `ROT_Z` roll. A keyed property replaces its static value; motion effects
(SPIN, BOB, ...) still add on top. Before the first key a track holds the first value, after the last the last.
Particle layers ignore the timeline.

Effect: `{ "type", "strength", "speed", "color", "color2", "axis" (0 Y, 1 X, 2 Z), "phase", "trigger", "audio"? }`.

Audio modulation (v3): `{ "param", "source", "signal", "amount", "sensitivity", "attack", "release", "threshold",
"min"?, "max"? }`.

- `source`: `AUTO` (loudest of jukeboxes, game music and, if the player allowed it, system audio), `JUKEBOX`
  (records-category sounds), `MUSIC` (music category) or `SYSTEM` (this PC's output or an input device, off by
  default, per player).
- `signal`: `LEVEL`, `BASS` (20-250 Hz), `MID` (250-4000 Hz), `HIGH` (4-16 kHz) or `BEAT` (onset pulse).
- The signal is multiplied by `sensitivity` (0.1..8), clamped to 0..1, then smoothed with `attack`/`release`
  (seconds, 0..2 / 0..4). `threshold` above 0 turns it into a step (1 while over the threshold).
- The value becomes `base + amount x envelope`, clamped to `min..max` when given. `|amount|` is capped per param:
  STRENGTH 720, SPEED 20, PHASE/HUE/VISIBLE/BRIGHT 1, OFFSET_* 8, ROT_* 360, SCALE 4, RATE 4.
- Effect `param`s: `STRENGTH`, `SPEED`, `PHASE`, `HUE`. Timeline links take the others. `RATE` (timeline
  playback rate, `1 + sum`) is root only. A positive `VISIBLE`/`BRIGHT` amount starts from off at silence.
- Several modulations of one value add up.

### Image layers (v3)

`kind: "IMAGE"`, `id` = the asset's hash (64 lowercase hex). `fullbright` means emissive (drawn at full light).
Settings are strings in `props`; a missing key is the default, unknown keys are dropped, and the game clamps
every value (`HologramImage#normalize`):

| key | values | default |
|---|---|---|
| `w` | width in blocks, 0.05..16 | `1` |
| `h` | height in blocks, 0.05..16; `0` or missing keeps the picture's aspect ratio | missing |
| `billboard` | `FIXED` (a flat panel that turns with the layer), `FACE` (always faces the camera), `FACE_Y` (turns about Y only) | `FIXED` |
| `sides` | `1` single-sided; anything else double-sided (only `FIXED` can be seen from behind) | double |
| `play` | `LOOP`, `ONCE`, `PINGPONG` (animated assets only) | `LOOP` |
| `speed` | -8..8, negative plays backwards | `1` |
| `start` | seconds into the animation, 0..600 | `0` |

An animation runs on the hologram's clock: the timeline's clip time when the hologram has a timeline (so the
`playhead` message scrubs it too), otherwise game time. The timeline animates image layers like any other layer
(offset, rotation, scale, visible, tint, effects).

## Validation

`HologramWebJson.read` rebuilds the spec from scratch and rejects the whole edit (`error`, nothing changes) on:
unknown block/entity/particle id, an image layer whose `id` is not 64 hex digits, a property or value the block does not have, wrong JSON types, more than the
layer/group/effect limits, unknown effect type or trigger, a layer `uid` that does not match `[A-Za-z0-9_-]{1,24}` (a repeated one is kept by the first layer and
the copy gets a new one),
and in the timeline: an unknown mode/start/target/prop/ease, a track whose group or layer does not exist, an
`EFFECT` track on the root or past the target's effect count, two tracks for the same property, a `CUBIC` key
without four `bezier` numbers, or more tracks/keys than `timelineCaps`; for audio: an unknown param/source/signal,
an effect param on a timeline link or a timeline param on an effect, `RATE` off the root, a link to a missing group
or layer, or more modulations per value / per hologram than the server's `hologramAudioMaxPerValue` /
`hologramAudioMaxPerHologram`. Key times are clamped to 0..length and
snapped to 1 ms, values to the prop's range. Numbers are clamped to the `limits` ranges, names are
trimmed to 32 printable characters, `id`/`pos`/`owner` are always taken from the game's copy, and the render
distance is capped at the server's maximum. An empty layer list gets a stone layer back.

**Block-entity and entity data never comes from the browser.** The game sends `data.key`
(`hex(hash)-hex(size)`), a byte count and a read-only SNBT preview; the browser sends back only the key.
The game maps keys to tags it sent this session (128 most recent); an unknown key means no data. So a page
cannot inject NBT, and a forged key just drops the data.

The game checks the timeline caps again on every save path (`HologramNetwork`: Done, styles, web save), so a
client with a stale or patched copy of the caps cannot store more.

The server checks image layers again on every save path: every hash must be in the world's library (an
unknown one refuses the whole save: "image layer uses an asset that is not in this world's library"), and a
hologram may hold at most `hologramImageMaxLayers` (server config, default 8) image layers. The page cannot
know the library for certain, so a well-formed unknown hash reaches the editor and is refused only on save.

## Assets

v3. The page never talks to the Minecraft server. Every asset message goes to the game, which forwards it over
the normal asset payload (`hologram/asset/AssetNetwork`, registrar `"2"`) and relays the answer.

`hello.assetCaps`: `available` (false when the server has no Fox's Tweaks or one older than 1.44.0),
`chunkBytes` (512 KiB), `maxUploadBytes` (16 MiB, the transport limit), `maxImageLayers`, and the server's own
asset limits `maxFileBytes`, `maxDimension` (px), `maxFps`, `maxSeconds`. A page converting video itself must
stay inside those or the server rejects the result.

**Library.** `assets {offset}` answers with `{type: "assets", seq, canUpload, video, maxBytes, count, offset,
entries: [{id, name, type: "IMAGE"|"ANIMATION", width, height, frames, durationMs, bytes, used, mine, owner,
created}]}` or `{type: "assets", seq, error}`. Pages are cut by size; ask again from `offset + entries.length`
until `count` is reached. `canUpload` follows the server's `assetUploadEveryone` (otherwise operators only);
anyone who may edit the hologram may place an asset already in the library. `video` says the server has
ffmpeg. `used` counts the image layers that use the asset; the server refuses to delete one in use.

### Uploads

One upload at a time per session, in order:

1. `upload {name, size, mode}`: `mode` is `image` (PNG, JPEG, GIF), `video` (anything ffmpeg reads; only when
   `video` is true) or `bundle` (FTAS, below). `size` 8 bytes to 16 MiB. Answer: `uploadStarted {seq, upload,
   chunkBytes}` or `error {code: "upload"}`.
2. `uploadChunk {upload, index, data}`: `data` is base64 of at most `chunkBytes` bytes, `index` from 0 with no
   gaps. Each chunk is answered with `uploadProgress {upload, percent, stage, server: false}` and then
   `uploadAck {upload, index}`; send the next chunk only after its ack. A wrong index, size or encoding aborts.
3. After the last chunk the game hands the file to the server. `uploadProgress {server: true}` messages report
   its stages (ffmpeg, frame checks); then `uploadDone {upload, ok: true, hash}` or `uploadDone {upload,
   ok: false, message}`.

`uploadCancel` aborts an upload before its last chunk; after that it cannot be called back and `uploadDone`
still arrives. The server sanitises every upload (decodes, re-encodes, applies the limits) and names it by the
hash of what it stored, so the same picture uploaded twice is one asset. It takes one upload per player per
second ("Upload rate limit: wait one second"); the page waits out that second itself.

**FTAS v1** (`hologram/asset/AssetFrames`) is how a page without server ffmpeg sends a video it decoded with
WebCodecs: big-endian `int` magic `0x46544131`, `int` frame count (at most 900), then per frame `int` delay in
ms, `int` length, and that many bytes of PNG or JPEG. The server checks it like any other upload: frames are
decoded and re-encoded (JPEG stays JPEG), delays are raised to at least `1000 / maxFps`, and the size,
dimension and length limits apply.
