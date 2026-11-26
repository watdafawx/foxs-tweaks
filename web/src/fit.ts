// Makes any picture, animation or video fit the server's asset limits in the browser, so the page never has to
// refuse a file for being too big, too large or too long. Still images are scaled down and re-encoded; animations
// (GIF, animated WebP/PNG/AVIF) and videos are resampled into an FTAS frame bundle, lowering the frame rate first
// and the size second until the bundle fits. Nothing here talks to the game: upload.ts sends what this returns.

import type { AssetCaps } from './protocol';
import { parseAnimatedWebp } from './webp';

/** FTAS v1 (AssetFrames.java): magic, frame count, then per frame its delay in ms, its length and its bytes. */
const FTAS_MAGIC = 0x46544131;
/** AssetLimits.MAX_FRAMES and PIXEL_BUDGET: hard ceilings the server applies whatever its config says. */
const MAX_FRAMES = 900;
const PIXEL_BUDGET = 32 * 1024 * 1024;
/** AssetFrames' decode guard for a still image the server reads itself. */
const SERVER_MAX_SIDE = 4096;
const SERVER_MAX_PIXELS = 4_194_304;
/** The server re-encodes every frame (JPEG at 0.85, PNG with Java's encoder), so the page aims below the limit. */
const JPEG_MARGIN = 0.85;
const PNG_MARGIN = 0.75;
/** Smallest output we accept before saying it cannot fit; below this a hologram picture is mush anyway. */
const MIN_SIDE = 24;
const MIN_FPS = 2;

export type Progress = (stage: string, percent: number) => void;

/** A part of a timed source to keep, in seconds. */
export interface Range {
  start: number;
  length: number;
}

/** Something with frames over time: a video or a decoded animation. */
export interface TimedSource {
  kind: 'video' | 'animation';
  width: number;
  height: number;
  /** Seconds; finite. */
  duration: number;
  /** Frame rate of the source, when known (an animation's average); a video reports 0. */
  nativeFps: number;
  /** Whether frames can be see-through, which forces PNG frames. */
  alpha: boolean;
  /** Output frames for {@code range} at most {@code fps}: what to draw ({@code at}) and how long it shows (ms). */
  plan(range: Range, fps: number): { at: number; delay: number }[];
  draw(ctx: OffscreenCanvasRenderingContext2D, at: number, w: number, h: number): Promise<void>;
  close(): void;
}

export interface Fitted {
  bytes: Blob;
  width: number;
  height: number;
  frames: number;
  fps: number;
}

// ---------------------------------------------------------------- still images

export interface StillInfo {
  width: number;
  height: number;
}

/**
 * Whether the server can take {@code file} as it is: a format it reads, under the byte limit and inside its decode
 * guard. Anything else is re-encoded by {@link fitStill}.
 */
export function stillFitsAsIs(file: Blob, info: StillInfo, caps: AssetCaps): boolean {
  return (
    (file.type === 'image/png' || file.type === 'image/jpeg') &&
    file.size <= caps.maxFileBytes &&
    info.width <= SERVER_MAX_SIDE &&
    info.height <= SERVER_MAX_SIDE &&
    info.width * info.height <= SERVER_MAX_PIXELS
  );
}

/** Scales {@code bitmap} to the server's size and encodes it under the byte limit (PNG, JPEG when PNG is too big). */
export async function fitStill(bitmap: ImageBitmap, caps: AssetCaps, budgetScale = 1): Promise<Fitted> {
  let side = Math.min(caps.maxDimension, Math.max(bitmap.width, bitmap.height));
  for (;;) {
    const { w, h } = sized(bitmap.width, bitmap.height, side);
    const canvas = new OffscreenCanvas(w, h);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(bitmap, 0, 0, w, h);
    const png = await canvas.convertToBlob({ type: 'image/png' });
    if (png.size <= caps.maxFileBytes * PNG_MARGIN * budgetScale) return { bytes: png, width: w, height: h, frames: 1, fps: 0 };
    if (!hasAlpha(ctx, w, h)) {
      const jpg = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
      if (jpg.size <= caps.maxFileBytes * JPEG_MARGIN * budgetScale) return { bytes: jpg, width: w, height: h, frames: 1, fps: 0 };
    }
    if (side <= MIN_SIDE) throw new Error('the picture does not fit the server’s size limit even at its smallest.');
    side = Math.max(MIN_SIDE, Math.floor(side * 0.8));
  }
}

