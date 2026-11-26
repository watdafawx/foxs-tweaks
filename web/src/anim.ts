// Timeline maths, the same as hologram/HologramTimelineEval.java, so the graph editor draws what the game plays,
// plus the helpers that keep a spec's timeline consistent while layers, groups and effects are edited.

import type { Ease, Effect, Key, PlayMode, Prop, Spec, TargetKind, Timeline, Track } from './protocol';

export const FRAME = 1 / 20;

export function localTime(mode: PlayMode, length: number, elapsed: number): number {
  if (mode === 'LOOP') {
    const t = elapsed % length;
    return t < 0 ? t + length : t;
  }
  if (mode === 'PINGPONG') {
    let p = elapsed % (2 * length);
    if (p < 0) p += 2 * length;
    return p <= length ? p : 2 * length - p;
  }
  return Math.max(0, Math.min(length, elapsed));
}

/** Bezier handles each named easing stands for (also what a handle drag starts from). */
export const PRESET_BEZIER: Record<Exclude<Ease, 'HOLD' | 'CUBIC'>, [number, number, number, number]> = {
  LINEAR: [0.33, 0.33, 0.67, 0.67],
  EASE_IN: [0.42, 0, 1, 1],
  EASE_OUT: [0, 0, 0.58, 1],
  EASE_IN_OUT: [0.42, 0, 0.58, 1],
};

export function handlesOf(k: Key): [number, number, number, number] {
  if (k.ease === 'CUBIC') return k.bezier ?? [0.33, 0.33, 0.67, 0.67];
  if (k.ease === 'HOLD') return [0.33, 0, 0.67, 0];
  return PRESET_BEZIER[k.ease];
}

function bez(p1: number, p2: number, s: number): number {
  const inv = 1 - s;
  return 3 * inv * inv * s * p1 + 3 * inv * s * s * p2 + s * s * s;
}

function bezSlope(p1: number, p2: number, s: number): number {
  const inv = 1 - s;
  return 3 * inv * inv * p1 + 6 * inv * s * (p2 - p1) + 3 * s * s * (1 - p2);
}

export function cubicBezier(x1: number, y1: number, x2: number, y2: number, u: number): number {
  let s = u;
  for (let i = 0; i < 8; i++) {
    const x = bez(x1, x2, s) - u;
    if (Math.abs(x) < 1e-6) return bez(y1, y2, s);
    const d = bezSlope(x1, x2, s);
    if (Math.abs(d) < 1e-6) break;
    s -= x / d;
    if (s < 0 || s > 1) break;
  }
  let lo = 0;
  let hi = 1;
  s = u;
  for (let i = 0; i < 40; i++) {
    const x = bez(x1, x2, s);
    if (Math.abs(x - u) < 1e-6) break;
    if (x < u) lo = s;
    else hi = s;
    s = (lo + hi) / 2;
  }
  return bez(y1, y2, s);
}

export function ease(k: Key, u: number): number {
  if (u <= 0) return 0;
  if (u >= 1) return 1;
  if (k.ease === 'LINEAR') return u;
  if (k.ease === 'HOLD') return 0;
  const [x1, y1, x2, y2] = handlesOf(k);
  return cubicBezier(x1, y1, x2, y2, u);
}

// ---------------------------------------------------------------- OKLab colour blend (as the game does it)

function toLinear(c: number): number {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

function toSrgb(v: number): number {
  v = Math.max(0, Math.min(1, v));
  const s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
  return Math.round(Math.max(0, Math.min(1, s)) * 255);
}

function toOklab(rgb: number): [number, number, number] {
  const r = toLinear((rgb >> 16) & 255);
  const g = toLinear((rgb >> 8) & 255);
  const b = toLinear(rgb & 255);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function fromOklab(lab: [number, number, number]): number {
  let l = lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2];
  let m = lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2];
  let s = lab[0] - 0.0894841775 * lab[1] - 1.291485548 * lab[2];
  l = l * l * l;
  m = m * m * m;
  s = s * s * s;
  const r = toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
  const g = toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
  const b = toSrgb(-0.0041960863 * l - 0.7034186168 * m + 1.707614701 * s);
  return (r << 16) | (g << 8) | b;
}

export function lerpColor(a: number, b: number, u: number): number {
  if (u <= 0 || a === b) return a;
  if (u >= 1) return b;
  const la = toOklab(a);
  const lb = toOklab(b);
  return fromOklab([la[0] + (lb[0] - la[0]) * u, la[1] + (lb[1] - la[1]) * u, la[2] + (lb[2] - la[2]) * u]);
}

