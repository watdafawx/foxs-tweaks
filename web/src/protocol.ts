// Wire types for docs/hologram-web-protocol.md (protocol 1). Keep in step with client/web/HologramWebJson.java.

export const PROTOCOL = 1;

export type Vec3 = [number, number, number];
export type Kind = 'BLOCK' | 'ENTITY' | 'PARTICLE';

export interface Effect {
  type: string;
  strength: number;
  speed: number;
  color: number;
  color2: number;
  axis: number;
  phase: number;
  trigger: string;
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

export interface Registries {
  properties: { name: string; values: string[] }[];
  blocks: BlockDef[];
  entities: { id: string; name: string }[];
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