// ---------------------------------------------------------------- timed sources

/** Opens a video the browser can decode; null when it cannot (the server's ffmpeg may still read it). */
export async function openVideo(file: Blob): Promise<TimedSource | null> {
  const url = URL.createObjectURL(file);
  const video = document.createElement('video');
  video.muted = true;
  video.preload = 'auto';
  video.playsInline = true;
  const close = () => {
    video.removeAttribute('src');
    video.load();
    URL.revokeObjectURL(url);
  };
  const ok = await new Promise<boolean>((resolve) => {
    video.onloadeddata = () => resolve(true);
    video.onerror = () => resolve(false);
    video.src = url;
  });
  if (!ok || !video.videoWidth || !video.videoHeight) {
    close();
    return null;
  }
  // Recorded WebM often has no duration in its header; seeking far past the end makes the browser work it out.
  if (!Number.isFinite(video.duration)) {
    await new Promise<void>((resolve) => {
      const done = () => resolve();
      video.ondurationchange = () => Number.isFinite(video.duration) && done();
      video.onseeked = done;
      setTimeout(done, 5000);
      video.currentTime = 1e9;
    });
    video.ondurationchange = null;
  }
  const duration = Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 0;
  if (duration <= 0) {
    close();
    return null;
  }

  return {
    kind: 'video',
    width: video.videoWidth,
    height: video.videoHeight,
    duration,
    nativeFps: 0,
    alpha: false,
    plan(range, fps) {
      // The server rounds every delay up to whole ms of at least 1000/fps and refuses anything over maxSeconds,
      // so count frames by that rounded delay: 150 frames of 67 ms is 10.05 s, which a 10 s limit refuses.
      const delay = Math.ceil(1000 / fps);
      const count = Math.max(1, Math.floor((range.length * 1000 + 1e-6) / delay));
      return Array.from({ length: count }, (_, i) => ({ at: range.start + (i * delay) / 1000, delay }));
    },
    async draw(ctx, at, w, h) {
      await seek(video, at);
      ctx.drawImage(video, 0, 0, w, h);
    },
    close,
  };
}

function seek(video: HTMLVideoElement, time: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const target = Math.max(0, Math.min(time, video.duration - 1e-3));
    if (Math.abs(video.currentTime - target) < 1e-4 && video.readyState >= 2) return resolve();
    const timer = setTimeout(() => reject(new Error('the video stopped decoding.')), 10_000);
    video.onseeked = () => {
      clearTimeout(timer);
      resolve();
    };
    video.currentTime = target;
  });
}

export function imageDecoderAvailable(): boolean {
  return typeof (globalThis as any).ImageDecoder !== 'undefined';
}

/**
 * Opens {@code file} as an animation through the browser's ImageDecoder (WebCodecs). Null when the browser has no
 * ImageDecoder, cannot read the type, or the image is a single still frame.
 */
