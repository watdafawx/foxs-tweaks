// Takes an animated WebP apart in plain JS, for browsers without ImageDecoder (Firefox). Each frame is re-wrapped as
// a stand-alone still WebP, which every browser can decode with createImageBitmap; fit.ts composites them.

export interface WebpFrame {
  x: number;
  y: number;
  width: number;
  height: number;
  /** Milliseconds as stored (may be 0). */
  delay: number;
  /** Clear this frame's rectangle once it has been shown. */
  dispose: boolean;
  /** Alpha-blend over the canvas; false replaces the rectangle. */
  blend: boolean;
  /** A complete still WebP file holding just this frame. */
  file: Uint8Array;
}

export interface WebpAnimation {
  width: number;
  height: number;
  frames: WebpFrame[];
}

const u24 = (b: Uint8Array, o: number) => b[o] | (b[o + 1] << 8) | (b[o + 2] << 16);
const u32 = (b: Uint8Array, o: number) => (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
const tag = (b: Uint8Array, o: number) => String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);

/** Null when {@code bytes} is not an animated WebP, or is damaged. */
export function parseAnimatedWebp(bytes: Uint8Array, maxFrames = 4096): WebpAnimation | null {
  if (bytes.length < 30 || tag(bytes, 0) !== 'RIFF' || tag(bytes, 8) !== 'WEBP') return null;
  const end = Math.min(bytes.length, 8 + u32(bytes, 4));
  let width = 0;
  let height = 0;
  let animated = false;
  const frames: WebpFrame[] = [];
  let at = 12;
  while (at + 8 <= end) {
    const name = tag(bytes, at);
    const size = u32(bytes, at + 4);
    const body = at + 8;
    if (body + size > bytes.length) return null;
    if (name === 'VP8X' && size >= 10) {
      animated = (bytes[body] & 0x02) !== 0;
      width = u24(bytes, body + 4) + 1;
      height = u24(bytes, body + 7) + 1;
    } else if (name === 'ANMF' && size >= 16) {
      if (frames.length >= maxFrames) return null;
      const fw = u24(bytes, body + 6) + 1;
      const fh = u24(bytes, body + 9) + 1;
      const flags = bytes[body + 15];
      // The frame's own chunks (ALPH + VP8, or VP8L) follow the 16-byte header, padding included.
      const data = bytes.subarray(body + 16, body + size);
      frames.push({
        x: u24(bytes, body) * 2,
        y: u24(bytes, body + 3) * 2,
        width: fw,
        height: fh,
        delay: u24(bytes, body + 12),
        dispose: (flags & 1) !== 0,
        blend: (flags & 2) === 0,
        file: stillFile(data, fw, fh),
      });
    }
    at = body + size + (size & 1);
  }
  if (!animated || frames.length === 0 || width < 1 || height < 1) return null;
  return { width, height, frames };
}

/** RIFF + VP8X (alpha flag, frame size) + the frame's chunks: a valid still WebP. */
function stillFile(data: Uint8Array, w: number, h: number): Uint8Array {
  const out = new Uint8Array(12 + 18 + data.length);
  const v = new DataView(out.buffer);
  out.set([0x52, 0x49, 0x46, 0x46], 0); // RIFF
  v.setUint32(4, out.length - 8, true);
  out.set([0x57, 0x45, 0x42, 0x50], 8); // WEBP
  out.set([0x56, 0x50, 0x38, 0x58], 12); // VP8X
  v.setUint32(16, 10, true);
  out[20] = 0x10; // alpha present
  out[24] = (w - 1) & 255;
  out[25] = ((w - 1) >> 8) & 255;
  out[26] = ((w - 1) >> 16) & 255;
  out[27] = (h - 1) & 255;
  out[28] = ((h - 1) >> 8) & 255;
  out[29] = ((h - 1) >> 16) & 255;
  out.set(data, 30);
  return out;
}
