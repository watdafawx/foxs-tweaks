// The Images tab of the middle panel: the world's asset library. Upload (button or drop), then click a picture to
// put it on the selected image layer or + to add it as a new layer.

import { useEffect, useRef, useState } from 'preact/hooks';
import type { AssetEntry, Layer } from './protocol';
import { ASSETS, S, edit, refreshAssets, requestThumb } from './store';
import { addLayer } from './browser';
import { uploadFile, webCodecsAvailable } from './upload';

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
    const hash = await uploadFile(file);
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
          title={lib.canUpload ? 'PNG, JPEG, GIF, WebP or a short video' : 'Only operators may upload on this server'}
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
            ? `Drop a file here or use Upload. Up to ${(caps.maxFileBytes / (1024 * 1024)).toFixed(0)} MB, ${caps.maxDimension} px. ${videoNote}`
            : 'Only operators may upload on this server; anyone editing can place what is already here.'}
          {layer?.kind === 'IMAGE' ? ' Click a picture to swap it into the selected layer.' : ''}
        </div>
      )}
      {lib.error && <div class="empty error">{lib.error}</div>}
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
      <div class="asset-foot">
        <span class={`small ${a.used > 0 ? '' : 'muted'}`}>used by {a.used}</span>
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

function addImage(id: string): void {
  addLayer('IMAGE', id);
}

