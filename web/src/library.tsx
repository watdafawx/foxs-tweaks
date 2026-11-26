// The Images tab of the middle panel: the world's asset library. Upload (button or drop), then click a picture to
// put it on the selected image layer or + to add it as a new layer.

import { useEffect, useRef, useState } from 'preact/hooks';
import type { AssetEntry, Layer } from './protocol';
import { ASSETS, S, deleteAsset, edit, refreshAssets, requestThumb } from './store';
import { addLayer } from './browser';
import { uploadFile, webCodecsAvailable, type TrimRequest } from './upload';
import type { Range } from './fit';

const ACCEPT = 'image/png,image/jpeg,image/gif,image/webp,image/avif,image/bmp,video/*';

/** Image layers already in the hologram, against the server's per-hologram cap. */
export function imageLayerCount(): number {
  return S.spec?.layers.filter((l) => l.kind === 'IMAGE').length ?? 0;
}

export function imageCapReached(): boolean {
  const cap = S.assetCaps?.maxImageLayers ?? 0;
  return imageLayerCount() >= cap;
}

/** Height / width of the picture, or 1 while the library has not said. */
export function aspectOf(id: string): number {
  const a = ASSETS.get(id);
  return a && a.width > 0 && a.height > 0 ? a.height / a.width : 1;
}

/** The drawn height of an image layer in blocks, before the layer's scale. */
export function imageHeight(l: Layer): number {
  const w = Number(l.props.w ?? 1) || 1;
  const h = Number(l.props.h ?? 0) || 0;
  return h > 0 ? h : w * aspectOf(l.id);
}