export async function openAnimation(file: Blob, onProgress?: Progress): Promise<TimedSource | null> {
  const Decoder = (globalThis as any).ImageDecoder;
  // Browsers without ImageDecoder (Firefox) still decode a single WebP frame, so animated WebP is taken apart by hand.
  const webp = file.type === 'image/webp' ? () => openWebpAnimation(file, onProgress) : () => null;
  if (!Decoder || !file.type) return webp();
  try {
    if (!(await Decoder.isTypeSupported(file.type))) return webp();
  } catch {
    return webp();
  }
  let decoder: any;
  try {
    decoder = new Decoder({ data: await file.arrayBuffer(), type: file.type });
    await decoder.tracks.ready;
    await decoder.completed;
  } catch {
    decoder?.close();
    return webp();
  }
  const track = decoder.tracks.selectedTrack;
  const count: number = track?.frameCount ?? 0;
  if (!track || count <= 1) {
    decoder.close();
    return null;
  }

  // One pass for the timing and the size; frames are decoded again when drawn, so nothing large is kept.
  const delays: number[] = [];
  let width = 0;
  let height = 0;
  let alpha = false;
  for (let i = 0; i < count; i++) {
    const { image } = await decoder.decode({ frameIndex: i });
    // Browsers play a 0 or 10 ms GIF delay at 100 ms; do the same so the animation keeps the speed people know.
    const ms = image.duration ? image.duration / 1000 : 0;
    delays.push(ms <= 10 ? 100 : ms);
    if (i === 0) {
      width = image.displayWidth;
      height = image.displayHeight;
    }
    // A frame's pixel format says nothing (GIF frames decode as BGRA either way): look at a few frames' pixels.
    if (!alpha && (i === 0 || i === count - 1 || i === Math.floor(count / 2))) {
      const { w, h } = sized(width, height, 256);
      const probe = new OffscreenCanvas(w, h).getContext('2d')!;
      probe.drawImage(image, 0, 0, w, h);
      alpha = hasAlpha(probe, w, h);
    }
    image.close();
    if (i % 16 === 0) onProgress?.(`reading animation (${i + 1}/${count} frames)`, ((i + 1) / count) * 100);
  }
  const src = animationSource(delays, width, height, alpha, async (ctx, at, w, h) => {
    const { image } = await decoder.decode({ frameIndex: at });
    try {
      ctx.clearRect(0, 0, w, h);
      ctx.drawImage(image, 0, 0, w, h);
    } finally {
      image.close();
    }
  }, () => decoder.close());
  return src;
}

/** Hand-made animated WebP reader (see webp.ts): null for a still WebP or one the browser cannot decode. */
async function openWebpAnimation(file: Blob, onProgress?: Progress): Promise<TimedSource | null> {
  let anim;
  try {
    anim = parseAnimatedWebp(new Uint8Array(await file.arrayBuffer()));
  } catch {
    return null;
  }
  if (!anim || anim.frames.length < 2) return null;
  const { width, height, frames } = anim;
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  const bitmaps = new Map<number, ImageBitmap>();
  // Keep decoded frames while they stay modest; past that, decode again when drawn.
  let cachedPixels = 0;
  const bitmap = async (i: number) => {
    const have = bitmaps.get(i);
    if (have) return have;
    const f = frames[i];
    const made = await createImageBitmap(new Blob([f.file as BlobPart], { type: 'image/webp' }));
    if (cachedPixels + f.width * f.height <= 48_000_000) {
      bitmaps.set(i, made);
      cachedPixels += f.width * f.height;
    }
    return made;
  };
  let shown = -1;
  // Frames are deltas over the canvas, so showing frame i means replaying from the start (or from where it stands).
  const compose = async (to: number) => {
    if (to < shown) {
      ctx.clearRect(0, 0, width, height);
      shown = -1;
    }
    for (let i = shown + 1; i <= to; i++) {
      const f = frames[i];
      if (i > 0 && frames[i - 1].dispose) {
        const p = frames[i - 1];
        ctx.clearRect(p.x, p.y, p.width, p.height);
      }
      if (!f.blend) ctx.clearRect(f.x, f.y, f.width, f.height);
      const img = await bitmap(i);
      ctx.drawImage(img, f.x, f.y);
      if (!bitmaps.has(i)) img.close();
      shown = i;
    }
  };

  const delays = frames.map((f) => (f.delay <= 10 ? 100 : f.delay));
  try {
    await compose(0);
  } catch {
    return null;
  }
  // Probe a few frames for see-through pixels, as openAnimation does.
  let alpha = false;
  const probeAt = [0, Math.floor(frames.length / 2), frames.length - 1];
  for (const i of probeAt) {
    onProgress?.(`reading animation (${i + 1}/${frames.length} frames)`, ((i + 1) / frames.length) * 100);
    try {
      await compose(i);
    } catch {
      return null;
    }
    if (!alpha) alpha = hasAlpha(ctx, width, height);
  }
  const close = () => {
    for (const b of bitmaps.values()) b.close();
    bitmaps.clear();
  };
  return animationSource(
    delays,
    width,
    height,
    alpha,
    async (out, at, w, h) => {
      await compose(at);
      out.clearRect(0, 0, w, h);
      out.drawImage(canvas, 0, 0, w, h);
    },
    close,
  );
}

