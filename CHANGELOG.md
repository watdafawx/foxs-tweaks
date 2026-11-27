# Changelog

## 1.52.0

- **Higher hologram asset limits & auto-downscaling model textures.** Raised the per-asset ceiling (`assetMaxFileMb` max and transfer cap) from `16 MB` to `64 MB`, and 3D model imports now accept up to `64 MB` embedded textures and automatically downscale oversized textures (`1024 -> 512 -> 256 px`, ≤2 MB per PNG) so heavy `.glb`/`.bbmodel` files shrink cleanly.
- **Direct URL streaming for 3D models & optional WATERMeDIA video/live streams.** Hologram `MODEL` layers can now take `http(s)://` URLs directly (`.glb`, `.gltf`, `.bbmodel`, `.json`): clients download over HTTPS and cache canonical `FTM1` blobs on disk with zero Minecraft server bandwidth. Hologram `IMAGE` layers with `http(s)://` URLs play big videos and live streams (YouTube, Twitch, Kick, HLS/DASH, `.mp4`, etc.) with distance-attenuated audio when **WATERMeDIA** is installed (strictly optional). Add them via the new **Stream** button in the in-game Pictures/Models library, **Stream URL** in the web editor, or `/foxstweaks hologram url <id> <image|model> <url>`. Server toggle: `assets.hologramAllowExternalUrls` (default `true`).
- **Fixed: holding Shift on sliders in the hologram editor flew the camera down.** Camera WASD/Space/Shift movement is now blocked while the cursor is over any editor panel in both Creator and Studio, so holding Shift for fine slider scrolling/dragging no longer moves the camera away.

## 1.51.1

- **Attribute Dial: normal modifier names, formatted values and Apothic Attributes icons.** Hover tooltips now use Apothic Attributes' own `ModifierSource` icons (armor, hands, Curios, mob effects) and formatted modifier lines (`+8 Armor`, `+6% Attack Speed`) instead of raw internal IDs (`apotheosis:armor/attribute/ironforged`, `minecraft:armor.boots`), along with the attribute's `.desc` description. Percentage attributes now display with `%` in the list and editor, and the editor subtitle shows the description or mod name instead of the raw attribute ID (shown only with `F3+H` advanced tooltips).

## 1.51.0

- **Player-attached holograms.** Any hologram can now be attached to a player so it follows them across dimensions and relogs (and hides automatically while they are offline).
  - Three follow modes: **Position** (floats at offset, keeps its own rotation), **Body** (rotates with body yaw), and **Head** (follows head yaw and pitch, pivoting around eye height).
  - **1st person: Hide / Show** toggle (default Hide so it does not block your own camera, while staying visible in third person and while the hologram editor camera is open).
  - Toggle and configure from **Whole hologram** in Creator, **Root** in Studio, **Player Attachment** in the web editor, or `/foxstweaks hologram attach <id> [player] [position|body_yaw|head_look]` and `/foxstweaks hologram detach <id>`. Players can attach their own holograms to themselves; operators can attach to any player. Detaching drops the hologram back into the world at the player's current position.

## 1.50.1

- **Hologram asset size limits are now configured in MB instead of bytes.** In `serverconfig/foxstweaks-server.toml` under `[assets]`, `assetMaxFileBytes` is now `assetMaxFileMb` (default `2`, range `1`–`16`) and `assetMaxWorldBytes` is now `assetMaxWorldMb` (default `200`, range `1`–`4096`), making them easier to edit in the in-game settings screen. Because the key names changed, existing worlds fall back to the defaults (`2` MB per asset, `200` MB per world) until re-edited.

## 1.50.0

- **Attribute Dial: vanilla default is the floor.** The dial now goes down to the attribute's vanilla value instead of 0: block interaction range 4.5 dials between 4.5 and what your gear gives, luck (vanilla 0) still goes to 0. The first preset reads **Base** when vanilla is not 0 (**Off** otherwise), the slider's left end shows the value, and a "Vanilla default" line says it. Never below vanilla, never above gear. Percentages are now a share of that range (50% of 4.5..64 is 34.25). Network version bumped: update client and server together.
- **Attribute Dial: hover a row** to see where its value comes from, like Apothic's panel: current value, base, min/max, each modifier (id and amount) and the dial's own share.

## 1.49.0

- **Attribute Dial.** A new screen turns any of your own attributes down to 0-100% of what your gear gives you, so you can keep every item and still drop, say, a 200 block interaction range you do not want. Open it with the inventory button (top-right corner of the inventory) or the new **Attribute Dial** key (Controls, unbound by default). It has a search box (words in any order, or letters in order: `blkrng`), a **Hide unchanged** toggle like Apothic's panel, a slider, Off / 25% / 50% / 75% / Full presets, mouse-wheel fine steps, and **Reset all dials**. Dialled rows show the gear value struck through and your value in amber. Gear is never touched: the dial is one extra multiplier applied last, kept per player (survives death and relogs) and re-fitted to new gear every half second, so an attribute capped by its maximum (200 reach on a 64 cap) still responds to the dial. It can only lower, never raise past what you have. Works on any attribute in the registry, including Apothic Attributes' ones; it cannot change effects that are not attributes (many affix procs, enchantment and potion effects). Needs Fox's Tweaks on the server too. Config: `misc.attributeDial` (default on; off removes every dial).