function size(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function describe(a: AssetEntry): string {
  const dims = `${a.width}×${a.height}`;
  if (a.frames <= 1) return `${dims} · still`;
  return `${dims} · ${a.frames} frames · ${(a.durationMs / 1000).toFixed(1)} s`;
}

export function AssetLibrary() {
  const caps = S.assetCaps;
  const lib = S.library;
  const up = S.upload;
  const spec = S.spec!;
  const sel = S.selection;
  const layer = sel.kind === 'layer' ? spec.layers[sel.layer] : undefined;
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const [trim, setTrim] = useState<{ req: TrimRequest; done: (r: Range | null) => void } | null>(null);
  const choose = (req: TrimRequest) =>
    new Promise<Range | null>((resolve) =>
      setTrim({
        req,
        done: (r) => {
          setTrim(null);
          resolve(r);
        },
      }),
    );

  useEffect(() => {
    if (caps?.available && !lib.loaded && !lib.loading) refreshAssets();
  }, [caps]);

  if (!caps || !caps.available)
    return (
      <div class="library">
        <div class="empty">
          This server has no asset library: Fox’s Tweaks is missing there, or older than 1.44.0. Images need the mod
          on the server.
        </div>
      </div>
    );

  const full = spec.layers.length >= S.registries!.limits.maxLayers;
  const capped = imageCapReached();
  const addTitle = full
    ? 'Layer limit reached'
    : capped
      ? `This server allows ${caps.maxImageLayers} image layers per hologram`
      : 'Add as a new image layer';

  const start = async (files: FileList | null) => {
    const file = files?.[0];
    if (!file) return;
    const hash = await uploadFile(file, choose);
    // A fresh upload goes straight into the hologram, which is what someone dropping a file nearly always wants.
    if (hash && !full && !imageCapReached()) addImage(hash);
  };

  const replace = (a: AssetEntry) => {
    if (!layer || layer.kind !== 'IMAGE' || layer.id === a.id) return;
    const index = sel.layer;
    edit((s) => (s.layers[index].id = a.id));
  };

  const videoNote = lib.serverVideo
    ? 'Video is converted by the server.'
    : webCodecsAvailable()
      ? 'Video is converted in this browser (the server has no ffmpeg).'
      : 'Video needs ffmpeg on the server or WebCodecs in this browser; neither is there.';

  return (
    <div
      class={`library ${over ? 'drop' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        if (lib.canUpload && !up) setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        if (lib.canUpload && !up) start(e.dataTransfer?.files ?? null);
      }}
    >
      <div class="library-bar">
        <button
          class="btn primary"
          disabled={!lib.canUpload || !!up}
          title={lib.canUpload ? 'Any picture, GIF or video; it is scaled to fit here' : 'Only operators may upload on this server'}
          onClick={() => input.current?.click()}
        >
          Upload…
        </button>
        <input
          ref={input}
          type="file"
          accept={ACCEPT}
          hidden
          onChange={(e) => {
            const el = e.currentTarget as HTMLInputElement;
            start(el.files);
            el.value = '';
          }}
        />
        <button class="btn" disabled={lib.loading} onClick={() => refreshAssets()} title="Reload the library">
          ↻
        </button>
        <span class="muted small">
          {lib.count} in this world · {imageLayerCount()}/{caps.maxImageLayers} image layers here
        </span>
      </div>
      {up ? (
        <div class="upload-progress" title={up.name}>
          <div class="upload-text small">
            <span class="mono">{up.name}</span> · {up.stage}
          </div>
          <div class="bar">
            <div class="bar-fill" style={{ width: `${up.percent}%` }} />
          </div>
        </div>
      ) : (
        <div class="browser-hint muted small">
          {lib.canUpload
            ? `Drop any picture, GIF or video here, or use Upload. Big files are scaled down in this page to fit ${caps.maxDimension} px and ${(caps.maxFileBytes / (1024 * 1024)).toFixed(0)} MB; for anything longer than ${caps.maxSeconds} s you pick the part to keep. ${videoNote}`
            : 'Only operators may upload on this server; anyone editing can place what is already here.'}
          {layer?.kind === 'IMAGE' ? ' Click a picture to swap it into the selected layer.' : ''}
        </div>
      )}
      {lib.error && <div class="empty error">{lib.error}</div>}
      {trim && <TrimDialog req={trim.req} onDone={trim.done} />}
      <div class="library-grid">
        {lib.entries.map((a) => (
          <AssetCard
            key={a.id}
            a={a}
            current={layer?.kind === 'IMAGE' && layer.id === a.id}
            onPick={() => replace(a)}
            onAdd={() => addImage(a.id)}
            addDisabled={full || capped}
            addTitle={addTitle}
          />
        ))}
        {lib.loaded && lib.entries.length === 0 && (
          <div class="empty">No pictures in this world yet.{lib.canUpload ? ' Upload one to start.' : ''}</div>
        )}
        {!lib.loaded && lib.entries.length === 0 && !lib.error && <div class="empty">Loading the library…</div>}
      </div>
    </div>
  );
}

function AssetCard(p: {
  a: AssetEntry;
  current: boolean;
  onPick: () => void;
  onAdd: () => void;
  addDisabled: boolean;
  addTitle: string;
}) {
  const a = p.a;
  const thumb = S.thumbs[a.id];
  useEffect(() => requestThumb(a.id), [a.id]);
  const owner = a.mine ? 'you' : a.owner || 'unknown';
  const [confirm, setConfirm] = useState(false);
  // a.used counts saved holograms, the open one included once saved; it may also use the picture unsaved. The page
  // cannot tell which, so take the larger count rather than adding the two.
  const here = S.spec?.layers.filter((l) => l.kind === 'IMAGE' && l.id === a.id).length ?? 0;
  const inUse = Math.max(a.used, here);
  return (
    <div
      class={`asset ${p.current ? 'current' : ''}`}
      onClick={p.onPick}
      onDblClick={() => !p.addDisabled && p.onAdd()}
      title={`${a.name}\n${a.id}`}
    >
      <div class="asset-thumb">
        {thumb ? <img src={thumb} alt="" draggable={false} /> : <span class="muted small">…</span>}
        {a.frames > 1 && <span class="chip asset-anim">anim</span>}
      </div>
      <div class="asset-name">{a.name}</div>
      <div class="muted small">{describe(a)}</div>
      <div class="muted small">
        {size(a.bytes)} · by {owner}
      </div>
      {confirm && (
        <div class="asset-confirm" onClick={(e) => e.stopPropagation()} onDblClick={(e) => e.stopPropagation()}>
          <div class="small">
            {inUse > 0
              ? `Used by ${inUse} layer${inUse === 1 ? '' : 's'}. Deleting leaves ${inUse === 1 ? 'it' : 'them'} empty, for everyone.`
              : 'Delete from this world? This cannot be undone.'}
          </div>
          <div class="asset-confirm-buttons">
            <button class="btn" onClick={() => setConfirm(false)}>
              Keep
            </button>
            <button
              class="btn danger"
              onClick={() => {
                setConfirm(false);
                deleteAsset(a.id, inUse > 0);
              }}
            >
              Delete
            </button>
          </div>
        </div>
      )}
      <div class="asset-foot">
        <span class={`small ${a.used > 0 ? '' : 'muted'}`}>used by {a.used}</span>
        {a.canDelete && (
          <button
            class="icon-btn asset-delete"
            title="Delete from this world"
            onClick={(e) => {
              e.stopPropagation();
              setConfirm(true);
            }}
          >
            ✕
          </button>
        )}
        <button
          class="icon-btn add"
          title={p.addTitle}
          disabled={p.addDisabled}
          onClick={(e) => {
            e.stopPropagation();
            p.onAdd();
          }}
        >
          +
        </button>
      </div>
    </div>
  );
}

/**
 * Picks the part of a too-long video or animation to keep: a start and a length (at most the server's maximum),
 * with the preview looping that part. Starts on the first {@code max} seconds, so Enter alone keeps the beginning.
 */
function TrimDialog(p: { req: TrimRequest; onDone: (r: Range | null) => void }) {
  const { req } = p;
  const max = Math.min(req.max, req.duration);
  const [start, setStart] = useState(0);
  const [length, setLength] = useState(max);
  const [url] = useState(() => URL.createObjectURL(req.file));
  const video = useRef<HTMLVideoElement>(null);
  const end = Math.min(req.duration, start + length);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);

  // Loop the chosen part in the preview.
  useEffect(() => {
    const v = video.current;
    if (!v) return;
    if (v.currentTime < start || v.currentTime > end) v.currentTime = start;
    const tick = () => {
      if (v.currentTime >= end - 0.03 || v.currentTime < start - 0.25) v.currentTime = start;
    };
    v.addEventListener('timeupdate', tick);
    return () => v.removeEventListener('timeupdate', tick);
  }, [start, end]);

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') p.onDone(null);
      else if (e.key === 'Enter') p.onDone({ start, length: end - start });
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [start, end]);

  const fmt = (t: number) => `${Math.floor(t / 60)}:${(t % 60).toFixed(1).padStart(4, '0')}`;
  const left = (start / req.duration) * 100;
  const width = ((end - start) / req.duration) * 100;
  const what = req.kind === 'video' ? 'a video' : 'an animation';

  return (
    <div class="overlay trim-overlay" onClick={(e) => e.target === e.currentTarget && p.onDone(null)}>
      <div class="overlay-card trim-card">
        <div class="overlay-title">Choose the part to keep</div>
        <div class="muted small">
          {`${req.file.name} is ${fmt(req.duration)} long; this server keeps up to ${req.max} s of ${what}.`}
        </div>
        <div class="trim-preview">
          {req.kind === 'video' ? <video ref={video} src={url} muted autoPlay playsInline /> : <img src={url} alt="" />}
        </div>
        <div class="trim-track" title="The kept part">
          <div class="trim-keep" style={{ left: `${left}%`, width: `${width}%` }} />
        </div>
        <label class="trim-row">
          <span>Start</span>
          <input
            type="range"
            min={0}
            max={Math.max(0, req.duration - 0.1)}
            step={0.1}
            value={start}
            onInput={(e) => setStart(Number((e.currentTarget as HTMLInputElement).value))}
          />
          <span class="mono">{fmt(start)}</span>
        </label>
        <label class="trim-row">
          <span>Length</span>
          <input
            type="range"
            min={Math.min(0.5, max)}
            max={max}
            step={0.1}
            value={length}
            onInput={(e) => setLength(Number((e.currentTarget as HTMLInputElement).value))}
          />
          <span class="mono">{`${(end - start).toFixed(1)} s`}</span>
        </label>
        <div class="muted small">
          {`Keeps ${fmt(start)} – ${fmt(end)}. Size and frame rate are lowered to fit the server if needed.`}
        </div>
        <div class="trim-buttons">
          <button class="btn" onClick={() => p.onDone(null)}>
            Cancel
          </button>
          <button class="btn primary" onClick={() => p.onDone({ start, length: end - start })}>
            Upload this part
          </button>
        </div>
      </div>
    </div>
  );
}

function addImage(id: string): void {
  addLayer('IMAGE', id);
}