/** A timed source over frames with their own delays (ms); {@code draw} paints frame {@code at} scaled to w x h. */
function animationSource(
  delays: number[],
  width: number,
  height: number,
  alpha: boolean,
  draw: TimedSource['draw'],
  close: () => void,
): TimedSource {
  const count = delays.length;
  const starts: number[] = [];
  let t = 0;
  for (const d of delays) {
    starts.push(t);
    t += d;
  }
  const total = t / 1000;

  return {
    kind: 'animation',
    width,
    height,
    duration: total,
    nativeFps: count / Math.max(total, 1e-3),
    alpha,
    plan(range, fps) {
      // Keep the source's own timing; frames shorter than one output frame merge into the one before, so a
      // fast animation plays at the same speed with fewer frames instead of in slow motion.
      const from = range.start * 1000;
      const to = from + range.length * 1000;
      const minDelay = Math.ceil(1000 / fps);
      const out: { at: number; delay: number }[] = [];
      for (let i = 0; i < count; i++) {
        const s = Math.max(starts[i], from);
        const e = Math.min(starts[i] + delays[i], to);
        if (e <= s) continue;
        const d = e - s;
        const last = out[out.length - 1];
        if (last && last.delay < minDelay) last.delay += d;
        else out.push({ at: i, delay: d });
      }
      return finishDelays(out, minDelay, range.length * 1000);
    },
    draw,
    close,
  };
}

/** Rounds delays to whole ms of at least {@code minDelay}, without the total going past {@code maxMs}. */
function finishDelays(out: { at: number; delay: number }[], minDelay: number, maxMs: number) {
  let total = 0;
  const kept: { at: number; delay: number }[] = [];
  for (const f of out) {
    const delay = Math.max(minDelay, Math.min(30000, Math.round(f.delay)));
    if (total + delay > maxMs + 0.5 && kept.length > 0) break;
    kept.push({ at: f.at, delay });
    total += delay;
  }
  return kept;
}

/**
 * Resamples {@code range} of {@code src} into an FTAS bundle the server will accept. Lowers the frame rate first
 * (down to 10), then the size (down to 128 px), then both further, re-measuring after each change.
 * {@code budgetScale} below 1 aims lower still, for a retry after the server said the result was too big.
 */