// ---------------------------------------------------------------- sampling

export function keyBefore(keys: Key[], t: number): number {
  let lo = 0;
  let hi = keys.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if (keys[mid].t <= t) {
      found = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return found;
}

export function sample(track: Track, t: number): number {
  const keys = track.keys;
  if (keys.length === 0) return NaN;
  const i = keyBefore(keys, t);
  if (i < 0) return keys[0].v;
  const a = keys[i];
  if (i >= keys.length - 1) return a.v;
  const b = keys[i + 1];
  if (track.prop === 'VISIBLE' || a.ease === 'HOLD') return a.v;
  const span = b.t - a.t;
  const u = span <= 0 ? 1 : (t - a.t) / span;
  const e = ease(a, u);
  if (track.prop === 'TINT') return lerpColor(a.v, b.v, Math.max(0, Math.min(1, e)));
  return a.v + (b.v - a.v) * e;
}

// ---------------------------------------------------------------- identity of tracks and their static values

export function trackKey(t: { target: TargetKind; id: string; prop: Prop; effect?: number }): string {
  return `${t.target}:${t.id}:${t.prop}:${t.prop === 'EFFECT' ? (t.effect ?? 0) : 0}`;
}

export const PROP_LABEL: Record<Prop, string> = {
  OFFSET_X: 'Offset X',
  OFFSET_Y: 'Offset Y',
  OFFSET_Z: 'Offset Z',
  ROT_X: 'Pitch (X)',
  ROT_Y: 'Yaw (Y)',
  ROT_Z: 'Roll (Z)',
  SCALE: 'Scale',
  VISIBLE: 'Visible',
  TINT: 'Tint',
  EFFECT: 'Effect',
};

export const PROP_UNIT: Partial<Record<Prop, string>> = {
  OFFSET_X: 'b',
  OFFSET_Y: 'b',
  OFFSET_Z: 'b',
  ROT_X: '°',
  ROT_Y: '°',
  ROT_Z: '°',
};

/** Spec JSON rotation is [yaw, pitch, roll]. */
const ROT_INDEX: Partial<Record<Prop, number>> = { ROT_Y: 0, ROT_X: 1, ROT_Z: 2 };
const OFF_INDEX: Partial<Record<Prop, number>> = { OFFSET_X: 0, OFFSET_Y: 1, OFFSET_Z: 2 };

type Transformable = { offset: [number, number, number]; rotation: [number, number, number]; scale: number };

export function targetOf(
  spec: Spec,
  kind: TargetKind,
  id: string,
): { obj: Transformable; visible?: boolean; effects?: Effect[] } | null {
  if (kind === 'ROOT') return { obj: spec.root };
  if (kind === 'GROUP') {
    const g = spec.groups.find((x) => x.id === id);
    return g ? { obj: g, visible: g.visible, effects: g.effects } : null;
  }
  const l = spec.layers.find((x) => x.uid === id);
  return l ? { obj: l, visible: l.visible, effects: l.effects } : null;
}

/** The value a property has with no keys (what the game draws when nothing animates it). */
export function baseValue(spec: Spec, t: { target: TargetKind; id: string; prop: Prop; effect?: number }): number {
  const x = targetOf(spec, t.target, t.id);
  if (!x) return 0;
  const p = t.prop;
  if (OFF_INDEX[p] !== undefined) return x.obj.offset[OFF_INDEX[p]!];
  if (ROT_INDEX[p] !== undefined) return x.obj.rotation[ROT_INDEX[p]!];
  if (p === 'SCALE') return x.obj.scale;
  if (p === 'VISIBLE') return 1;
  if (p === 'TINT') return 0xffffff;
  return x.effects?.[t.effect ?? 0]?.strength ?? 0;
}

/** Reads the inspector-visible value of a property on any target object. */
export function readProp(obj: any, prop: Prop, effect = 0): number | undefined {
  if (!obj) return undefined;
  if (OFF_INDEX[prop] !== undefined) return obj.offset?.[OFF_INDEX[prop]!];
  if (ROT_INDEX[prop] !== undefined) return obj.rotation?.[ROT_INDEX[prop]!];
  if (prop === 'SCALE') return obj.scale;
  if (prop === 'VISIBLE') return undefined; // the visibility toggle is the editor's hide, not an animatable value
  if (prop === 'EFFECT') return obj.effects?.[effect]?.strength;
  return undefined;
}

export function writeProp(obj: any, prop: Prop, effect: number, v: number): void {
  if (OFF_INDEX[prop] !== undefined) obj.offset[OFF_INDEX[prop]!] = v;
  else if (ROT_INDEX[prop] !== undefined) obj.rotation[ROT_INDEX[prop]!] = v;
  else if (prop === 'SCALE') obj.scale = v;
  else if (prop === 'EFFECT' && obj.effects?.[effect]) obj.effects[effect].strength = v;
}

export function emptyTimeline(): Timeline {
  return { length: 4, mode: 'LOOP', start: 'ALWAYS', tracks: [] };
}

export function roundTime(t: number): number {
  return Math.round(t * 1000) / 1000;
}

/** Sorts keys and merges equal times (the later one wins), like the game. */
export function normaliseKeys(keys: Key[]): Key[] {
  const sorted = keys.map((k, i) => ({ k: { ...k, t: roundTime(k.t) }, i })).sort((a, b) => a.k.t - b.k.t || a.i - b.i);
  const out: Key[] = [];
  for (const { k } of sorted) {
    if (out.length && out[out.length - 1].t === k.t) out[out.length - 1] = k;
    else out.push(k);
  }
  return out;
}

let uidN = 0;

function freshUid(taken: Set<string>): string {
  let u: string;
  do u = `w${Date.now().toString(36).slice(-5)}${(++uidN).toString(36)}`;
  while (taken.has(u));
  return u;
}

/**
 * Keeps the timeline valid after any edit: every layer gets a unique uid (a duplicated layer gets a new one, so
 * its tracks stay with the original), and tracks whose layer, group or effect is gone are dropped. The game
 * refuses a spec that breaks either rule.
 */
export function fixTimeline(spec: Spec): void {
  const taken = new Set<string>();
  for (const l of spec.layers) {
    if (!l.uid || taken.has(l.uid) || !/^[A-Za-z0-9_-]{1,24}$/.test(l.uid)) l.uid = freshUid(taken);
    taken.add(l.uid);
  }
  const tl = spec.timeline;
  if (!tl) return;
  tl.tracks = tl.tracks.filter((t) => {
    const x = targetOf(spec, t.target, t.id);
    if (!x) return false;
    if (t.prop === 'EFFECT') return t.target !== 'ROOT' && (t.effect ?? 0) < (x.effects?.length ?? 0);
    return true;
  });
  if (tl.audio) tl.audio = tl.audio.filter((a) => !!targetOf(spec, a.target, a.id));
  for (const t of tl.tracks) for (const k of t.keys) if (k.t > tl.length) k.t = tl.length;
}

/** Effect {@code index} of a target was removed: its tracks go, later effects' tracks shift down by one. */
export function effectRemoved(spec: Spec, target: TargetKind, id: string, index: number): void {
  const tl = spec.timeline;
  if (!tl) return;
  tl.tracks = tl.tracks.filter((t) => !(t.target === target && t.id === id && t.prop === 'EFFECT' && t.effect === index));
  for (const t of tl.tracks)
    if (t.target === target && t.id === id && t.prop === 'EFFECT' && (t.effect ?? 0) > index) t.effect = (t.effect ?? 0) - 1;
}

/** Effects {@code a} and {@code b} of a target swapped places: so do their tracks. */
export function effectsSwapped(spec: Spec, target: TargetKind, id: string, a: number, b: number): void {
  const tl = spec.timeline;
  if (!tl) return;
  for (const t of tl.tracks) {
    if (t.target !== target || t.id !== id || t.prop !== 'EFFECT') continue;
    if (t.effect === a) t.effect = b;
    else if (t.effect === b) t.effect = a;
  }
}

/** A group's id was renamed (or a group removed: pass null) - its tracks follow or go. */
export function groupRenamed(spec: Spec, from: string, to: string | null): void {
  const tl = spec.timeline;
  if (!tl) return;
  if (to === null) tl.tracks = tl.tracks.filter((t) => !(t.target === 'GROUP' && t.id === from));
  else for (const t of tl.tracks) if (t.target === 'GROUP' && t.id === from) t.id = to;
}

export function hex(c: number): string {
  return '#' + (c & 0xffffff).toString(16).padStart(6, '0');
}
