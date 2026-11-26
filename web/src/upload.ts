// Uploads a file to the world's asset library: through the game (the page never talks to the server), in chunks
// the game acknowledges one at a time. Video goes to the server's ffmpeg when it has one; otherwise this page
// decodes it with WebCodecs and sends a ready-made frame bundle, which the server checks like any other upload.

import type { AssetCaps } from './protocol';
import { S, refreshAssets, sendAsset, setUpload, setUploadListener, toast } from './store';

type Mode = 'image' | 'video' | 'bundle';

/** Formats the server decodes itself; anything else that is an image is turned into a PNG here first. */
const SERVER_IMAGES = ['image/png', 'image/jpeg', 'image/gif'];
const VIDEO_EXT = /\.(mp4|m4v|webm|mov|mkv|avi|ogv)$/i;

/** How long one step (start, one chunk ack, the server's answer) may stay silent before we give up. */
const STEP_TIMEOUT_MS = 30_000;
/** Server-side work (ffmpeg, frame checks) reports progress, but a long video can sit in one stage for a while. */
const PROCESS_TIMEOUT_MS = 11 * 60_000;
/** The server takes one upload per player per second; the next one waits out the rest of that second. */
const UPLOAD_GAP_MS = 1100;
let lastSubmit = 0;

export function isVideo(file: File): boolean {
  return file.type.startsWith('video/') || VIDEO_EXT.test(file.name);
}

export function uploadBusy(): boolean {
  return S.upload !== null;
}

/** Uploads {@code file}; resolves with the asset's hash, or null after showing why it failed. */
export async function uploadFile(file: File): Promise<string | null> {
  const caps = S.assetCaps;
  if (!caps || !caps.available) {
    toast('This server has no asset library (Fox’s Tweaks is missing there, or too old).', 'error');
    return null;
  }
  if (!S.library.canUpload) {
    toast('Only operators may upload on this server (server config assetUploadEveryone).', 'error');
    return null;
  }
  if (uploadBusy()) {
    toast('Another upload is still running.', 'error');
    return null;
  }

  setUpload({ name: file.name, percent: 0, stage: 'reading' });
  try {
    const { bytes, mode } = await prepare(file, caps);
    const limit = mode === 'image' ? Math.min(caps.maxFileBytes, caps.maxUploadBytes) : caps.maxUploadBytes;
    if (bytes.size > limit)
      throw new Error(`${file.name} is ${mib(bytes.size)}; the limit is ${mib(limit)}.`);
    const hash = await send(file.name, bytes, mode, caps);
    toast(`Uploaded ${file.name}.`);
    refreshAssets();
    return hash;
  } catch (e) {
    toast(`Upload failed: ${e instanceof Error ? e.message : String(e)}`, 'error');
    return null;
  } finally {
    setUploadListener(null);
    setUpload(null);
  }
}

function mib(n: number): string {
  return `${(n / (1024 * 1024)).toFixed(1)} MiB`;
}

function stage(stage: string, percent: number): void {
  setUpload({ name: S.upload?.name ?? '', percent: Math.max(0, Math.min(100, Math.round(percent))), stage });
}

// ---------------------------------------------------------------- what to send

async function prepare(file: File, caps: AssetCaps): Promise<{ bytes: Blob; mode: Mode }> {
  if (isVideo(file)) {
    if (S.library.serverVideo) return { bytes: file, mode: 'video' };
    return { bytes: await videoToBundle(file, caps), mode: 'bundle' };
  }
  if (SERVER_IMAGES.includes(file.type)) return { bytes: file, mode: 'image' };
  if (file.type.startsWith('image/')) return { bytes: await toPng(file), mode: 'image' };
  throw new Error(`${file.name} is not an image or a video.`);
}

/** WebP, AVIF, BMP...: the server only reads PNG, JPEG and GIF, so the browser re-encodes the first frame. */
async function toPng(file: File): Promise<Blob> {
  stage('converting to PNG', 0);
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    throw new Error(`this browser cannot read ${file.name}.`);
  }
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  canvas.getContext('2d')!.drawImage(bitmap, 0, 0);
  bitmap.close();
  return canvas.convertToBlob({ type: 'image/png' });
}

// ---------------------------------------------------------------- transport

/** One message from the game about this upload, or a timeout. */
function next(timeoutMs: number): Promise<any> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      setUploadListener(null);
      reject(new Error('the game stopped answering.'));
    }, timeoutMs);
    setUploadListener((msg) => {
      clearTimeout(timer);
      setUploadListener(null);
      resolve(msg);
    });
  });
}

function failure(msg: any): Error {
  return new Error(String(msg.message ?? 'the game refused it.'));
}

async function send(name: string, bytes: Blob, mode: Mode, caps: AssetCaps): Promise<string> {
  stage('starting', 0);
  const wait = lastSubmit + UPLOAD_GAP_MS - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  const started = next(STEP_TIMEOUT_MS);
  if (!sendAsset({ type: 'upload', name, size: bytes.size, mode })) throw new Error('not connected to the game.');
  let msg = await started;
  if (msg.type !== 'uploadStarted') throw failure(msg);
  const upload = Number(msg.upload);
  const chunk = Math.max(1024, Number(msg.chunkBytes ?? caps.chunkBytes));

  let index = 0;
  for (let at = 0; at < bytes.size; at += chunk, index++) {
    const data = await base64(bytes.slice(at, at + chunk));
    // Progress messages arrive between acks; skip them until this chunk is acknowledged or the upload ends.
    const ack = waitFor(upload, (m) => m.type === 'uploadAck' && m.index === index, STEP_TIMEOUT_MS);
    sendAsset({ type: 'uploadChunk', upload, index, data });
    msg = await ack;
    if (msg.type !== 'uploadAck') throw failure(msg);
    stage('sending to the game', ((at + chunk) / bytes.size) * 100);
  }

  stage('sending to the server', 0);
  lastSubmit = Date.now();
  msg = await waitFor(upload, (m) => m.type === 'uploadDone', PROCESS_TIMEOUT_MS);
  if (msg.type !== 'uploadDone' || !msg.ok || typeof msg.hash !== 'string') throw failure(msg);
  return msg.hash;
}

