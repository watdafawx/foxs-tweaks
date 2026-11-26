// Wire types for docs/hologram-web-protocol.md (protocol 5). Keep in step with client/web/HologramWebJson.java.

export const PROTOCOL = 5;

export type Vec3 = [number, number, number];
export type Kind = 'BLOCK' | 'ENTITY' | 'PARTICLE' | 'STRUCTURE' | 'IMAGE';

export interface Effect {
  type: string;
  strength: number;
  speed: number;
  color: number;
  color2: number;
  axis: number;
  phase: number;
  trigger: string;
  /** Audio modulations of this effect (STRENGTH, SPEED, PHASE, HUE); absent when none. */
  audio?: AudioMod[];
}

export type AudioSource = 'AUTO' | 'JUKEBOX' | 'MUSIC' | 'SYSTEM';
export type AudioSignal = 'LEVEL' | 'BASS' | 'MID' | 'HIGH' | 'BEAT';
export type AudioParam =
  | 'STRENGTH'
  | 'SPEED'
  | 'PHASE'
  | 'HUE'
  | 'OFFSET_X'
  | 'OFFSET_Y'
  | 'OFFSET_Z'
  | 'ROT_X'
  | 'ROT_Y'
  | 'ROT_Z'
  | 'SCALE'
  | 'VISIBLE'
  | 'BRIGHT'
  | 'RATE';

/** One audio modulation: value += amount x signal, with its own envelope. See HologramAudioMod.java. */
export interface AudioMod {
  param: AudioParam;
  source: AudioSource;
  signal: AudioSignal;
  amount: number;
  sensitivity: number;
  /** Envelope attack / release, seconds. */
  attack: number;
  release: number;
  /** 0 = smooth; above 0 the signal is a step (on while over the threshold). */
  threshold: number;
  /** Clamp of the modulated value; absent = no clamp. */
  min?: number;
  max?: number;
}

/** An audio modulation of a root / group / layer value, kept on the timeline. */
export interface AudioLink extends AudioMod {
  target: TargetKind;
  id: string;
}

export interface Motion {
  spin: number;
  spinAxis: number;
  hover: number;
  hoverPeriod: number;
  fullbright: boolean;
}

export interface Group extends Motion {
  id: string;
  name: string;
  visible: boolean;
  offset: Vec3;
  rotation: Vec3;
  scale: number;
  effects: Effect[];
}

export interface LayerData {
  key: string;
  bytes: number;
  snbt?: string;
}

export interface Layer extends Motion {
  /** Stable id timeline tracks point at; the game fills in missing ones. */
  uid?: string;
  kind: Kind;
  id: string;
  props: Record<string, string>;
  group: string;
  visible: boolean;
  offset: Vec3;
  rotation: Vec3;
  scale: number;
  particleRate: number;
  particleSpeed: number;
  effects: Effect[];
  data?: LayerData;
}

export type TargetKind = 'ROOT' | 'GROUP' | 'LAYER';
export type Prop =
  | 'OFFSET_X'
  | 'OFFSET_Y'
  | 'OFFSET_Z'
  | 'ROT_X'
  | 'ROT_Y'
  | 'ROT_Z'
  | 'SCALE'
  | 'VISIBLE'
  | 'TINT'
  | 'EFFECT';
export type Ease = 'LINEAR' | 'HOLD' | 'EASE_IN' | 'EASE_OUT' | 'EASE_IN_OUT' | 'CUBIC';
export type PlayMode = 'LOOP' | 'PINGPONG' | 'ONCE';

export interface Key {
  /** Seconds into the clip. */
  t: number;
  /** Value; packed 0xRRGGBB for TINT, 0/1 for VISIBLE. */
  v: number;
  /** Easing of the segment that leaves this key. */
  ease: Ease;
  /** CSS cubic-bezier handles, only for CUBIC. */
  bezier?: [number, number, number, number];
}

export interface Track {
  target: TargetKind;
  /** Group id or layer uid; empty for ROOT. */
  id: string;
  prop: Prop;
  /** Effect index for EFFECT tracks. */
  effect?: number;
  keys: Key[];
}

