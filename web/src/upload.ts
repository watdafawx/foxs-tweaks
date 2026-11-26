// Uploads a file to the world's asset library: through the game (the page never talks to the server), in chunks
// the game acknowledges one at a time. Any file is accepted: fit.ts scales pictures down and resamples animations
// and videos in this page until they fit the server's limits, and a video or animation longer than the server
// allows asks which part to keep. Only a video this browser cannot decode goes to the server's ffmpeg as it is.

import type { AssetCaps } from './protocol';
import { S, refreshAssets, sendAsset, setUpload, setUploadListener, toast } from './store';
import {
  fitStill,
  fitTimed,
  imageDecoderAvailable,
  openAnimation,
  openVideo,
  stillFitsAsIs,
  type Range,
  type TimedSource,
} from './fit';

type Mode = 'image' | 'video' | 'bundle' | 'model';

const VIDEO_EXT = /\.(mp4|m4v|webm|mov|mkv|avi|ogv)$/i;

/** How long one step (start, one chunk ack, the server's answer) may stay silent before we give up. */
const STEP_TIMEOUT_MS = 30_000;
/** Server-side work (ffmpeg, frame checks) reports progress, but a long video can sit in one stage for a while. */
const PROCESS_TIMEOUT_MS = 11 * 60_000;
/** The server takes one upload per player per second; the next one waits out the rest of that second. */
const UPLOAD_GAP_MS = 1100;
/** The server re-encodes frames itself; when its result still lands over the limit, aim this much lower. */
const RETRY_SCALES = [0.7, 0.45];
let lastSubmit = 0;

/** What the page asks when a video or animation is longer than the server keeps. */
export interface TrimRequest {
  file: File;
  kind: 'video' | 'animation';
  /** Seconds. */
  duration: number;
  /** Longest part the server keeps, in seconds. */
  max: number;
}

/** Answers a {@link TrimRequest} with the part to keep, or null to cancel the upload. */
export type TrimChooser = (r: TrimRequest) => Promise<Range | null>;

export function isVideo(file: File): boolean {
  return file.type.startsWith('video/') || VIDEO_EXT.test(file.name);
}

export function uploadBusy(): boolean {
  return S.upload !== null;
}

/**
 * Uploads {@code file}; resolves with the asset's hash, or null after showing why it failed (or when the person
 * cancelled the part picker).
 */