/**
 * Waits for a message that {@code wanted} accepts, showing progress meanwhile. A finished upload (uploadDone) or
 * an error ends the wait too, whatever was wanted, so the caller sees the failure.
 */
async function waitFor(upload: number, wanted: (m: any) => boolean, timeoutMs: number): Promise<any> {
  for (;;) {
    const msg = await next(timeoutMs);
    if (msg.type === 'error') return msg;
    if (msg.upload !== upload) continue;
    if (wanted(msg) || msg.type === 'uploadDone') return msg;
    if (msg.type === 'uploadProgress' && msg.server) stage(String(msg.stage ?? 'processing'), Number(msg.percent ?? 0));
  }
}

function base64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => {
      const url = String(r.result);
      resolve(url.slice(url.indexOf(',') + 1));
    };
    r.onerror = () => reject(new Error('could not read the file.'));
    r.readAsDataURL(blob);
  });
}

// ---------------------------------------------------------------- video in the browser (WebCodecs)

/** FTAS v1 (AssetFrames.java): magic, frame count, then per frame its delay in ms, its length and its bytes. */
const FTAS_MAGIC = 0x46544131;
const MAX_FRAMES = 900;

export function webCodecsAvailable(): boolean {
  return typeof VideoFrame !== 'undefined' && typeof OffscreenCanvas !== 'undefined';
}

/**
 * Decodes {@code file} frame by frame and packs JPEG frames into an FTAS bundle that already fits the server's
 * limits (size, frame rate, length), so the server's own checks have nothing to cut.
 */
async function videoToBundle(file: File, caps: AssetCaps): Promise<Blob> {
  if (!webCodecsAvailable())
    throw new Error(
      'the server has no ffmpeg and this browser has no WebCodecs, so nothing can convert the video. ' +
        'Open the editor in a current Chrome, Edge or Firefox, or upload a GIF instead.',
    );

  stage('opening video', 0);
  const url = URL.createObjectURL(file);
  const video = document.createElement('video');
  video.muted = true;
  video.preload = 'auto';
  video.playsInline = true;
  try {
    await new Promise<void>((resolve, reject) => {
      video.onloadeddata = () => resolve();
      video.onerror = () => reject(new Error(`this browser cannot decode ${file.name}.`));
      video.src = url;
    });
    if (!video.videoWidth || !video.videoHeight) throw new Error(`${file.name} has no picture.`);

    const length = Math.min(Number.isFinite(video.duration) ? video.duration : caps.maxSeconds, caps.maxSeconds);
    const fps = Math.max(1, Math.min(caps.maxFps, Math.floor(MAX_FRAMES / Math.max(length, 1e-3))));
    const count = Math.max(1, Math.min(MAX_FRAMES, Math.floor(length * fps)));
    const scale = Math.min(1, caps.maxDimension / Math.max(video.videoWidth, video.videoHeight));
    const width = Math.max(1, Math.round(video.videoWidth * scale));
    const height = Math.max(1, Math.round(video.videoHeight * scale));

    const canvas = new OffscreenCanvas(width, height);
    const ctx = canvas.getContext('2d')!;
    // Each frame gets an equal share of the upload limit; a frame over its share is re-encoded at lower quality.
    const share = (caps.maxUploadBytes - 8 - count * 8) / count;
    const frames: Uint8Array[] = [];
    for (let i = 0; i < count; i++) {
      await seek(video, i / fps);
      const frame = new VideoFrame(video, { timestamp: Math.round((i / fps) * 1e6) });
      try {
        ctx.drawImage(frame, 0, 0, width, height);
      } finally {
        frame.close();
      }
      frames.push(await jpeg(canvas, share));
      stage(`converting video (${i + 1}/${count} frames)`, ((i + 1) / count) * 100);
    }

    const delay = Math.max(1, Math.min(30000, Math.round(1000 / fps)));
    const total = 8 + frames.reduce((n, f) => n + 8 + f.length, 0);
    const out = new Uint8Array(total);
    const view = new DataView(out.buffer);
    view.setInt32(0, FTAS_MAGIC);
    view.setInt32(4, frames.length);
    let at = 8;
    for (const f of frames) {
      view.setInt32(at, delay);
      view.setInt32(at + 4, f.length);
      out.set(f, at + 8);
      at += 8 + f.length;
    }
    return new Blob([out]);
  } finally {
    video.removeAttribute('src');
    video.load();
    URL.revokeObjectURL(url);
  }
}

function seek(video: HTMLVideoElement, time: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('the video stopped decoding.')), 10_000);
    video.onseeked = () => {
      clearTimeout(timer);
      resolve();
    };
    video.currentTime = Math.min(time, Math.max(0, video.duration - 1e-3));
  });
}

async function jpeg(canvas: OffscreenCanvas, share: number): Promise<Uint8Array> {
  let blob: Blob | null = null;
  for (const quality of [0.82, 0.65, 0.5, 0.35]) {
    blob = await canvas.convertToBlob({ type: 'image/jpeg', quality });
    if (blob.size <= share) break;
  }
  if (!blob || blob.size > share) throw new Error('the video is too big even at low quality; trim it or make it smaller.');
  return new Uint8Array(await blob.arrayBuffer());
}
