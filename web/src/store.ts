// Connection + state. The game is the source of truth: this keeps an optimistic copy of the spec while edits are
// in flight and takes the game's `state` back once it has acknowledged everything sent (state.ack >= last seq).

import { useEffect, useState } from 'preact/hooks';
import type { BlockDef, EffectDef, Prop, Registries, Selection, SelectionKind, Spec, TargetKind, Track } from './protocol';
import { PROTOCOL } from './protocol';
import { emptyTimeline, fixTimeline, localTime, normaliseKeys, readProp, roundTime, trackKey } from './anim';

export type Status = 'connecting' | 'open' | 'lost' | 'closed';

export interface Toast {
  id: number;
  text: string;
  kind: 'error' | 'info';
}

export interface AppState {
  status: Status;
  closedReason: string;
  modVersion: string;
  minecraft: string;
  registries: Registries | null;
  spec: Spec | null;
  canUndo: boolean;
  canRedo: boolean;
  selection: Selection;
  toasts: Toast[];
  timelineCaps: { maxTracks: number; maxKeys: number };
  /** Auto-key: a changed property with no track yet gets one, keyed at the playhead. */
  autoKey: boolean;
}

export const S: AppState = {
  status: 'connecting',
  closedReason: '',
  modVersion: '',
  minecraft: '',
  registries: null,
  spec: null,
  canUndo: false,
  canRedo: false,
  selection: { kind: 'layer', layer: 0, group: null, soloLayer: -1, soloGroup: null },
  toasts: [],
  timelineCaps: { maxTracks: 64, maxKeys: 2048 },
  autoKey: false,
};

export const REG = {
  blocks: new Map<string, BlockDef>(),
  entities: new Map<string, string>(),
  effects: new Map<string, EffectDef>(),
};

// ---------------------------------------------------------------- subscriptions

const listeners = new Set<() => void>();
let notifyQueued = false;

export function notify(): void {
  if (notifyQueued) return;
  notifyQueued = true;
  queueMicrotask(() => {
    notifyQueued = false;
    listeners.forEach((l) => l());
  });
}

export function useApp(): AppState {
  const [, setTick] = useState(0);
  useEffect(() => {
    const l = () => setTick((t) => t + 1);
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  }, []);
  return S;
}

// ---------------------------------------------------------------- toasts

let toastId = 0;

export function toast(text: string, kind: Toast['kind'] = 'info'): void {
  const id = ++toastId;
  S.toasts = [...S.toasts.slice(-3), { id, text, kind }];
  notify();
  setTimeout(() => {
    S.toasts = S.toasts.filter((t) => t.id !== id);
    notify();
  }, kind === 'error' ? 6000 : 3500);
}

// ---------------------------------------------------------------- socket

let ws: WebSocket | null = null;
let seq = 0;
let lastSent = 0;
let lastAck = 0;
let retries = 0;
let closedByServer = false;

function wsUrl(): string {
  // The page lives at /<token>/ (or /<token>/index.html); the socket is /<token>/ws on the same origin.
  const base = location.pathname.replace(/[^/]*$/, '');
  return `ws://${location.host}${base}ws`;
}

export function connect(): void {
  S.status = 'connecting';
  notify();
  seq = 0;
  lastSent = 0;
  lastAck = 0;
  pending = null;
  const sock = new WebSocket(wsUrl());
  ws = sock;
  sock.onopen = () => {
    retries = 0;
    S.status = 'open';
    resubscribeMeter();
    notify();
  };
  sock.onmessage = (e) => {
    let msg: any;
    try {
      msg = JSON.parse(String(e.data));
    } catch {
      return;
    }
    handle(msg);
  };
  sock.onclose = () => {
    if (ws === sock) ws = null;
    if (closedByServer) {
      S.status = 'closed';
      notify();
      return;
    }
    S.status = 'lost';
    notify();
    if (retries < 5) setTimeout(connect, 500 * 2 ** retries++);
  };
}

