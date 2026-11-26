// Connection + state. The game is the source of truth: this keeps an optimistic copy of the spec while edits are
// in flight and takes the game's `state` back once it has acknowledged everything sent (state.ack >= last seq).

import { useEffect, useState } from 'preact/hooks';
import type { BlockDef, EffectDef, Registries, Selection, SelectionKind, Spec } from './protocol';
import { PROTOCOL } from './protocol';

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
  const next = structuredClone(S.spec);
  fn(next);
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