## 1.48.0

- **3D models in holograms.** A hologram layer can now be a 3D model with animations, loaded like pictures: upload a `.glb` / `.gltf` (glTF 2.0, skinned or not, with its animations), a Blockbench `.bbmodel` (cubes, meshes, groups, keyframe animations) or a vanilla block-model `.json` (cubes only, drawn untextured) and it joins the world's asset library as a **3D** tile. Add it as a layer from the library (Creator: the ▣ button; web editor: the Images tab, double-click or +), or drop the file on the editor. Per layer: **Animation** (cycle the model's own clips), **Loop** (loop / once / ping-pong), **Speed** (negative plays backwards), **Height** in blocks (0 keeps the model's own size); Glow, Size, tint, effects, the timeline and the 3D handles work as for any layer. Models follow the hologram clock, so the timeline playhead scrubs them. At most 4 model layers per hologram; each model is capped at 65,536 vertices, 512 nodes, 8 textures. Drawn on the CPU each frame, so keep them modest. Server-side the file is re-read and re-encoded (never stored as sent). Web protocol 7. Blockbench Catmull-Rom/Bezier keyframes play as linear; glTF morph targets, sparse accessors and cameras/lights are ignored. Not yet checked: Blockbench's own rotation and face orientation against a real Blockbench render.
- **Pictures can always face you.** New **Faces** chip on picture layers in the simple editor: *as placed*, *you (upright)* (turns around the vertical axis only) or *you (always)* (full billboard). The web editor already had this (Facing).

## 1.47.3

- **New picture layers no longer glow.** Pictures were added with Glow on by default, in game and in the web editor, which nobody asked for. They now start with Glow off like every other layer; turn it on per layer if you want a self-lit picture. Layers you already made keep what they saved.

- **The hologram editor has its own GUI scale.** New **Scale: Game / 1 / 2 / 3 / 4** button in the simple editor's top bar (second row). Pick 2 and the editor uses scale 2 while vanilla menus stay at your Video Settings scale; the game's scale comes back when the editor closes. Applies to every editor screen (Studio, picker, pictures, audio) and is remembered. Default is Game (follow Video Settings).
- **Simple (Creator) hologram editor now has the 3D handles.** Move, Rotate and Scale arrows/rings appear on the selected layer, dragged with the left mouse button like in the full editor. Mode buttons sit in a second row under the top bar; keys 1-4 switch (Move, Rotate, Scale, Off); Esc during a drag undoes it. Same gizmo code as the full editor, so the maths is identical.
- **Simple editor: exact position and whole-hologram controls.** New collapsed **PLACE** section (X/Y/Z, yaw/pitch/roll sliders + Zero position; drag or scroll, Shift fine, Ctrl coarse) moves the same values as the handles. New **Whole hologram** row at the top of the layer list selects the root: place, Size, render Range and In-front preview for every layer at once, with the handles acting on it. Selected layer rows get ▲/▼ buttons to reorder. Not yet in the simple editor: groups, layer copy/paste, solo, blockstate properties.
- **Pictures in game.** The simple editor has a new ▣ button in the layer header that opens the world's picture library as a grid; click one to add it as an image layer. Add new pictures without the web editor: drag a file onto the game window, or type a file path or an http(s) address and press Upload. Pictures that are too big (over 4 MP / 4096 px or the byte cap) are scaled down and re-encoded before upload, and a GIF that is too long, too fast or too heavy is thinned into a frame bundle, so they go through instead of being refused. Status line says what changed. Source files up to 64 MiB are opened for fitting (the upload stays capped after fitting). The library now has a search box (name, type or hash start) and right-click twice on a picture to delete it. The fit uses the server's real caps (sent with the listing). WebP is refused with a clear message (use the web editor).

## 1.47.2

- **Taverns & Adventurers: rumor lookups can no longer freeze the server.** Its rumor and dungeon-map searches ran a structure search on the server thread, which on a big pack hung for a minute and tripped the watchdog (server crashes on DrunkGamblers). The search now runs on a helper thread and the tick waits at most `tavernsLocateWaitMs` (default 4000 ms, 0 = off) before treating it as "nothing found". New config key in the **performance** section; paths unchanged since it is a new key.

## 1.47.1

- **Web editor: animated WebP now animates in every browser.** Browsers without `ImageDecoder` (Firefox) used to keep only the first frame. The page now reads the WebP's frames itself (`web/src/webp.ts`), composites them with their blend and dispose rules, and sends the same frame bundle as for a GIF. Chrome and Edge still use `ImageDecoder`. Nothing changes on the server.

## 1.47.0

- **Web editor: delete pictures and videos from the Images tab.** Each card you may delete (operators: any; everyone else: what they uploaded) has a ✕. It asks first, and when holograms use the picture it says how many layers will turn empty. Web protocol 6.

## 1.46.1

- **Fixed: uploaded videos showed nothing**, in game and in the web editor's library (the preview stayed on "…"). Video frames are stored as JPEG, and the game's JPEG decoding failed silently; they are now decoded the way Minecraft decodes its own images. A frame that still cannot be decoded is logged once instead of failing silently, and the library shows the name instead of waiting forever.

## 1.46.0

- **Web editor: upload any picture, GIF or video, whatever its size or length.** Files over the server's limits are no longer refused: the page scales pictures down and lowers a video's or animation's frame rate and size until it fits. A video or animation longer than the server keeps (10 s by default) opens a dialog to pick which part to keep, with a looping preview.
- **Fixed: a 10-second video converted in the browser was refused** ("Animation duration limit exceeded"): its frames added up to 10.05 s.

## 1.45.0

- **Web editor: real icons.** The content browser, layer list and inspector header in the web editor now show the same block, item, entity and particle icons as the game instead of letter badges. The game draws them only for what is on screen, a few per frame, so scrolling through 48,000 blocks does not stall it. Web protocol 5; an older page still works without icons.

## 1.44.3

- **Hologram Creator: Del button.** The Creator's footer can now delete the hologram, as the Studio's Del does. The first click turns it into "Sure?", and a second click within 3 seconds deletes it.

## 1.44.2

- **Fixed: WASD (and Space/Shift/Q/E) did not fly the camera in the hologram Creator**, the default editor. It only worked in the Studio.

## 1.44.1

- **Fixed: dedicated servers would not start with 1.44.0** ("Attempted to load class com/mojang/blaze3d/vertex/VertexConsumer for invalid dist DEDICATED_SERVER"). A client-only reload listener for hologram structure meshes was registered on servers too.

## 1.44.0

- **Holograms: image layers.** A layer can now be a picture: PNG (with transparency), JPEG, animated GIF, WebP or a short video. Upload them from the web editor's new **Images** tab (button or drag and drop, with progress) into the world's asset library, where anyone editing a hologram can reuse them.
  - Each image layer sets its width (height follows the picture), facing (fixed panel, always facing the camera, or turning about Y only), single- or double-sided, emissive, and for animations playback (loop, once, ping-pong), speed and start time.
  - Video is converted by the server when it has ffmpeg (server config section `media`: `mediaFfmpegEnabled`, `mediaFfmpegPath`, `mediaConvertTimeoutSeconds`), and otherwise in the browser (WebCodecs). Frames are capped at 256 px, 15 fps and 10 s.
  - Storage limits live in the new server config section `assets` (2 MB per file, 200 MB per world, 100 assets per player by default). Only operators may upload unless `assetUploadEveryone` is on. `hologramImageMaxLayers` (8) caps image layers per hologram.
  - `/foxstweaks asset list|info|delete`; delete refuses an asset a hologram still uses unless you add `force`.
- **Holograms: timeline and keyframes (web editor).** The web editor's new Timeline dock animates the hologram, a group or a layer: offset, rotation, scale, visibility, tint (blended in OKLab) or an effect's strength, with linear, hold, ease or bezier keys and a graph editor. Clips run 0.5-120 s and loop, ping-pong or play once, and can wait for a start trigger (day, night, rain, a record playing, a mob dying...). Dragging the playhead scrubs the hologram in game without saving, Space plays, Auto-key keys whatever you change. Motion effects still play on top of the keyed pose, and image animations follow the timeline. The Studio and the Creator show a summary and keep the timeline when you save. Server caps: `hologramTimelineMaxTracks` (64), `hologramTimelineMaxKeys` (2048).
- **Holograms: music-reactive.** Holograms can react to jukeboxes (anything playing in the Jukebox/Note Blocks volume category, not only vanilla jukeboxes), game music, or, if you switch it on, whatever your PC plays.
  - Any effect value (strength, speed, offset, hue) and any hologram, group or layer value (position, rotation, scale, visibility, brightness, playback rate) can follow the level, bass, mid, high or beat, with smoothing, a beat step and a clamp. Several can stack on one value.
  - The Creator has a **Music** section with four presets (Pulse to the bass, Flash on the beat, Dance, Glow with the music); every Studio and Creator stack row has a **♪** button; the ♪ panel shows a live meter. The web editor has the same fields.
  - `/foxstweaks audio` opens the audio settings: system audio on/off, device picker with a level meter, listen at player or at the hologram.
  - System audio is off by default and set per player. Nothing is recorded or sent anywhere: the sound is turned into five levels on your PC and thrown away.
  - Sound from mods that play outside Minecraft's sound engine (WaterMedia/VLC, Simple Voice Chat) is only heard through system audio.
  - Server caps: `hologramAudioMaxPerValue` (4), `hologramAudioMaxPerHologram` (64).
- **Holograms: entities come alive.** Entity layers can walk, run, fly (bee, parrot, bat, chicken, phantom, allay...), swim (fish, squid, axolotl, frog, guardian...) and play some of their own vanilla animations: warden roar and sniff, sniffer sniff, frog croak, armadillo curl, sitting and resting. Every entity layer animates on its own, from the hologram's clock, so scrubbing works and nothing runs AI. The editors only offer what that mob can do.
- **Holograms: fluids.** Structures grabbed from the world, and single-block layers, now show water, lava, kelp, seagrass and waterlogged blocks, with the same face culling, flow and biome tint as the world, animated textures, and every colour effect. Water is translucent and sorted to the camera. A half-water 16x16x16 structure still draws in ~0.07 ms.
- **Holograms: editing from farther away.** Save, Delete and live preview now work from up to 64 blocks away while the editor is open (server option `holograms.hologramsEditRange`, 14-512). With no editor open the limit stays 14 blocks. A refused save now says so in chat and names the limit, instead of silently dropping the change.
- **Holograms: editor fixes.** Creator and Studio text fits at GUI 3 and 4 (the empty-stack hint wraps, long layer names are shortened with the full name in the tooltip, the "+ Save" tile is padded, the timeline label shortens). In the block browser an exact name match ranks first ("oak stairs" lists Oak Stairs first), and wall variants of signs and banners get a "(wall)" tag.
- **Holograms: web editor fixes.** It works in windows narrower than 720 px, no longer logs a favicon error, and once the session has ended it hides Undo/Redo/Cancel/Save and locks the page.
- Network version for holograms is now 4 and the web companion speaks protocol 4: a client and server must both be on 1.44.0 to edit holograms (joining still works either way).

## 1.43.0

- **Holograms: Creator, a simple editor.** The Hologram Wand now opens the Creator by default: layers on the left, and on the right Shape (size, glow), Colour, Look FX, Motion and Reveal. The full editor is one click away (**Studio ›**, and **< Simple** to come back), and your choice is remembered.
  - **Colour:** None, Solid or **Gradient** (two stops, swap, hex box, colour wheel, swatches). Gradient is a new effect and also works in the Studio.
  - **Looks:** one click sets a style (Holo, Ghost, Glitch, Shrine, Neon, Prism, Plain) without touching your motion. Save your own look; right-click a saved look to delete it.
  - **Effect stack:** drag to reorder, strength bar, axis and trigger chips, expand for speed and offset.
  - Undo/Redo, Frame, Pause in the top bar; Done saves, Cancel throws the edit away.
- **Holograms: block browser.** **Browse** (or **B**) in the Studio, and **+** in the Creator, open a full-screen picker: search, colour and shape filters, favourites, recent, variants, block families and swap chips.
- **Holograms: grab from the world.** **Grab** (⌖) lets you point at a block or mob and take it as a layer: `[1]` block, `[2]` its state, `[3]` its contents (chest items, sign text, mob NBT). Click to grab and return, Shift-click to grab several.
- **Holograms: structure grab.** In Grab mode, press `[4]` or Tab, click two corners and press Enter: the whole box becomes one layer that keeps every block's state and contents. Server limits (server config): 32 blocks per side, 4,096 blocks, 256 KB. Structures are drawn from a cached mesh: a 16x16x16 build costs ~0.1 ms per frame instead of ~2.7 ms. Scanline and gradient effects on a structure skip the cache.
- **Holograms: web editor (preview).** **Web** in the Studio and the Creator (or `/foxstweaks hologram web <id>`) opens an editor in your browser: outliner with groups, inspector, effect stack, content picker. It is served by your own game on 127.0.0.1 only, behind a one-time token; the game window stays the preview and the game checks every change. Timeline, images, video and audio come later.
- **Holograms:** the studio camera no longer switches off while the Creator or the block browser is open.
- Network version for holograms is now 3: a client and server must both be on 1.43.0 to edit holograms (joining still works either way).

## 1.42.1

- **FPS report:** the title and help text now sit on a dark panel, so they can be read over any world. The chat `[Open report]` button opens the report straight away instead of waiting for you to close the chat.

## 1.42.0

- **Holograms: triggers.** Every animation and FX can now play only when the world says so. Each card in the Anim and FX tabs has a small button (default **Any**); click it to cycle:
  - **Day / Night** follow the sun and ease through dusk and dawn.
  - **Rain / Thunder** follow the weather.
  - **Jukebox** plays while a music disc is playing within 24 blocks.
  - **Music** plays while the game's own background music is playing.
  - **Death** plays one cycle each time a mob dies within 24 blocks.
  - Effects fade in and out instead of snapping. A Spin that is switched off holds its angle and carries on from there.
  - Old holograms are unchanged (Any = always on). Costs nothing for holograms that use no trigger.
- Dev check `runHologramCheck` covers triggers: save round trip, music/jukebox/death/day/night gates, eased in/out, spin continuity.

## 1.41.0

- **Holograms: stackable animations on layers and groups.** The Anim tab is now a list instead of one Spin and one Hover:
  - Add as many as you like to a layer or a group: **Spin, Sway, Bob, Orbit, Slide, Breathe, Heartbeat, Flip, Bounce**. The same one can be added twice on different axes (Spin on X *and* Y), each with its own amount, speed (Hz), phase and axis. Up to 12 per target.
  - **Entity animations** for entity layers and groups: **Walk, Attack, Hurt, Death, Sneak** play the entity's own game animation.
  - **Presets** (`~Float`, `~Coin`, `~Orbit`, `~Roll`, `~Beat`, `~Hop`), **Copy / Paste anims** between layers and groups, and a button that converts an old single Spin/Hover into list entries. Old holograms keep working unchanged.
  - Editor top bar: **Pause / Play** and **T=0** to scrub the animation clock while editing.
- **Holograms: editor camera starts framed.** Creating or editing no longer drops the camera inside your head: it starts behind and above, looking at the hologram. **F** re-frames it.
- **Holograms: chat works while editing.** Press the chat key (or `/` for a command) in the editor; closing chat puts you back in the same editor with your edits and undo history.
- **Holograms: smoother animation.** Entity layers aged with the hologram clock but interpolated with the game's partial tick, so idle animations jumped back a few times a second. They now use one clock. Rig check: 710 frames, none backwards or stalled, age tracks real time within 1%.
- Dev check `runHologramCheck` grew an editor-UI section (real button clicks, camera framing, chat round trip) and a frame-smoothness section.

## 1.40.0

- **FPS hotspots: report screen, by-type totals, clickable chat, clearer overlay** (`/foxstweaks fps`):
  - **Report screen** (`/foxstweaks fps report`, the chat link, or the new unbound key *Open FPS Hotspot Report*): By type / Individual lists with % of frame, µs, "+FPS if it cost nothing", mod name, distance + direction, and buttons to show it in the world (all of a type, or one with a tall beam), look at it, teleport next to it (needs op), copy coordinates, ignore a type. Shows the spark link when the scan ran with `/sparkc profiler`.
  - **By type**: every instance of a block entity / entity type added together, so 200 cheap drawers show up as one big row instead of 200 hidden ones.
  - **You are no longer highlighted**: the local player (and camera entity) is kept out of the boxes and listed as one "You" line; entities that are not in the world (GUI paper-dolls, preview entities, the old `(0, 1, 0)` rows) are ignored; times are self time (nested draws no longer count twice).
  - **Heat colours by share of the frame**, not fixed µs, so they read the same at 30 and 300 FPS.
  - **Top-left list**: top 5 with rank, %, µs, distance and a direction arrow, plus your own cost.
  - **Labels**: numbered to match the list (`#3`), constant size on screen (they were huge up close), white text with a coloured rank (dark green was unreadable under shaders), range 160 blocks, top 20 in rank order.
  - **Chat**: top 3 with `[Look] [Go] [Show]`, the biggest type with `[Show all]`, `[Open report]`. New commands `show item|group <n>|off`, `look <n>`, `tp <n>`.

## 1.39.0

- **Holograms: unified Layers & Groups, independent animation/FX, Solo, live multiplayer edits, Undo/Redo, and Free Studio Camera**:
  - **Unified first-class Layers (`HologramLayer`, up to 16)**: replaced the split base-block + `parts[]` / `minecraft:air` override model with a single uniform layer list. Every Block, Entity, and Particle layer has its own position, pitch/yaw/roll, scale, spin, hover, fullbright, FX list, visibility, and block-entity NBT data. Particle layers add dedicated `Spread`, `Rate` (`/s`), and `Rise` speed controls and spawn from exact transformed world positions.
  - **Groups (`HologramGroup`, up to 8)**: create named groups to link transform, spin, hover, fullbright, and FX across member layers while ungrouped layers remain completely independent in the same hologram.
  - **Solo editing**: click `S` on any layer or group to isolate it locally while editing without modifying visibility flags for other players.
  - **Live multiplayer previews & safe Revert/Cancel**: edits stream live (throttled) to all players in the dimension before pressing `Done` so others can review in real time. `Done` saves permanently; `Cancel` / `Esc` / disconnect reverts everyone to the saved state; `Revert` restores the opened snapshot (undoable).
  - **Full Undo & Redo history**: 64-step history (`Ctrl+Z`, `Ctrl+Y` / `Ctrl+Shift+Z`, and toolbar buttons) covering layer/group transforms, content picks, FX, animations, and hierarchy edits.
  - **Free Studio Camera**: detached client-side camera while the editor is open (`W/A/S/D` + `Space`/`E` & `Shift`/`Q` to fly, scroll on viewport to dolly zoom, right-drag to look, middle-drag to pan, `F` / `Cam` to reset). Gizmo hotkeys moved to `1`–`4` (`Move`/`Rotate`/`Scale`/`Off`) so `S` flies backward cleanly.
  - **Backwards-compatible world migration**: existing saved holograms auto-migrate to the unified layer/group format on load.

## 1.38.3

- **Hologram Content Picker visual previews & hover zoom**:
  - **Live 3D entity icons** in the Entity picker list (auto-scaled to bounding box, scissored, lazily cached per visible row with crash guard and spawn-egg fallback).
  - **Animated particle sprite icons** in the Particle picker list (lazily resolved from `particles/<id>.json` and blitted directly).
  - **Docked hover zoom preview card** beside the left panel for Blocks (3× zoom), Entities (spinning 3D preview), and Particles (zoomed animated sprite) with name and `mod:id`.

## 1.38.2

- **Holograms: smooth animation, new FX, Reveal styles, JEI/AE2 search, 16 layers**:
  - **Fixed frozen Hover and stuttery/low-FPS animations**: replaced `(float)(gameTime + partial)` + `Mth.sin` (which lost sub-tick precision and overflowed `Mth.sin`'s int lookup table on older worlds, freezing Hover at 0) with monotonic wall-clock `double` time and modular `Math.sin`.
  - **JEI / AE2 search syntax in the Content Picker**: supports `@mod` (mod ID or display name), `#tooltip` (lazily cached item tooltip lines), `$tag` (block/entity tags), `&id`, `-term` negation, `|` OR branches, and `"quoted phrases"`.
  - **New & upgraded FX** (up to 6 stacked layers): added **Scanline** (sweeping horizontal sci-fi bands per vertex, shader-safe), **Glitch** (periodic horizontal offset bursts + cyan/magenta flash), **Pulse** (rhythmic energy brightness surge), and **Rainbow** (HSV chroma cycle); upgraded **Wobble** to squash-and-stretch.
  - **6 Walk-in Reveal styles** (`Animate > Walk-in Reveal`): `Rise`, `Pop` (elastic bounce), `Unfold` (teleporter beam), `Spin` (360° spin-in), `Drop` (landing squash), and `Glitch` (glitch-in).
  - **Raised extra layer (part) limit from 4 to 16** with a scrollable Layers list on the `More` tab.

## 1.38.1

- **Hologram editor 3D-studio UI overhaul**:
  - Unified flat dark-slate styling everywhere (no vanilla grey buttons), RGB axis indicators, cyan active accents, and floating top-center `Move / Rotate / Scale / Off / Undo / Zero` gizmo bar.
  - **Collapsible Content Picker** on the left (`<` / `Content >`): single-clicking any **Block**, **Entity**, or **Particle** directly sets what the hologram shows (entity/particle holograms now inherit main `Scale`, rotation, gizmos, spin, hover, and FX directly). Hovering block grid cells shows name + `mod:id` tooltips; optional `+ Stack Layer` button stacks up to 4 extra layers.
  - **4 clean Inspector tabs** (`Main | Animate | FX | More`): Position/Rotation/Scale/Range/Glow/Preview grouped under `Main`; Spin/Hover/Walk-in Reveal (`Appear` + `Ease`) under `Animate`; direct effect toggle pills + 9 clickable Tint color swatches + `Hz` speed under `FX`; Clipboard, BE Data, and stacked Layers under `More`.
  - **Direct number controls**: drag horizontally to scrub, click to type exact values, scroll wheel to step (`Shift` fine / `Ctrl` coarse), right-click to reset.
- **Hologram fixes & hardening**:
  - Fixed rotation/spin pivoting around the NW block corner instead of block center (now spins in place and aligns with 3D gizmos).
  - Non-glow lighting now samples at the hologram's offset position; server edit/delete/recapture reach checks distance to the offset box too.
  - Guarded entity part creation/rendering with `try/catch` + failed-id cache so exotic modded entities cannot crash the client; passed real `partialTick` for smooth entity animation.
  - Pruned deleted holograms from the client `BlockEntity` cache; cleared stale BE NBT when switching blocks in the picker; expanded hologram bounds to cover stacked parts.
  - Enforced server-side caps (`effects <= 4`, `parts <= 4`, `data <= 64 KB`), required op permissions on `/foxstweaks hologram tp` (and owner/op on `edit`), and teleported to the offset box center.

## 1.38.0

- **Hologram editor & renderer overhaul**:
  - **3D viewport controls**: right-click drag (or left-click drag empty viewport) turns the player camera while the editor is open; 3D Move (`G`), Rotate (`R`), and Scale (`S`) gizmos project over the hologram with `Shift` for free precision and `Ctrl+Z` undo.
  - **Origin offset**: holograms can now move freely off their anchor block (`offsetX/Y/Z`, ±16 blocks) via the 3D Move gizmo or the `Move` tab (`Zero` button / right-click resets to 0). Picking, outline, frustum culling, appear radius and particles all follow the offset.
  - **Entity & particle pickers**: the left panel now has **`Block` | `Entity` | `Partcl`** mode tabs with search, spawn-egg icons for entities, and double-click / `+ Add` to add parts.
  - **Working draggable scrollbar**: click track to jump, drag thumb to scroll, or scroll wheel over the track across all three pickers.
  - **Per-part & per-effect fine controls**: selecting a part on `Parts` exposes inline `X / Y / Z / Size / Yaw` controls; selecting an effect on `FX` exposes `Strength` plus **Tint color presets** (9 colours with swatch) or **Speed (Hz)** for animated effects.
  - **Fixed half-rendering / missing modded blocks (Ad Astra, BER + ModelData)**:
    - `EntityBlock`s picked fresh from the picker now instantiate a default client-side `BlockEntity` even with empty NBT so blocks with both a model and a BER (Ad Astra Oxygen Distributor, Gravity Normalizer, Energizer, etc.) draw both halves, and `RenderShape.INVISIBLE` `EntityBlock`s (flags, sliding doors, banners, beds) appear in the block picker.
    - Client-side hologram block entities now tick properly, pass `BlockEntity#getModelData()` to `renderSingleBlock`, invalidate cache on blockstate property changes, and guard modded BER/ticker calls against crashes.
    - Wand placement now preserves the clicked block's blockstate properties.
  - **Generic `getUpdateTag` capture & BE data controls**: server captures `BlockEntity#getUpdateTag` alongside `saveWithoutMetadata` and replays `handleUpdateTag` client-side, reproducing client visual state (including powered **AE2 Drives** and cell models) with no per-mod shims. `Share` tab now has `Re-capture BE data` and `Clear BE data`.
  - *(Note: the 1.37.0 release notes still said "no block entity" in one bullet; holograms do carry captured block entity data client-side without adding a block entity to the world.)*

## 1.37.0

- Added **holograms**: the craftable *Hologram Wand* projects a block into the world as decoration.
  - Right-click a block to project a hologram of it on the face you touched and open the editor; right-click an existing hologram to edit it; sneak + right-click it to remove it.
  - Show only by design: no collision, no light, no block entity, no tick, nothing to break. A hologram is one saved row and one model draw per frame while it is on screen.
  - The editor lists the block's own blockstate properties as clickable rows (`state: online`, `type: block`, `facing: north`, ...), so any block's variants are reachable - including **AE2's controller**, whose animated lights are an animated texture on its normal model and whose `offline`/`online`/`conflicted` states are two enum properties. No AE2-specific code.
  - Per-hologram scale, yaw/pitch/roll, spin speed and axis, hover height and period, glow, and render distance.
  - The editor is **two small panels on the screen edges** (block grid + blockstate properties on the left, values on the right under `Look`/`Move`/`FX`/`Share` tabs) with the middle left clear, so you can see the hologram while you edit it. Every value takes the **mouse wheel**, with a `Step x1/x2/x5/x10` multiplier, and `Share > Preview in front` draws a copy a few blocks ahead for when the real one is behind you.
  - **Copy/paste**: `Share > Copy settings` keeps a hologram's block, states, transform, effects and range; `Paste settings` applies it to the one you have open, `Paste as new` drops a copy in front of you, and **sneak + right-click a block** with the wand pastes one there directly. The clipboard is per-session.
  - **FX tab**: stack up to four effect layers - `Tint` (vertex-colour multiply, default cyan), `Flicker` (hides frames, like a failing projector), `Tremor` (rotation jitter), `Wobble` (slow scale breathing), `Jump` (mistimed vertical snap). Wheel changes a layer's strength, right-click removes it.
  - **Walk-in reveal**: `Appear radius` (0 = always on) grows the hologram in when a player comes within range and out again when they leave, with a 0.6 s ease. Client-side, so every player sees their own approach.
  - **Parts tab**: one hologram can hold a block **and** an entity **and** particles at the same time. Each part has fractional offsets (0.25-block nudges), its own scale and facing, and inherits the parent's transform, effects and appear radius - so a flickering display flickers as one object instead of three. Entity parts are real entities that are never added to the level (no AI, no ticking, no collisions); particles spawn client-side at 10/s per part. A base of `minecraft:air` gives an entity-only hologram.
  - **Batching: measured and rejected.** 48 identical blocks ~120 us/frame; 48 distinct models ~160-188 us - the cost is vertex emission, not model lookup, so state batching cannot help. What did help is **frustum culling**: 48 holograms behind you now cost ~2 us.
  - **Block entities**: chests, signs, beds, banners, shulker boxes and modded machines with status lights render only through a block entity renderer, so the hologram now carries the block's own data. Pointing the wand at a real block copies it (`saveWithoutMetadata`, capped at 64 KB): a chest hologram keeps its contents and open state, a sign its text. The instance is created client-side, never added to the level, and ticked by hand so animation runs.
  - Server config: `hologramsEnabled`, `hologramsMaxPerDimension`, `hologramsMaxRenderDistance`. Client config: `hologramRenderCap`.
  - Admin: `/foxstweaks hologram add|remove|list|edit|tp`.
  - **Perf** (dev check, rig, 48 holograms all in view): 132 us/frame of CPU plain, 135 us with four effect layers each, 4 us after removal - ~2.7 us per visible hologram, effects inside the noise.
  - Dev check: `build.bat runHologramCheck` (game dir `run-hologram/`; put AE2 + GuideME in `mods/` to cover `ae2:controller`). Report, screenshots and the perf numbers land in `run-hologram/`.
  - **Not yet**: block entity renderers (chests, signs, AE2 drive LEDs) draw nothing - only blocks with a real model are pickable; entities and particles are the next phase.

## 1.36.1

- The settings screen is now **grouped into sections** instead of one long list: common (Relics, World, Apotheosis, Nearby Storage, Performance, Mod Integrations, Misc) and server (Overworld Copy Portals, Performance, Mob & Chunk Loader), each opened from its own row. **Settings written by an older version fall back to their defaults once**, because every key moved under its section in the toml; set anything you had changed again.
- The **Tab performance cards are now placeable**: `perfOverlayPlacement` keeps `flank` (Server left of the player list, Client right) or pins the pair to any edge/corner (`topLeft` … `bottomRight`), `perfOverlayOffsetX`/`perfOverlayOffsetY` nudge them, and `perfOverlayServerCard`/`perfOverlayClientCard` hide either card.
  - **In game**: bind *Move Performance Overlay* (unbound by default) — pressed while Tab is up it cycles the presets on the spot; pressed otherwise it opens a drag-to-place screen (drag it, or arrow-key it, then Done saves). A pinned placement also shows `Move: <key>` under the cards.
- Dev check: `build.bat runConfigCheck` (game dir `run-config/`) asserts the sections and that every section and key has a real label, then screenshots the screen. `runServer` also catches a broken config spec headlessly - a `defineInList` with an immutable list crashes config creation, because NeoForge tests a missing key with `null`.
- The **Mob & Chunk Loader GUI** now does the Apotheosis tier too: a `Tier:` button cycles `off` → `haven` → … → `pinnacle` → `follows <owner>`, and the line under it shows what it is on. It reads `Tier: needs Apotheosis` and stays disabled when Apotheosis isn't installed. The tier button writes the same anchor data `/foxstweaks anchor tier` does.
- Added `Name: shown/hidden` to the loader GUI: hides the floating `Mob & Chunk Loader [NxN]` label without touching the anchor itself. Persists on the dummy.
- The GUI payloads grew those fields, so the channel went to version "2"; a client still on v1 keeps working, it just can't open this GUI (payloads stay optional).
- Fixed an update from the GUI being dropped entirely when its tier string could not be validated (no Apotheosis installed, or a tier this build doesn't know): the anchor now keeps its existing tier and still applies the status/radius/name changes.

## 1.36.0

- Added **Apotheosis world tier for the Mob & Chunk Loader** (`spawnAnchorsApotheosisTier`, default on):
  - An anchor remembers the player who placed it and hands Apotheosis a stand-in "player" at the spawn, so mobs spawning around a loaded farm are judged against the owner's world tier instead of nobody's. Bosses (invaders), elites and tier augments now happen with the owner thousands of blocks away or offline; while the owner is online their *live* tier is used.
  - A real player within `spawnAnchorsTierPlayerRange` (128 blocks) always keeps their own tier.
  - `/foxstweaks anchor tier <name> <haven|frontier|ascent|summit|pinnacle|owner|off>` pins a tier or goes back to following the owner; `anchor list` shows each anchor's tier. Anchors placed before this update carry no tier until one is set.
  - Three `require = 0` hooks into Apotheosis (`finalizeMobSpawns`, `delayedEliteMobs`, `SurfaceType#skyOrSameVerticalSlice`). An Apotheosis update that moves them makes the anchor stop carrying a tier rather than break the game.
  - **Tells you when that happens**: on server start the mod checks that every hook target still exists and logs a warning naming what moved, and every operator gets that warning once in chat on join. A healthy build logs one info line instead. Silent failure was the worst part of `require = 0`.
  - Dev check: `build.bat runAnchorTierCheck` (game dir `run-anchor-tier/`, needs Apotheosis + ApothicAttributes + Placebo in `mods/`). `-PtierOff` switches the path off to prove the assertions can fail; `-PfakeBreak` makes the startup probe report a broken hook to prove the warning renders.

## 1.35.0

- Added **In-World FPS Hotspot Highlighter** (Observable-style for client render time):
  - Automatically starts/stops alongside `/sparkc profiler` (toggle: `fpsHighlighterSparkHook`, default on) or run standalone via `/foxstweaks fps [seconds|stop|clear]`.
  - Times `BlockEntityRenderDispatcher#render` and `EntityRenderDispatcher#render` with zero overhead when inactive.
  - Highlights the heaviest BlockEntities and Entities in-world with through-wall heat-colored boxes (green → yellow → orange → red), floating `µs/frame` labels, a compact HUD badge, and a ranked Top-8 chat summary with coordinates.

## 1.34.5

- Added `disableFloraLuckParticles` (default on, server-side): stops Kaleidoscope Flora (`PlayerEvents#coronationMotes`) from orbiting yellow dust particles around any player or entity with the vanilla Luck effect.

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