export async function fitTimed(
  src: TimedSource,
  range: Range,
  caps: AssetCaps,
  progress: Progress,
  budgetScale = 1,
): Promise<Fitted> {
  const length = Math.min(range.length, caps.maxSeconds, src.duration - range.start);
  const r = { start: Math.max(0, range.start), length: Math.max(0.05, length) };
  const png = src.alpha;
  const budget = caps.maxFileBytes * (png ? PNG_MARGIN : JPEG_MARGIN) * budgetScale;
  let fps = Math.max(MIN_FPS, Math.min(caps.maxFps, src.nativeFps > 0 ? Math.ceil(src.nativeFps) : caps.maxFps));
  let side = Math.min(caps.maxDimension, Math.max(src.width, src.height));

  for (let attempt = 0; attempt < 16; attempt++) {
    let plan = src.plan(r, fps);
    if (plan.length > MAX_FRAMES) {
      fps = Math.max(MIN_FPS, Math.floor((fps * MAX_FRAMES) / plan.length));
      continue;
    }
    const { w, h } = sized(src.width, src.height, side);
    if (w * h * plan.length > PIXEL_BUDGET) {
      side = Math.max(MIN_SIDE, Math.floor(side * Math.sqrt(PIXEL_BUDGET / (w * h * plan.length)) * 0.98));
      continue;
    }
    const canvas = new OffscreenCanvas(w, h);
    const ctx = canvas.getContext('2d')!;
    ctx.imageSmoothingQuality = 'high';
    const encode = async (at: number) => {
      await src.draw(ctx, at, w, h);
      const blob = png
        ? await canvas.convertToBlob({ type: 'image/png' })
        : await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 });
      return new Uint8Array(await blob.arrayBuffer());
    };
    const label = `${w}×${h}, ${fps} fps`;

    // Measure a few frames spread over the part before encoding all of them.
    const samples = Math.min(plan.length, 6);
    let sampled = 0;
    for (let i = 0; i < samples; i++) {
      progress(`measuring (${label})`, ((i + 1) / samples) * 100);
      sampled += (await encode(plan[Math.floor(((i + 0.5) * plan.length) / samples)].at)).length + 8;
    }
    const estimate = 8 + (sampled / samples) * plan.length;
    if (estimate > budget) {
      [fps, side] = shrink(fps, side, budget / estimate);
      continue;
    }

    const frames: Uint8Array[] = [];
    let total = 8;
    let over = false;
    for (let i = 0; i < plan.length; i++) {
      const f = await encode(plan[i].at);
      frames.push(f);
      total += f.length + 8;
      progress(`converting (${label}, ${i + 1}/${plan.length} frames)`, ((i + 1) / plan.length) * 100);
      // Projected past the budget by more than the estimate's slack: stop early and try smaller.
      if (total > budget && i < plan.length - 1) {
        over = true;
        break;
      }
    }
    if (over || total > budget) {
      const projected = over ? (total / frames.length) * plan.length : total;
      [fps, side] = shrink(fps, side, (budget / projected) * 0.97);
      continue;
    }
    plan = plan.slice(0, frames.length);
    return { bytes: pack(frames, plan.map((p) => p.delay)), width: w, height: h, frames: frames.length, fps };
  }
  throw new Error('it does not fit the server’s size limit even at a low frame rate and size.');
}

/** Next settings after a result {@code factor} times too big (factor below 1). */
function shrink(fps: number, side: number, factor: number): [number, number] {
  const f = Math.max(0.05, Math.min(0.97, factor));
  if (fps > 10) return [Math.max(10, Math.floor(fps * f)), side];
  if (side > 128) return [fps, Math.max(128, Math.floor(side * Math.sqrt(f)))];
  if (fps > 5) return [Math.max(5, Math.floor(fps * f)), side];
  if (side > 64) return [fps, Math.max(64, Math.floor(side * Math.sqrt(f)))];
  if (fps > MIN_FPS) return [Math.max(MIN_FPS, Math.floor(fps * f)), side];
  if (side > MIN_SIDE) return [fps, Math.max(MIN_SIDE, Math.floor(side * Math.sqrt(f)))];
  throw new Error('it does not fit the server’s size limit even at a low frame rate and size.');
}

function pack(frames: Uint8Array[], delays: number[]): Blob {
  const total = 8 + frames.reduce((n, f) => n + 8 + f.length, 0);
  const out = new Uint8Array(total);
  const view = new DataView(out.buffer);
  view.setInt32(0, FTAS_MAGIC);
  view.setInt32(4, frames.length);
  let at = 8;
  frames.forEach((f, i) => {
    view.setInt32(at, delays[i]);
    view.setInt32(at + 4, f.length);
    out.set(f, at + 8);
    at += 8 + f.length;
  });
  return new Blob([out]);
}

function sized(width: number, height: number, side: number): { w: number; h: number } {
  const scale = Math.min(1, side / Math.max(width, height));
  return { w: Math.max(1, Math.round(width * scale)), h: Math.max(1, Math.round(height * scale)) };
}

function hasAlpha(ctx: OffscreenCanvasRenderingContext2D, w: number, h: number): boolean {
  const data = ctx.getImageData(0, 0, w, h).data;
  for (let i = 3; i < data.length; i += 4) if (data[i] < 255) return true;
  return false;
}