function handle(msg: any): void {
  switch (msg.type) {
    case 'hello': {
      if (msg.protocol !== PROTOCOL)
        toast(`The game speaks protocol ${msg.protocol}, this page ${PROTOCOL}. Some things may not work.`, 'error');
      S.modVersion = String(msg.modVersion ?? '');
      S.minecraft = String(msg.minecraft ?? '');
      const reg = msg.registries as Registries;
      S.registries = reg;
      if (msg.timelineCaps)
        S.timelineCaps = { maxTracks: Number(msg.timelineCaps.maxTracks), maxKeys: Number(msg.timelineCaps.maxKeys) };
      REG.blocks.clear();
      REG.entities.clear();
      REG.effects.clear();
      reg.blocks.forEach((b) => REG.blocks.set(b.id, b));
      reg.entities.forEach((e) => REG.entities.set(e.id, e.name));
      reg.effects.forEach((e) => REG.effects.set(e.type, e));
      break;
    }
    case 'state': {
      if (typeof msg.ack === 'number') lastAck = Math.max(lastAck, msg.ack);
      S.canUndo = !!msg.canUndo;
      S.canRedo = !!msg.canRedo;
      // Older than what we have sent: our own edit is still on its way, keep the optimistic copy.
      if (lastAck >= lastSent && pending === null) S.spec = msg.spec as Spec;
      break;
    }
    case 'selection':
      if (lastAck >= lastSent)
        S.selection = {
          kind: msg.kind as SelectionKind,
          layer: msg.layer,
          group: msg.group ?? null,
          soloLayer: msg.soloLayer,
          soloGroup: msg.soloGroup ?? null,
        };
      break;
    case 'playhead':
      return; // the game confirms; the browser already shows its own playhead
    case 'meter':
      METER.level = Number(msg.level) || 0;
      METER.bass = Number(msg.bass) || 0;
      METER.mid = Number(msg.mid) || 0;
      METER.high = Number(msg.high) || 0;
      METER.beat = Number(msg.beat) || 0;
      METER.system = String(msg.system ?? '');
      meterListeners.forEach((l) => l());
      return;
    case 'error':
      toast(String(msg.message ?? 'The game refused that.'), 'error');
      break;
    case 'closed':
      closedByServer = true;
      S.closedReason = String(msg.reason ?? 'closed');
      S.status = 'closed';
      break;
    default:
      break;
  }
  notify();
}

