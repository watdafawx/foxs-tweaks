# Hologram asset foundation

Storage: `<world>/foxstweaks/assets/index.json` and `blobs/<sha256>`.
SHA-256 covers canonical FTAS bytes, not the original upload. Index changes use
atomic replacement. First uploader owns deduplicated content. `retain/release`
persist reference counts; release does not garbage collect. Image layers retain
their asset when a hologram is saved and release it when the layer or hologram
goes (`Holograms`). Operator delete refuses an asset that is still used unless
`force` is added.

FTAS v1 (big endian): int magic `0x46544131`, int frame count, then for each
frame: int delay in milliseconds, int byte length, image bytes. Upload bundles
may contain PNG/JPEG frames with identical dimensions. The server always decodes,
rescales and re-encodes frames: JPEG frames (video, browser bundles) stay JPEG at
quality 0.85, everything else becomes PNG. GIF disposal and logical canvas dimensions
are respected. Too many frames/duration are rejected; delays are raised to the
configured minimum. Static images use a one-frame FTAS bundle too.

Payload `foxstweaks:asset`, optional registrar 2, bidirectional:
kind/number/total are VarInts, text is UTF-8 (256 chars), bytes are length-prefixed
(max 30000). BEGIN(0): number=mode (0 image, 1 video for the server's ffmpeg,
2 FTAS bundle built by the web editor), total=file length, text=name;
CHUNK(1): number=sequence; ABORT(2): cancel player transfers; GET(3): number=byte
offset, text=hash; DATA(4): number=offset, total=blob length, text=hash;
RESULT(5): number=success, total=request kind, text=canonical hash or refusal;
PROGRESS(6): number=percent, text=stage, while the server works on an upload;
LIST(7)/LISTING(8): one size-limited page of the library as JSON (see
`docs/hologram-web-protocol.md`, Assets). Sanitising runs on a worker thread,
not the server thread. Downloads are pull-based:
one response per request, one blob retained per player, 30s timeout. Upload sends
one chunk per client tick. Server caps all requests at 40 packets/second and new
uploads at one/second, checks permission on every chunk, and drops on logout.

API: `HologramAssets.texture(hash, timeSeconds)` on render thread; null until
available. Time loops over frame delays. Frames stay compressed; a single worker
decodes the requested frame, and client main thread copies it into the asset's
one reused DynamicTexture. Intermediate frames can be skipped. Native images are
closed; textures evicted after 30s without drawing, on compressed RAM pressure
(32 MiB), and on disconnect. Maximum 64 resident/pending entries and 64 queued
worker tasks. Disk cache is shared by servers safely through hashes, verifies
bytes before use, and evicts oldest access time above 200 MiB after writes.

10 seconds at 256x256, 15 FPS = 150 frames. All decoded frames would need
37.5 MiB RGBA. Streaming needs compressed PNG total (<=2 MiB by default per
asset), one 256 KiB native image, one transient 256 KiB decode and ~256 KiB VRAM.
Transfer/reassembly and bundle parsing temporarily duplicate compressed bytes.
Many incompressible frames exceed default 2 MiB and are refused.

Hard safety ceilings: 16 MiB input/canonical bundle, 900 frames, dimensions <=4096
and <=4M pixels per input frame, <=32M pixels across decoded frames. SERVER config
can lower these, but cannot exceed them. PNG/GIF/JPEG signatures checked before
reader selection; dimensions checked before image allocation. Only hash-shaped
paths touch blob/cache storage. Client upload resolves real paths inside
`<gamedir>/foxstweaks/upload`, including symlink/junction containment checks.

Commands: `/foxstweaks asset list`, `info <hash>`, `delete <hash>` (operator);
`/foxstweaksc asset upload <relative path>` (client). List shows world byte and
player asset quotas. Image layers (`HologramLayer.Kind.IMAGE`, settings in
`hologram/HologramImage`) draw assets; the web editor uploads through the game
(`client/web/HologramWebAssets`).