export interface Timeline {
  length: number;
  mode: PlayMode;
  start: string;
  tracks: Track[];
  /** Audio modulations of root / group / layer values; absent when none. */
  audio?: AudioLink[];
}

export interface Spec {
  id: string;
  pos: Vec3;
  owner: string;
  root: { offset: Vec3; rotation: Vec3; scale: number };
  renderDistance: number;
  maxRenderDistance: number;
  reveal: { radius: number; seconds: number; style: number };
  groups: Group[];
  layers: Layer[];
  timeline?: Timeline | null;
}

export interface EffectDef {
  type: string;
  label: string;
  short: string;
  family: 'fx' | 'motion' | 'entity';
  usesAxis: boolean;
  usesAmount: boolean;
  usesPhase: boolean;
  usesSpeed: boolean;
  usesColor: boolean;
  usesColor2: boolean;
  min: number;
  max: number;
  step: number;
  decimals: number;
  defaults: Effect;
}

export interface BlockDef {
  id: string;
  name: string;
  props?: number[];
  defaults?: Record<string, string>;
  blockEntity?: boolean;
}

export type Range = [number, number];

export interface PropDef {
  name: Prop;
  min: number;
  max: number;
  stepped: boolean;
}

export interface Registries {
  properties: { name: string; values: string[] }[];
  blocks: BlockDef[];
  entities: { id: string; name: string; animations?: string[] }[];
  particles: string[];
  effects: EffectDef[];
  triggers: { name: string; label: string; pulse: boolean }[];
  presets: { name: string; effects: Effect[] }[];
  revealStyles: string[];
  limits: {
    maxLayers: number;
    maxGroups: number;
    maxEffects: number;
    maxNameLength: number;
    offset: Range;
    rootScale: Range;
    groupScale: Range;
    layerScale: Range;
    spin: Range;
    hover: Range;
    hoverPeriod: Range;
    speed: Range;
    revealRadius: Range;
    revealSeconds: Range;
    renderDistance: Range;
    particleRate: Range;
    particleSpeed: Range;
  };
  timeline?: {
    length: Range;
    modes: PlayMode[];
    eases: Ease[];
    props: PropDef[];
  };
}

export type SelectionKind = 'root' | 'group' | 'layer';

export interface Selection {
  kind: SelectionKind;
  layer: number;
  group: string | null;
  soloLayer: number;
  soloGroup: string | null;
}

/** Spin / effect axis ids as the mod stores them. */
export const AXIS_Y = 0;
export const AXIS_X = 1;
export const AXIS_Z = 2;

// ---------------------------------------------------------------- assets (protocol 3)

/** What the game said about uploads in its hello. */
export interface AssetCaps {
  /** False when the server has no asset store (Fox's Tweaks missing there, or too old). */
  available: boolean;
  chunkBytes: number;
  maxUploadBytes: number;
  maxImageLayers: number;
  maxFileBytes: number;
  maxDimension: number;
  maxFps: number;
  maxSeconds: number;
}

/** One row of the world's asset library. */
export interface AssetEntry {
  /** SHA-256, 64 hex characters; an IMAGE layer's id. */
  id: string;
  name: string;
  type: string;
  width: number;
  height: number;
  frames: number;
  durationMs: number;
  bytes: number;
  /** How many saved image layers use it; the server refuses to delete one that is in use. */
  used: number;
  mine: boolean;
  owner: string;
  created: number;
}

/**
 * IMAGE layer settings, kept in the layer's props as strings (HologramImage.java). Missing keys are the defaults:
 * w 1, h 0 (= keep the picture's aspect), billboard FIXED, sides double, play LOOP, speed 1, start 0.
 */
export type Billboard = 'FIXED' | 'FACE' | 'FACE_Y';
export type ImagePlayback = 'LOOP' | 'ONCE' | 'PINGPONG';

// ---------------------------------------------------------------- icons (protocol 5)

/** What the game said about icons in its hello; absent from a game older than protocol 5. */
export interface IconCaps {
  available: boolean;
  /** Pixels per icon cell in a sheet. */
  size: number;
  /** Most ids per request. */
  maxIds: number;
  maxCols: number;
  kinds: string[];
}