/** Messages that change nothing the game echoes back carry no seq, so they never hold back a state update. */
function sendQuiet(obj: Record<string, unknown>): void {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

// ---------------------------------------------------------------- audio meter (levels only, ~20 Hz)

export const METER = { level: 0, bass: 0, mid: 0, high: 0, beat: 0, system: '' };
const meterListeners = new Set<() => void>();
let meterUsers = 0;
let meterSource = 'AUTO';

/** Subscribes while mounted; the game streams levels only while at least one panel shows them. */
export function useMeter(source = 'AUTO'): typeof METER {
  const [, setTick] = useState(0);
  useEffect(() => {
    const l = () => setTick((t) => t + 1);
    meterListeners.add(l);
    meterUsers++;
    if (meterUsers === 1 || source !== meterSource) {
      meterSource = source;
      sendQuiet({ type: 'meter', on: true, source });
    }
    return () => {
      meterListeners.delete(l);
      if (--meterUsers === 0) sendQuiet({ type: 'meter', on: false });
    };
  }, [source]);
  return METER;
}

/** Re-sends the subscription after a reconnect. */
function resubscribeMeter(): void {
  if (meterUsers > 0) sendQuiet({ type: 'meter', on: true, source: meterSource });
}

function send(obj: Record<string, unknown>): boolean {
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  obj.seq = ++seq;
  lastSent = seq;
  ws.send(JSON.stringify(obj));
  return true;
}

// ---------------------------------------------------------------- edits

let gestureN = 0;

/** A new undo step. Edits that share a gesture id (one drag, one colour pick) collapse into one step in game. */
export function newGesture(): string {
  return `g${++gestureN}-${Date.now().toString(36)}`;
}

let pending: { gesture: string; spec: Spec } | null = null;
let raf = 0;

/** Applies {@code fn} to a copy of the spec, shows it at once, and sends it (at most once per frame). */
export function edit(fn: (s: Spec) => void, gesture: string = newGesture()): void {
  if (!S.spec || S.status !== 'open') return;
  const prev = S.spec;
  const next = structuredClone(S.spec);
  fn(next);
  keyChanges(prev, next);
  fixTimeline(next);
  S.spec = next;
  notify();
  if (pending && pending.gesture !== gesture) flush();
  pending = { gesture, spec: next };
  if (!raf) raf = requestAnimationFrame(flush);
}

function flush(): void {
  if (raf) {
    cancelAnimationFrame(raf);
    raf = 0;
  }
  if (!pending) return;
  const p = pending;
  pending = null;
  // The SNBT preview is read-only; only the opaque key travels back.
  const spec = {
    ...p.spec,
    layers: p.spec.layers.map((l) => (l.data ? { ...l, data: { key: l.data.key, bytes: l.data.bytes } } : l)),
  };
  send({ type: 'edit', gesture: p.gesture, spec });
}

export function select(kind: SelectionKind, layer = -1, group: string | null = null): void {
  flush();
  S.selection = {
    ...S.selection,
    kind,
    layer: kind === 'layer' ? layer : S.selection.layer,
    group: kind === 'group' ? group : S.selection.group,
  };
  notify();
  send({ type: 'select', kind, layer, group });
}

export function solo(layer: number, group: string | null): void {
  flush();
  S.selection = { ...S.selection, soloLayer: group ? -1 : layer, soloGroup: group };
  notify();
  send({ type: 'solo', layer, group });
}

export function undo(): void {
  flush();
  send({ type: 'undo' });
}

export function redo(): void {
  flush();
  send({ type: 'redo' });
}

export function save(): void {
  flush();
  send({ type: 'save' });
}

export function cancel(): void {
  pending = null;
  send({ type: 'cancel' });
}

// ---------------------------------------------------------------- playhead (preview only, never saved)

export type PlayheadMode = 'live' | 'play' | 'pause';

export const PH = {
  /** live = the game runs the timeline on its own clock; play/pause = this page drives the preview. */
  mode: 'live' as PlayheadMode,
  time: 0,
  playFrom: 0,
  playStarted: 0,
};

const phListeners = new Set<() => void>();
let phRaf = 0;
let phSendQueued = false;

export function usePlayhead(): typeof PH {
  const [, setTick] = useState(0);
  useEffect(() => {
    const l = () => setTick((t) => t + 1);
    phListeners.add(l);
    return () => {
      phListeners.delete(l);
    };
  }, []);
  return PH;
}

function phNotify(): void {
  phListeners.forEach((l) => l());
}

function phSend(): void {
  if (phSendQueued) return;
  phSendQueued = true;
  requestAnimationFrame(() => {
    phSendQueued = false;
    const time = PH.mode === 'play' ? PH.playFrom : PH.time;
    send({ type: 'playhead', mode: PH.mode, time: Math.max(0, time) });
  });
}

function tick(): void {
  phRaf = 0;
  if (PH.mode !== 'play') return;
  const tl = S.spec?.timeline;
  const len = tl?.length ?? 4;
  PH.time = localTime(tl?.mode ?? 'LOOP', len, PH.playFrom + (performance.now() - PH.playStarted) / 1000);
  phNotify();
  phRaf = requestAnimationFrame(tick);
}

/** Paused at {@code time}: the game holds that frame (a scrub sends a stream of these, one per frame). */
export function scrubTo(time: number): void {
  const len = S.spec?.timeline?.length ?? 4;
  PH.mode = 'pause';
  PH.time = Math.max(0, Math.min(len, time));
  phNotify();
  phSend();
}

export function play(): void {
  const len = S.spec?.timeline?.length ?? 4;
  const tl = S.spec?.timeline;
  PH.playFrom = tl?.mode === 'ONCE' && PH.time >= len - 1e-3 ? 0 : PH.time;
  PH.playStarted = performance.now();
  PH.mode = 'play';
  phSend();
  if (!phRaf) phRaf = requestAnimationFrame(tick);
  phNotify();
}

export function pause(): void {
  if (PH.mode === 'play') scrubTo(PH.time);
}

export function togglePlay(): void {
  if (PH.mode === 'play') pause();
  else play();
}

/** Hands the preview back to the game's own clock (triggers, game time). */
export function goLive(): void {
  PH.mode = 'live';
  phNotify();
  phSend();
}

// ---------------------------------------------------------------- keying from the inspector

const ANIMATABLE: Prop[] = ['OFFSET_X', 'OFFSET_Y', 'OFFSET_Z', 'ROT_X', 'ROT_Y', 'ROT_Z', 'SCALE'];

function targets(spec: Spec): { kind: TargetKind; id: string; obj: any }[] {
  const out: { kind: TargetKind; id: string; obj: any }[] = [{ kind: 'ROOT', id: '', obj: spec.root }];
  spec.groups.forEach((g) => out.push({ kind: 'GROUP', id: g.id, obj: g }));
  spec.layers.forEach((l) => l.uid && out.push({ kind: 'LAYER', id: l.uid, obj: l }));
  return out;
}

/**
 * After an inspector change: a property that has a track gets a key at the playhead (After Effects' rule once the
 * stopwatch is on); with auto-key on, a property without one gets a new track. Changes made by the timeline
 * itself touch keys, not properties, so they never get here.
 */
function keyChanges(prev: Spec, next: Spec): void {
  const before = new Map(targets(prev).map((t) => [`${t.kind}:${t.id}`, t.obj]));
  const time = roundTime(PH.time);
  for (const t of targets(next)) {
    const old = before.get(`${t.kind}:${t.id}`);
    if (!old) continue;
    const props: { prop: Prop; effect: number }[] = ANIMATABLE.map((p) => ({ prop: p, effect: 0 }));
    if (t.kind !== 'ROOT') (t.obj.effects ?? []).forEach((_: unknown, i: number) => props.push({ prop: 'EFFECT', effect: i }));
    for (const { prop, effect } of props) {
      const a = readProp(old, prop, effect);
      const b = readProp(t.obj, prop, effect);
      if (a === undefined || b === undefined || a === b) continue;
      if (prop === 'EFFECT' && old.effects?.[effect]?.type !== t.obj.effects?.[effect]?.type) continue;
      const k = trackKey({ target: t.kind, id: t.id, prop, effect });
      let track: Track | undefined = next.timeline?.tracks.find((x) => trackKey(x) === k);
      if (!track) {
        if (!S.autoKey) continue;
        next.timeline = next.timeline ?? emptyTimeline();
        if (next.timeline.tracks.length >= S.timelineCaps.maxTracks) continue;
        track = { target: t.kind, id: t.id, prop, keys: [] };
        if (prop === 'EFFECT') track.effect = effect;
        next.timeline.tracks.push(track);
      }
      const at = track.keys.findIndex((x) => Math.abs(x.t - time) < 1e-4);
      if (at >= 0) track.keys[at] = { ...track.keys[at], v: b };
      else track.keys = normaliseKeys([...track.keys, { t: time, v: b, ease: 'LINEAR' }]);
    }
  }
}