export async function uploadFile(file: File, choose?: TrimChooser): Promise<string | null> {
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
  let job: Prepared | null = null;
  try {
    job = await prepare(file, caps, choose);
    if (!job) return null;
    let bytes = job.bytes;
    for (let attempt = 0; ; attempt++) {
      try {
        const hash = await send(file.name, bytes, job.mode, caps);
        toast(job.note ? `Uploaded ${file.name} (${job.note}).` : `Uploaded ${file.name}.`);
        refreshAssets();
        return hash;
      } catch (e) {
        // The server re-encodes every frame, and its encoder can come out larger than the browser's.
        const tooBig = e instanceof Error && /byte limit|budget exceeded/i.test(e.message);
        if (!tooBig || !job.refit || attempt >= RETRY_SCALES.length) throw e;
        bytes = await job.refit(RETRY_SCALES[attempt]);
      }
    }
  } catch (e) {
    toast(`Upload failed: ${e instanceof Error ? e.message : String(e)}`, 'error');
    return null;
  } finally {
    job?.close?.();
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

interface Prepared {
  bytes: Blob;
  mode: Mode;
  /** What the page changed, for the toast ("256×144, 150 frames at up to 15 fps"). */
  note?: string;
  /** Builds the same upload again aiming at {@code scale} of the byte limit. */
  refit?: (scale: number) => Promise<Blob>;
  close?: () => void;
}

/** A glTF binary or a Blockbench project: sent as it is, the game reads and checks it. */
export function isModelFile(file: File): boolean {
  return /\.(glb|gltf|bbmodel)$/i.test(file.name);
}

async function prepare(file: File, caps: AssetCaps, choose?: TrimChooser): Promise<Prepared | null> {
  if (isModelFile(file)) {
    if (file.size > caps.maxUploadBytes)
      throw new Error(`${file.name} is ${mib(file.size)}; the server takes models up to ${mib(caps.maxUploadBytes)}.`);
    return { bytes: file, mode: 'model' };
  }
  if (isVideo(file)) {
    stage('opening video', 0);
    const src = await openVideo(file);
    if (src) return timed(file, src, caps, choose);
    if (!S.library.serverVideo)
      throw new Error(`this browser cannot decode ${file.name}, and the server has no ffmpeg to convert it.`);
    if (file.size > caps.maxUploadBytes)
      throw new Error(
        `this browser cannot decode ${file.name}, and at ${mib(file.size)} it is too big to send to the ` +
          `server’s converter (${mib(caps.maxUploadBytes)}). Convert it to MP4 or WebM first.`,
      );
    return { bytes: file, mode: 'video' };
  }
  if (!file.type.startsWith('image/')) throw new Error(`${file.name} is not an image or a video.`);

  stage('reading picture', 0);
  const anim = await openAnimation(file, stage);
  if (anim) return timed(file, anim, caps, choose);
  // Without ImageDecoder the page cannot take a GIF apart: the server reads it as long as it is small enough.
  if (file.type === 'image/gif' && !imageDecoderAvailable() && file.size <= caps.maxFileBytes)
    return { bytes: file, mode: 'image' };

  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    throw new Error(`this browser cannot read ${file.name}.`);
  }
  if (stillFitsAsIs(file, bitmap, caps)) {
    bitmap.close();
    return { bytes: file, mode: 'image' };
  }
  stage('scaling down', 0);
  const fitted = await fitStill(bitmap, caps);
  const lostAnimation = file.type === 'image/gif' && !imageDecoderAvailable();
  return {
    bytes: fitted.bytes,
    mode: 'image',
    note:
      `scaled to ${fitted.width}×${fitted.height}` +
      (lostAnimation ? ', first frame only: this browser cannot resize animations' : ''),
    refit: async (scale) => (await fitStill(bitmap, caps, scale)).bytes,
    close: () => bitmap.close(),
  };
}

async function timed(file: File, src: TimedSource, caps: AssetCaps, choose?: TrimChooser): Promise<Prepared | null> {
  let range: Range = { start: 0, length: Math.min(src.duration, caps.maxSeconds) };
  if (src.duration > caps.maxSeconds + 0.05 && choose) {
    stage('choose the part to keep', 0);
    const picked = await choose({ file, kind: src.kind, duration: src.duration, max: caps.maxSeconds });
    if (!picked) {
      src.close();
      return null;
    }
    range = picked;
  }
  let fitted;
  try {
    fitted = await fitTimed(src, range, caps, stage);
  } catch (e) {
    src.close();
    throw e;
  }
  const cut = range.start > 0.01 || range.length < src.duration - 0.05;
  const parts = [`${fitted.width}×${fitted.height}`, `${fitted.frames} frames at up to ${fitted.fps} fps`];
  if (cut)
    parts.push(`${range.start.toFixed(1)}–${(range.start + range.length).toFixed(1)} s of ${src.duration.toFixed(1)} s`);
  return {
    bytes: fitted.bytes,
    mode: 'bundle',
    note: parts.join(', '),
    refit: async (scale) => (await fitTimed(src, range, caps, stage, scale)).bytes,
    close: () => src.close(),
  };
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
  // A refused start still used up this second of the server's rate limit.
  lastSubmit = Date.now();
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

// ---------------------------------------------------------------- capabilities

/** True when this page can convert video itself (decode through a video element, draw into an OffscreenCanvas). */
export function webCodecsAvailable(): boolean {
  return typeof OffscreenCanvas !== 'undefined';
}
