// The Timeline dock: tracks grouped by what they animate, a zoomable ruler with a playhead that scrubs the game's
// preview, keyframe diamonds (select, drag with snapping, copy/paste, delete), a key inspector and a graph editor
// with draggable bezier handles. Every change is an ordinary spec edit, so undo, validation and saving are the
// game's as for any other edit.

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { Ease, Key, PlayMode, Prop, Spec, TargetKind, Timeline as TL, Track } from './protocol';
import {
  FRAME,
  PROP_LABEL,
  PROP_UNIT,
  baseValue,
  emptyTimeline,
  handlesOf,
  hex,
  keyBefore,
  normaliseKeys,
  roundTime,
  sample,
  trackKey,
} from './anim';
import { layerName } from './outliner';
import { PH, REG, S, edit, goLive, newGesture, notify, scrubTo, toast, togglePlay, useApp, usePlayhead } from './store';
import { ColorField, NumField, SelectField, Toggle } from './ui';
import { PARAMS, linksOf } from './audio';

// ---------------------------------------------------------------- module state (survives re-renders)

/** Selected keys as "trackKey|time". */
let selected = new Set<string>();
let focusTrack: string | null = null;
let clipboard: { tk: string; dt: number; key: Key }[] = [];

const selId = (tk: string, t: number) => `${tk}|${roundTime(t)}`;
const ROW = 20;
const PROPS: Prop[] = ['OFFSET_X', 'OFFSET_Y', 'OFFSET_Z', 'ROT_X', 'ROT_Y', 'ROT_Z', 'SCALE', 'VISIBLE', 'TINT'];
const EASES: { value: Ease; label: string }[] = [
  { value: 'LINEAR', label: 'Linear' },
  { value: 'HOLD', label: 'Hold' },
  { value: 'EASE_IN', label: 'Ease in' },
  { value: 'EASE_OUT', label: 'Ease out' },
  { value: 'EASE_IN_OUT', label: 'Ease in-out' },
  { value: 'CUBIC', label: 'Bezier' },
];
const MODES: { value: PlayMode; label: string }[] = [
  { value: 'LOOP', label: 'Loop' },
  { value: 'PINGPONG', label: 'Ping-pong' },
  { value: 'ONCE', label: 'Once (hold end)' },
];

function decimalsOf(p: Prop): number {
  return p === 'ROT_X' || p === 'ROT_Y' || p === 'ROT_Z' ? 1 : 2;
}

function stepOf(p: Prop): number {
  return p === 'ROT_X' || p === 'ROT_Y' || p === 'ROT_Z' ? 1 : 0.05;
}

function fmtValue(tr: Track, v: number): string {
  if (Number.isNaN(v)) return '–';
  if (tr.prop === 'TINT') return hex(v).toUpperCase();
  if (tr.prop === 'VISIBLE') return v >= 0.5 ? 'on' : 'off';
  return v.toFixed(decimalsOf(tr.prop)).replace(/\.?0+$/, '') + (PROP_UNIT[tr.prop] ?? '');
}

function trackLabel(spec: Spec, tr: { target: TargetKind; id: string; prop: Prop; effect?: number }): string {
  if (tr.prop !== 'EFFECT') return PROP_LABEL[tr.prop];
  const fx =
    tr.target === 'GROUP'
      ? spec.groups.find((g) => g.id === tr.id)?.effects
      : spec.layers.find((l) => l.uid === tr.id)?.effects;
  const e = fx?.[tr.effect ?? 0];
  return `${REG.effects.get(e?.type ?? '')?.label ?? e?.type ?? 'Effect'} #${(tr.effect ?? 0) + 1}`;
}

function totalKeys(tl: TL | null | undefined): number {
  return tl ? tl.tracks.reduce((n, t) => n + t.keys.length, 0) : 0;
}

function findTrack(spec: Spec, tk: string): Track | undefined {
  return spec.timeline?.tracks.find((t) => trackKey(t) === tk);
}

function editTimeline(fn: (tl: TL, s: Spec) => void, gesture?: string): void {
  edit((s) => {
    s.timeline = s.timeline ?? emptyTimeline();
    fn(s.timeline, s);
  }, gesture);
}

// ---------------------------------------------------------------- actions (also used by keyboard shortcuts)

export function hasKeySelection(): boolean {
  return selected.size > 0;
}

function selectedKeys(spec: Spec): { tk: string; track: Track; key: Key }[] {
  const out: { tk: string; track: Track; key: Key }[] = [];
  for (const tr of spec.timeline?.tracks ?? []) {
    const tk = trackKey(tr);
    for (const k of tr.keys) if (selected.has(selId(tk, k.t))) out.push({ tk, track: tr, key: k });
  }
  return out;
}

export function deleteSelectedKeys(): void {
  if (!selected.size) return;
  const sel = new Set(selected);
  editTimeline((tl) => {
    for (const tr of tl.tracks) {
      const tk = trackKey(tr);
      tr.keys = tr.keys.filter((k) => !sel.has(selId(tk, k.t)));
    }
  });
  selected = new Set();
}

export function copyKeys(): void {
  const spec = S.spec;
  if (!spec) return;
  const keys = selectedKeys(spec);
  if (!keys.length) return;
  const t0 = Math.min(...keys.map((k) => k.key.t));
  clipboard = keys.map((k) => ({ tk: k.tk, dt: k.key.t - t0, key: structuredClone(k.key) }));
  toast(`Copied ${keys.length} key${keys.length === 1 ? '' : 's'}`);
}

export function pasteKeys(): void {
  const spec = S.spec;
  if (!spec || !clipboard.length) return;
  if (totalKeys(spec.timeline) + clipboard.length > S.timelineCaps.maxKeys) {
    toast(`This server allows ${S.timelineCaps.maxKeys} keyframes per hologram`, 'error');
    return;
  }
  // One copied track and another one focused: paste onto the focused track (same kind of value only).
  const single = new Set(clipboard.map((c) => c.tk)).size === 1;
  const ft = focusTrack ? findTrack(spec, focusTrack) : undefined;
  const src = findTrack(spec, clipboard[0].tk);
  const retarget = single && ft && (!src || (src.prop === 'TINT') === (ft.prop === 'TINT')) ? focusTrack : null;
  const at = PH.time;
  const next = new Set<string>();
  editTimeline((tl) => {
    for (const c of clipboard) {
      const tk = retarget ?? c.tk;
      const tr = tl.tracks.find((x) => trackKey(x) === tk);
      if (!tr) continue;
      const t = roundTime(Math.min(tl.length, at + c.dt));
      tr.keys = normaliseKeys([...tr.keys, { ...structuredClone(c.key), t }]);
      next.add(selId(tk, t));
    }
  });
  selected = next;
}

/** K / the ◆ button: a key at the playhead on the focused track, or removes the one already there. */
export function toggleKeyAtPlayhead(tk: string | null = focusTrack): void {
  const spec = S.spec;
  if (!spec || !tk) return;
  const tr = findTrack(spec, tk);
  if (!tr) return;
  const t = roundTime(PH.time);
  const at = tr.keys.findIndex((k) => Math.abs(k.t - t) < 1e-4);
  if (at < 0 && totalKeys(spec.timeline) >= S.timelineCaps.maxKeys) {
    toast(`This server allows ${S.timelineCaps.maxKeys} keyframes per hologram`, 'error');
    return;
  }
  editTimeline((tl) => {
    const x = tl.tracks.find((y) => trackKey(y) === tk);
    if (!x) return;
    if (at >= 0) x.keys.splice(at, 1);
    else {
      const v = sample(x, t);
      x.keys = normaliseKeys([...x.keys, { t, v: Number.isNaN(v) ? baseValue(spec, x) : v, ease: 'LINEAR' }]);
    }
  });
  if (at < 0) selected = new Set([selId(tk, t)]);
}

export function nudgeKeys(frames: number): void {
  if (!selected.size) return;
  const spec = S.spec!;
  const len = spec.timeline?.length ?? 4;
  const keys = selectedKeys(spec);
  let d = frames * FRAME;
  const lo = Math.min(...keys.map((k) => k.key.t));
  const hi = Math.max(...keys.map((k) => k.key.t));
  d = Math.max(-lo, Math.min(len - hi, d));
  if (Math.abs(d) < 1e-6) return;
  moveKeys(spec, keys, d, newGesture());
}

function moveKeys(base: Spec, keys: { tk: string; key: Key }[], d: number, gesture: string): void {
  const byTrack = new Map<string, Key[]>();
  keys.forEach((k) => byTrack.set(k.tk, [...(byTrack.get(k.tk) ?? []), k.key]));
  const next = new Set<string>();
  editTimeline((tl) => {
    for (const tr of tl.tracks) {
      const tk = trackKey(tr);
      const moving = byTrack.get(tk);
      if (!moving) continue;
      const startTr = findTrack(base, tk);
      if (!startTr) continue;
      const movingTimes = new Set(moving.map((k) => roundTime(k.t)));
      const rest = startTr.keys.filter((k) => !movingTimes.has(roundTime(k.t)));
      const moved = moving.map((k) => ({ ...k, t: roundTime(Math.max(0, Math.min(tl.length, k.t + d))) }));
      moved.forEach((k) => next.add(selId(tk, k.t)));
      tr.keys = normaliseKeys([...rest, ...moved]);
    }
  }, gesture);
  selected = next;
}

export function clearKeySelection(): void {
  selected = new Set();
}

// ---------------------------------------------------------------- rows

interface Section {
  target: TargetKind;
  id: string;
  name: string;
  effects: { type: string }[];
  tracks: Track[];
}

function sections(spec: Spec): Section[] {
  const tl = spec.timeline;
  const tracks = tl?.tracks ?? [];
  const out: Section[] = [];
  const sel = S.selection;
  const add = (target: TargetKind, id: string, name: string, effects: { type: string }[], forced: boolean) => {
    const own = tracks.filter((t) => t.target === target && t.id === id);
    if (own.length || forced || linksOf(spec, target, id).length) out.push({ target, id, name, effects, tracks: own });
  };
  add('ROOT', '', 'Hologram', [], sel.kind === 'root');
  spec.groups.forEach((g) => add('GROUP', g.id, `▣ ${g.name}`, g.effects, sel.kind === 'group' && sel.group === g.id));
  spec.layers.forEach((l, i) =>
    add('LAYER', l.uid ?? '', layerName(l), l.effects, sel.kind === 'layer' && sel.layer === i && !!l.uid),
  );
  return out;
}

function addTrack(sec: Section, value: string): void {
  const spec = S.spec!;
  if ((spec.timeline?.tracks.length ?? 0) >= S.timelineCaps.maxTracks) {
    toast(`This server allows ${S.timelineCaps.maxTracks} tracks per hologram`, 'error');
    return;
  }
  const [prop, fx] = value.split('#') as [Prop, string | undefined];
  const tr: Track = { target: sec.target, id: sec.id, prop, keys: [] };
  if (prop === 'EFFECT') tr.effect = Number(fx);
  const t = roundTime(PH.time);
  tr.keys = [{ t, v: baseValue(spec, tr), ease: 'LINEAR' }];
  const tk = trackKey(tr);
  editTimeline((tl) => tl.tracks.push(tr));
  focusTrack = tk;
  selected = new Set([selId(tk, t)]);
}

function AddTrack({ sec }: { sec: Section }) {
  const used = new Set(sec.tracks.map((t) => trackKey(t)));
  const options: { value: string; label: string }[] = [];
  for (const p of PROPS) {
    if (!used.has(trackKey({ target: sec.target, id: sec.id, prop: p }))) options.push({ value: p, label: PROP_LABEL[p] });
  }
  if (sec.target !== 'ROOT')
    sec.effects.forEach((e, i) => {
      if (!used.has(trackKey({ target: sec.target, id: sec.id, prop: 'EFFECT', effect: i })))
        options.push({ value: `EFFECT#${i}`, label: `${REG.effects.get(e.type)?.label ?? e.type} #${i + 1} strength` });
    });
  if (!options.length) return null;
  return (
    <select
      class="select tl-add"
      value=""
      title="Animate a property of this"
      onChange={(e) => {
        const v = (e.currentTarget as HTMLSelectElement).value;
        (e.currentTarget as HTMLSelectElement).value = '';
        if (v) addTrack(sec, v);
      }}
    >
      <option value="">+ Track</option>
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

// ---------------------------------------------------------------- ruler maths

interface View {
  start: number;
  pps: number;
}

function tickStep(pps: number): number {
  for (const s of [FRAME, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60]) if (s * pps >= 56) return s;
  return 120;
}

function snapTime(t: number, len: number, pps: number, exclude: Set<string>, spec: Spec): number {
  const cands: number[] = [0, len, PH.time];
  for (const tr of spec.timeline?.tracks ?? []) {
    const tk = trackKey(tr);
    for (const k of tr.keys) if (!exclude.has(selId(tk, k.t))) cands.push(k.t);
  }
  let best = Math.round(t / FRAME) * FRAME;
  let bestPx = 7;
  for (const c of cands) {
    const px = Math.abs(c - t) * pps;
    if (px < bestPx) {
      bestPx = px;
      best = c;
    }
  }
  return best;
}

// ---------------------------------------------------------------- component

export function Timeline() {
  useApp();
  const ph = usePlayhead();
  const [open, setOpen] = useState(true);
  const [graph, setGraph] = useState(false);
  const [view, setView] = useState<View>({ start: -0.1, pps: 120 });
  const [width, setWidth] = useState(600);
  const [box, setBox] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null);
  const lanesRef = useRef<HTMLDivElement>(null);
  const fitted = useRef(false);
  const drag = useRef<any>(null);
  const [, force] = useState(0);
  const rerender = () => force((n) => n + 1);

  const spec = S.spec!;
  const tl = spec.timeline;
  const len = tl?.length ?? 4;

  useLayoutEffect(() => {
    const el = lanesRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setWidth(el.clientWidth || 600));
    ro.observe(el);
    setWidth(el.clientWidth || 600);
    return () => ro.disconnect();
  }, [open, graph]);

  useEffect(() => {
    if (fitted.current || width < 100) return;
    fitted.current = true;
    setView({ start: -0.05 * len, pps: (width - 24) / (len * 1.1) });
  }, [width]);

  const fit = () => setView({ start: -0.05 * len, pps: Math.max(4, (width - 24) / (len * 1.1)) });
  const tx = (t: number) => (t - view.start) * view.pps;
  const xt = (x: number) => x / view.pps + view.start;

  const secs = sections(spec);
  const rows: ({ kind: 'sec'; sec: Section } | { kind: 'track'; sec: Section; track: Track })[] = [];
  secs.forEach((sec) => {
    rows.push({ kind: 'sec', sec });
    sec.tracks.forEach((track) => rows.push({ kind: 'track', sec, track }));
  });

  // Drop selected ids whose key is gone (undo, the game refusing an edit).
  const live = new Set<string>();
  tl?.tracks.forEach((tr) => tr.keys.forEach((k) => live.add(selId(trackKey(tr), k.t))));
  for (const s of selected) if (!live.has(s)) selected.delete(s);

  const graphTrack =
    (focusTrack && findTrack(spec, focusTrack)) ||
    (selected.size ? findTrack(spec, [...selected][0].split('|')[0]) : undefined) ||
    tl?.tracks[0];

  // -------------------------------------------------------------- pointer handling

  const onWheel = (e: WheelEvent) => {
    e.preventDefault();
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const x = e.clientX - rect.left;
    if (e.shiftKey || Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
      const d = (e.shiftKey ? e.deltaY : e.deltaX) / view.pps;
      setView({ ...view, start: view.start + d });
      return;
    }
    const factor = Math.exp(-e.deltaY * 0.0015);
    const pps = Math.max(4, Math.min(4000, view.pps * factor));
    const at = xt(Math.max(0, x));
    setView({ pps, start: at - Math.max(0, x) / pps });
  };

  const rulerDown = (e: PointerEvent) => {
    if (e.button === 1) return panDown(e);
    if (e.button !== 0) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const scrub = (cx: number, alt: boolean) => {
      const t = xt(cx - rect.left);
      scrubTo(alt ? t : Math.round(t / FRAME) * FRAME);
    };
    scrub(e.clientX, e.altKey);
    drag.current = { type: 'scrub', scrub };
  };

  const panDown = (e: PointerEvent) => {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = { type: 'pan', x: e.clientX, start: view.start };
    e.preventDefault();
  };

  const keyDown = (e: PointerEvent, tk: string, k: Key) => {
    if (e.button === 1) return panDown(e);
    if (e.button !== 0) return;
    e.stopPropagation();
    focusTrack = tk;
    const id = selId(tk, k.t);
    if (e.shiftKey || e.ctrlKey || e.metaKey) {
      if (selected.has(id)) selected.delete(id);
      else selected.add(id);
    } else if (!selected.has(id)) selected = new Set([id]);
    (lanesRef.current as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = {
      type: 'keys',
      x: e.clientX,
      base: structuredClone(spec),
      keys: selectedKeys(spec).map((s) => ({ tk: s.tk, key: structuredClone(s.key) })),
      primary: k.t,
      gesture: newGesture(),
      moved: false,
    };
    rerender();
  };

  const lanesDown = (e: PointerEvent) => {
    if (e.button === 1) return panDown(e);
    if (e.button !== 0) return;
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    if (!e.shiftKey) selected = new Set();
    const p = { x: e.clientX - rect.left, y: e.clientY - rect.top + (e.currentTarget as HTMLElement).scrollTop };
    drag.current = { type: 'box', rect, x0: p.x, y0: p.y, base: new Set(selected) };
    setBox({ x0: p.x, y0: p.y, x1: p.x, y1: p.y });
  };

  const onMove = (e: PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    if (d.type === 'scrub') d.scrub(e.clientX, e.altKey);
    else if (d.type === 'pan') setView({ ...view, start: d.start - (e.clientX - d.x) / view.pps });
    else if (d.type === 'keys') {
      if (!d.moved && Math.abs(e.clientX - d.x) < 3) return;
      d.moved = true;
      const raw = d.primary + (e.clientX - d.x) / view.pps;
      const exclude = new Set<string>(d.keys.map((k: any) => selId(k.tk, k.key.t)));
      const target = e.altKey ? raw : snapTime(raw, len, view.pps, exclude, d.base);
      let delta = target - d.primary;
      const lo = Math.min(...d.keys.map((k: any) => k.key.t));
      const hi = Math.max(...d.keys.map((k: any) => k.key.t));
      delta = Math.max(-lo, Math.min(len - hi, delta));
      moveKeys(d.base, d.keys, delta, d.gesture);
    } else if (d.type === 'box') {
      const el = lanesRef.current!;
      const x1 = e.clientX - d.rect.left;
      const y1 = e.clientY - d.rect.top + el.scrollTop;
      setBox({ x0: d.x0, y0: d.y0, x1, y1 });
      const [ax, bx] = [Math.min(d.x0, x1), Math.max(d.x0, x1)];
      const [ay, by] = [Math.min(d.y0, y1), Math.max(d.y0, y1)];
      const next = new Set<string>(d.base);
      rows.forEach((r, ri) => {
        if (r.kind !== 'track') return;
        const cy = ri * ROW + ROW / 2;
        if (cy < ay || cy > by) return;
        const tk = trackKey(r.track);
        r.track.keys.forEach((k) => {
          const x = tx(k.t);
          if (x >= ax && x <= bx) next.add(selId(tk, k.t));
        });
      });
      selected = next;
    }
  };

  const onUp = () => {
    drag.current = null;
    setBox(null);
  };

  const laneDouble = (e: MouseEvent, tr: Track) => {
    const rect = lanesRef.current!.getBoundingClientRect();
    const t = roundTime(
      Math.max(0, Math.min(len, e.altKey ? xt(e.clientX - rect.left) : Math.round(xt(e.clientX - rect.left) / FRAME) * FRAME)),
    );
    if (totalKeys(tl) >= S.timelineCaps.maxKeys) {
      toast(`This server allows ${S.timelineCaps.maxKeys} keyframes per hologram`, 'error');
      return;
    }
    const tk = trackKey(tr);
    editTimeline((x) => {
      const y = x.tracks.find((z) => trackKey(z) === tk);
      if (!y) return;
      const v = sample(y, t);
      y.keys = normaliseKeys([...y.keys, { t, v: Number.isNaN(v) ? baseValue(spec, y) : v, ease: 'LINEAR' }]);
    });
    focusTrack = tk;
    selected = new Set([selId(tk, t)]);
  };

  // -------------------------------------------------------------- render

  const step = tickStep(view.pps);
  const ticks: number[] = [];
  const t0 = Math.ceil(view.start / step) * step;
  for (let t = t0; tx(t) <= width && ticks.length < 400; t += step) ticks.push(Math.round(t * 1000) / 1000);
  const phX = tx(ph.time);

  return (
    <div class={`timeline ${open ? 'open' : ''}`}>
      <div class="panel-head tl-head">
        <span class="tl-title" onClick={() => setOpen(!open)} role="button" tabIndex={0}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), setOpen(!open))}>
          <span class="caret">{open ? '▾' : '▸'}</span> Timeline
        </span>
        <div class="tl-transport" onClick={(e) => e.stopPropagation()}>
          <button class="icon-btn" title="Go to start (Home)" onClick={() => scrubTo(0)}>
            ⏮
          </button>
          <button class={`icon-btn ${ph.mode === 'play' ? 'on' : ''}`} title="Play / pause (Space)" onClick={togglePlay}>
            {ph.mode === 'play' ? '⏸' : '▶'}
          </button>
          <span class="tl-time mono" title="Playhead">
            {ph.time.toFixed(2)}
            <span class="muted"> / {len.toFixed(2)} s</span>
          </span>
          <button
            class={`btn small ${ph.mode === 'live' ? 'on' : ''}`}
            title="Let the game run the timeline on its own clock (game time, start trigger)"
            onClick={goLive}
          >
            {ph.mode === 'live' ? '● Live' : 'Live'}
          </button>
          <span class="sep" />
          <NumField
            label="Length"
            value={len}
            min={S.registries!.timeline?.length[0] ?? 0.5}
            max={S.registries!.timeline?.length[1] ?? 120}
            step={0.05}
            decimals={2}
            unit="s"
            onChange={(v, g) => editTimeline((x) => (x.length = v), g)}
          />
          <SelectField
            value={tl?.mode ?? 'LOOP'}
            options={MODES}
            onChange={(v) => editTimeline((x) => (x.mode = v as PlayMode))}
          />
          <SelectField
            value={tl?.start ?? 'ALWAYS'}
            options={S.registries!.triggers.map((t) => ({
              value: t.name,
              label: t.name === 'ALWAYS' ? 'Start: always' : `Start: ${t.label.toLowerCase()}`,
            }))}
            onChange={(v) => editTimeline((x) => (x.start = v))}
          />
          <span class="sep" />
          <span title="A changed property without a track gets one, keyed at the playhead">
            <Toggle value={S.autoKey} onChange={(v) => ((S.autoKey = v), rerender())} label="Auto-key" />
          </span>
          <div class="seg">
            <button class={`seg-btn ${!graph ? 'on' : ''}`} onClick={() => setGraph(false)} title="Keyframes">
              Keys
            </button>
            <button class={`seg-btn ${graph ? 'on' : ''}`} onClick={() => setGraph(true)} title="Graph editor">
              Graph
            </button>
          </div>
          <button class="btn small" onClick={fit} title="Fit the clip">
            Fit
          </button>
          <span class="chip">
            {tl?.tracks.length ?? 0}/{S.timelineCaps.maxTracks} tracks · {totalKeys(tl)}/{S.timelineCaps.maxKeys} keys
          </span>
        </div>
      </div>
      {open && (
        <div class="tl-body">
          <div class="tl-main">
            <div class="tl-ruler-row">
              <div class="tl-corner muted small">
                {selected.size ? `${selected.size} key${selected.size === 1 ? '' : 's'} selected` : 'Wheel zoom · Shift+wheel pan'}
              </div>
              <div
                class="tl-ruler"
                onPointerDown={rulerDown}
                onPointerMove={onMove}
                onPointerUp={onUp}
                onPointerCancel={onUp}
                onWheel={onWheel}
              >
                <div class="tl-out" style={{ left: `${Math.max(0, tx(len))}px` }} />
                <div class="tl-out left" style={{ width: `${Math.max(0, tx(0))}px` }} />
                {ticks.map((t) => (
                  <div key={t} class="tl-tick" style={{ left: `${tx(t)}px` }}>
                    <span>{step < 1 ? t.toFixed(step < 0.1 ? 2 : 1) : t}</span>
                  </div>
                ))}
                <div class={`tl-playhead-cap ${ph.mode}`} style={{ left: `${phX}px` }} />
              </div>
            </div>
            {graph ? (
              <div class="tl-graph-wrap">
                <div class="tl-names">
                  {rows.map((r, i) => (
                    <NameRow key={i} r={r} spec={spec} rerender={rerender} />
                  ))}
                  {!rows.length && <EmptyHint />}
                </div>
                <div class="tl-lanes" ref={lanesRef} onWheel={onWheel}>
                  {graphTrack ? (
                    <Graph track={graphTrack} view={view} width={width} len={len} rerender={rerender} />
                  ) : (
                    <div class="tl-empty muted">Add a track to see its curve.</div>
                  )}
                  <div class={`tl-playhead ${ph.mode}`} style={{ left: `${phX}px` }} />
                </div>
              </div>
            ) : (
              <div class="tl-scroll">
                <div class="tl-names">
                  {rows.map((r, i) => (
                    <NameRow key={i} r={r} spec={spec} rerender={rerender} />
                  ))}
                  {!rows.length && <EmptyHint />}
                </div>
                <div
                  class="tl-lanes"
                  ref={lanesRef}
                  onPointerDown={lanesDown}
                  onPointerMove={onMove}
                  onPointerUp={onUp}
                  onPointerCancel={onUp}
                  onWheel={onWheel}
                  style={{ height: `${Math.max(rows.length * ROW, 60)}px` }}
                >
                  <div class="tl-out" style={{ left: `${Math.max(0, tx(len))}px` }} />
                  {ticks.map((t) => (
                    <div key={t} class="tl-grid" style={{ left: `${tx(t)}px` }} />
                  ))}
                  {rows.map((r, ri) =>
                    r.kind === 'sec' ? (
                      <div key={ri} class="tl-lane sec" style={{ top: `${ri * ROW}px` }} />
                    ) : (
                      <div
                        key={ri}
                        class={`tl-lane ${focusTrack === trackKey(r.track) ? 'focus' : ''}`}
                        style={{ top: `${ri * ROW}px` }}
                        onDblClick={(e) => laneDouble(e, r.track)}
                      >
                        <LaneSegments track={r.track} tx={tx} />
                        {r.track.keys.map((k) => {
                          const tk = trackKey(r.track);
                          const id = selId(tk, k.t);
                          return (
                            <button
                              key={id}
                              class={`tl-key ease-${k.ease.toLowerCase()} ${selected.has(id) ? 'sel' : ''}`}
                              style={{
                                left: `${tx(k.t)}px`,
                                ...(r.track.prop === 'TINT' ? { background: hex(k.v) } : {}),
                              }}
                              title={`${k.t.toFixed(2)} s · ${fmtValue(r.track, k.v)} · ${k.ease.toLowerCase().replace(/_/g, ' ')}`}
                              aria-label={`Key at ${k.t.toFixed(2)} seconds`}
                              onPointerDown={(e) => keyDown(e, tk, k)}
                              onDblClick={(e) => e.stopPropagation()}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') {
                                  e.preventDefault();
                                  e.stopPropagation();
                                  focusTrack = tk;
                                  if (e.shiftKey) selected.has(id) ? selected.delete(id) : selected.add(id);
                                  else selected = new Set([id]);
                                  scrubTo(k.t);
                                  rerender();
                                }
                              }}
                            />
                          );
                        })}
                      </div>
                    ),
                  )}
                  {box && (
                    <div
                      class="tl-box"
                      style={{
                        left: `${Math.min(box.x0, box.x1)}px`,
                        top: `${Math.min(box.y0, box.y1)}px`,
                        width: `${Math.abs(box.x1 - box.x0)}px`,
                        height: `${Math.abs(box.y1 - box.y0)}px`,
                      }}
                    />
                  )}
                  <div class={`tl-playhead ${ph.mode}`} style={{ left: `${phX}px` }} />
                </div>
              </div>
            )}
          </div>
          <KeyInspector spec={spec} rerender={rerender} />
        </div>
      )}
    </div>
  );
}

function EmptyHint() {
  return (
    <div class="tl-hint muted small">
      Select a layer, group or the hologram, then use <b>+ Track</b> to animate one of its properties. Keys go in at the
      playhead; drag the ruler to scrub the game.
    </div>
  );
}

function LaneSegments({ track, tx }: { track: Track; tx: (t: number) => number }) {
  // A bar between keys shows where the value changes; a hold shows as a dashed bar.
  const out = [];
  for (let i = 0; i + 1 < track.keys.length; i++) {
    const a = track.keys[i];
    const b = track.keys[i + 1];
    const changes = track.prop === 'VISIBLE' || a.ease === 'HOLD' ? false : a.v !== b.v;
    out.push(
      <div
        key={i}
        class={`tl-seg ${changes ? '' : 'flat'}`}
        style={{
          left: `${tx(a.t)}px`,
          width: `${Math.max(0, tx(b.t) - tx(a.t))}px`,
          ...(track.prop === 'TINT' ? { background: `linear-gradient(to right, ${hex(a.v)}, ${hex(b.v)})`, opacity: 0.55 } : {}),
        }}
      />,
    );
  }
  return <>{out}</>;
}

/** "♪ 2": this target's values react to music (edit them in the inspector's Audio section). */
function AudioBadge({ spec, target, id }: { spec: Spec; target: TargetKind; id: string }) {
  const links = linksOf(spec, target, id);
  const fx = target === 'ROOT' ? 0 : (targetEffects(spec, target, id) ?? []).reduce((n, e) => n + (e.audio?.length ?? 0), 0);
  const n = links.length + fx;
  if (!n) return null;
  const what = [...links.map((a) => PARAMS[a.param].label), ...(fx ? [`${fx} on effects`] : [])].join(', ');
  return (
    <span class="tl-audio" title={`Reacts to music: ${what}`}>
      ♪{n}
    </span>
  );
}

function targetEffects(spec: Spec, target: TargetKind, id: string) {
  if (target === 'GROUP') return spec.groups.find((g) => g.id === id)?.effects;
  if (target === 'LAYER') return spec.layers.find((l) => l.uid === id)?.effects;
  return undefined;
}

function NameRow({
  r,
  spec,
  rerender,
}: {
  r: { kind: 'sec'; sec: Section } | { kind: 'track'; sec: Section; track: Track };
  spec: Spec;
  rerender: () => void;
}) {
  const ph = PH;
  if (r.kind === 'sec')
    return (
      <div class={`tl-name sec tgt-${r.sec.target.toLowerCase()}`}>
        <span class="tl-sec-name">{r.sec.name}</span>
        <AudioBadge spec={spec} target={r.sec.target} id={r.sec.id} />
        <AddTrack sec={r.sec} />
      </div>
    );
  const tr = r.track;
  const tk = trackKey(tr);
  const v = sample(tr, ph.time);
  const t = roundTime(ph.time);
  const onKey = tr.keys.some((k) => Math.abs(k.t - t) < 1e-4);
  const i = keyBefore(tr.keys, t - 1e-4);
  const prev = i >= 0 ? tr.keys[i] : undefined;
  const next = tr.keys.find((k) => k.t > t + 1e-4);
  return (
    <div
      class={`tl-name ${focusTrack === tk ? 'focus' : ''}`}
      tabIndex={0}
      onClick={() => ((focusTrack = tk), rerender())}
      onFocus={() => ((focusTrack = tk), rerender())}
    >
      <span class="tl-prop">{trackLabel(spec, tr)}</span>
      <span class="tl-val mono">
        {tr.prop === 'TINT' && <span class="tl-swatch" style={{ background: hex(v) }} />}
        {fmtValue(tr, v)}
      </span>
      <span class="tl-nav">
        <button class="icon-btn" title="Previous key" disabled={!prev} onClick={(e) => (e.stopPropagation(), prev && scrubTo(prev.t))}>
          ‹
        </button>
        <button
          class={`icon-btn key-toggle ${onKey ? 'on' : ''}`}
          title={onKey ? 'Remove the key at the playhead (K)' : 'Add a key at the playhead (K)'}
          onClick={(e) => (e.stopPropagation(), toggleKeyAtPlayhead(tk))}
        >
          ◆
        </button>
        <button class="icon-btn" title="Next key" disabled={!next} onClick={(e) => (e.stopPropagation(), next && scrubTo(next.t))}>
          ›
        </button>
        <button
          class="icon-btn danger"
          title="Remove this track"
          onClick={(e) => {
            e.stopPropagation();
            editTimeline((x) => (x.tracks = x.tracks.filter((y) => trackKey(y) !== tk)));
          }}
        >
          ×
        </button>
      </span>
    </div>
  );
}

// ---------------------------------------------------------------- key inspector

function KeyInspector({ spec, rerender }: { spec: Spec; rerender: () => void }) {
  const keys = selectedKeys(spec);
  if (!keys.length)
    return (
      <div class="tl-keyinsp muted small">
        <div class="tl-ki-title">Keyframe</div>
        Click a ◆ to edit it. Shift-click or drag a box to select more; Alt while dragging turns snapping off.
        <div class="tl-shortcuts">
          <kbd>Space</kbd> play · <kbd>K</kbd> key · <kbd>Del</kbd> delete · <kbd>Ctrl+C</kbd>/<kbd>V</kbd> copy, paste
          at playhead · <kbd>←</kbd>/<kbd>→</kbd> nudge a frame · <kbd>,</kbd>/<kbd>.</kbd> step · <kbd>Home</kbd>
        </div>
      </div>
    );
  const one = keys.length === 1 ? keys[0] : null;
  const setKey = (fn: (k: Key) => void, gesture?: string) => {
    const ids = new Set(keys.map((k) => selId(k.tk, k.key.t)));
    editTimeline((tl) => {
      for (const tr of tl.tracks) {
        const tk = trackKey(tr);
        tr.keys.forEach((k) => ids.has(selId(tk, k.t)) && fn(k));
      }
    }, gesture);
  };
  const ease = one ? one.key.ease : keys.every((k) => k.key.ease === keys[0].key.ease) ? keys[0].key.ease : '';
  const h = one ? handlesOf(one.key) : null;
  return (
    <div class="tl-keyinsp">
      <div class="tl-ki-title">
        {one ? `${trackLabel(spec, one.track)} key` : `${keys.length} keys`}
        <button class="icon-btn danger" title="Delete (Del)" onClick={() => (deleteSelectedKeys(), rerender())}>
          ×
        </button>
      </div>
      {one && (
        <>
          <div class="row">
            <span class="row-label">Time</span>
            <NumField
              label="t"
              value={one.key.t}
              min={0}
              max={spec.timeline?.length ?? 4}
              step={FRAME}
              decimals={3}
              unit="s"
              onChange={(v, g) => moveKeys(spec, [{ tk: one.tk, key: one.key }], v - one.key.t, g)}
            />
          </div>
          <div class="row">
            <span class="row-label">Value</span>
            {one.track.prop === 'TINT' ? (
              <ColorField value={one.key.v} onChange={(v, g) => setKey((k) => (k.v = v), g)} />
            ) : one.track.prop === 'VISIBLE' ? (
              <Toggle value={one.key.v >= 0.5} onChange={(v) => setKey((k) => (k.v = v ? 1 : 0))} label={one.key.v >= 0.5 ? 'shown' : 'hidden'} />
            ) : (
              <NumField
                label="v"
                value={one.key.v}
                min={S.registries!.timeline?.props.find((p) => p.name === one.track.prop)?.min ?? -720}
                max={S.registries!.timeline?.props.find((p) => p.name === one.track.prop)?.max ?? 720}
                step={stepOf(one.track.prop)}
                decimals={decimalsOf(one.track.prop)}
                unit={PROP_UNIT[one.track.prop]}
                onChange={(v, g) => setKey((k) => (k.v = v), g)}
              />
            )}
          </div>
        </>
      )}
      {(!one || one.track.prop !== 'VISIBLE') && (
        <div class="row">
          <span class="row-label">Ease out</span>
          <SelectField
            value={ease}
            options={[...(ease ? [] : [{ value: '', label: 'mixed' }]), ...EASES]}
            onChange={(v) =>
              v &&
              setKey((k) => {
                if (v === 'CUBIC' && k.ease !== 'CUBIC') k.bezier = handlesOf(k);
                k.ease = v as Ease;
                if (v !== 'CUBIC') delete k.bezier;
              })
            }
          />
        </div>
      )}
      {one && h && one.key.ease === 'CUBIC' && (
        <div class="row">
          <span class="row-label">Handles</span>
          <div class="tl-bez">
            {(['x1', 'y1', 'x2', 'y2'] as const).map((n, i) => (
              <NumField
                key={n}
                label={n}
                value={h[i]}
                min={i % 2 === 0 ? 0 : -2}
                max={i % 2 === 0 ? 1 : 3}
                step={0.01}
                decimals={2}
                onChange={(v, g) =>
                  setKey((k) => {
                    const b = [...handlesOf(k)] as [number, number, number, number];
                    b[i] = v;
                    k.bezier = b;
                    k.ease = 'CUBIC';
                  }, g)
                }
              />
            ))}
          </div>
        </div>
      )}
      <div class="muted small tl-ki-note">The easing shapes the move from this key to the next one.</div>
    </div>
  );
}

// ---------------------------------------------------------------- graph editor

function Graph({
  track,
  view,
  width,
  len,
  rerender,
}: {
  track: Track;
  view: View;
  width: number;
  len: number;
  rerender: () => void;
}) {
  const H = 196;
  const svgRef = useRef<SVGSVGElement>(null);
  const drag = useRef<any>(null);
  const tk = trackKey(track);
  const tx = (t: number) => (t - view.start) * view.pps;
  const xt = (x: number) => x / view.pps + view.start;

  if (track.prop === 'TINT') {
    const stops = [];
    for (let x = 0; x <= width; x += 6) {
      const t = Math.max(0, Math.min(len, xt(x)));
      stops.push(`${hex(sample(track, t))} ${((x / Math.max(1, width)) * 100).toFixed(2)}%`);
    }
    return (
      <div class="tl-graph">
        <div class="tl-tint-strip" style={{ background: `linear-gradient(to right, ${stops.join(',')})` }} />
        <div class="tl-empty muted small">Tint blends in OKLab; edit colours in the key inspector.</div>
      </div>
    );
  }

  let lo = Infinity;
  let hi = -Infinity;
  for (const k of track.keys) {
    lo = Math.min(lo, k.v);
    hi = Math.max(hi, k.v);
  }
  // Bezier handles may overshoot: include them in the range.
  for (let i = 0; i + 1 < track.keys.length; i++) {
    const a = track.keys[i];
    const b = track.keys[i + 1];
    if (a.ease === 'HOLD' || a.ease === 'LINEAR') continue;
    const [, y1, , y2] = handlesOf(a);
    lo = Math.min(lo, a.v + y1 * (b.v - a.v), a.v + y2 * (b.v - a.v));
    hi = Math.max(hi, a.v + y1 * (b.v - a.v), a.v + y2 * (b.v - a.v));
  }
  if (!Number.isFinite(lo)) {
    lo = 0;
    hi = 1;
  }
  if (hi - lo < 1e-3) {
    lo -= 0.5;
    hi += 0.5;
  }
  const pad = (hi - lo) * 0.12;
  const vlo = drag.current?.vlo ?? lo - pad;
  const vhi = drag.current?.vhi ?? hi + pad;
  const vy = (v: number) => H - 8 - ((v - vlo) / (vhi - vlo)) * (H - 16);
  const yv = (y: number) => vlo + ((H - 8 - y) / (H - 16)) * (vhi - vlo);

  let d = '';
  for (let x = Math.max(0, tx(0)); x <= Math.min(width, tx(len)); x += 2) {
    const v = sample(track, xt(x));
    d += `${d ? 'L' : 'M'}${x.toFixed(1)},${vy(v).toFixed(1)}`;
  }

  const point = (e: PointerEvent) => {
    const r = svgRef.current!.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };

  const down = (e: PointerEvent, what: any) => {
    if (e.button !== 0) return;
    e.stopPropagation();
    svgRef.current!.setPointerCapture(e.pointerId);
    focusTrack = tk;
    if (what.kind === 'key') selected = new Set([selId(tk, track.keys[what.i].t)]);
    drag.current = { ...what, gesture: newGesture(), vlo, vhi, base: structuredClone(S.spec!) };
    rerender();
  };

  const move = (e: PointerEvent) => {
    const dr = drag.current;
    if (!dr) return;
    const p = point(e);
    if (dr.kind === 'key') {
      const base = findTrack(dr.base, tk)!;
      const k0 = base.keys[dr.i];
      const t = e.altKey ? xt(p.x) : Math.round(xt(p.x) / FRAME) * FRAME;
      const nt = roundTime(Math.max(0, Math.min(len, t)));
      let v = yv(p.y);
      if (track.prop === 'VISIBLE') v = v >= 0.5 ? 1 : 0;
      editTimeline((tl) => {
        const tr = tl.tracks.find((x) => trackKey(x) === tk);
        if (!tr) return;
        const rest = base.keys.filter((_, j) => j !== dr.i);
        tr.keys = normaliseKeys([...rest, { ...k0, t: nt, v }]);
      }, dr.gesture);
      selected = new Set([selId(tk, nt)]);
    } else if (dr.kind === 'handle') {
      const base = findTrack(dr.base, tk)!;
      const a = base.keys[dr.i];
      const b = base.keys[dr.i + 1];
      const span = Math.max(1e-6, b.t - a.t);
      const dv = Math.abs(b.v - a.v) < 1e-6 ? (vhi - vlo) * 0.5 : b.v - a.v;
      const hx = Math.max(0, Math.min(1, (xt(p.x) - a.t) / span));
      const hy = Math.max(-2, Math.min(3, (yv(p.y) - a.v) / dv));
      editTimeline((tl) => {
        const tr = tl.tracks.find((x) => trackKey(x) === tk);
        const k = tr?.keys[dr.i];
        if (!k) return;
        const h = [...handlesOf(a)] as [number, number, number, number];
        if (dr.which === 1) {
          h[0] = hx;
          h[1] = hy;
        } else {
          h[2] = hx;
          h[3] = hy;
        }
        k.ease = 'CUBIC';
        k.bezier = [h[0], h[1], h[2], h[3]].map((n) => Math.round(n * 1000) / 1000) as [number, number, number, number];
      }, dr.gesture);
    }
  };

  const up = () => {
    drag.current = null;
    rerender();
  };

  const grid = [];
  const gstep = niceStep((vhi - vlo) / 5);
  for (let v = Math.ceil(vlo / gstep) * gstep; v <= vhi; v += gstep)
    grid.push(
      <g key={v}>
        <line x1={0} x2={width} y1={vy(v)} y2={vy(v)} class="tl-gline" />
        <text x={4} y={vy(v) - 2} class="tl-gtext">
          {Math.abs(v) < 1e-9 ? 0 : +v.toFixed(3)}
        </text>
      </g>,
    );

  const handles = [];
  for (let i = 0; i + 1 < track.keys.length; i++) {
    const a = track.keys[i];
    const b = track.keys[i + 1];
    if (a.ease === 'HOLD' || track.prop === 'VISIBLE') continue;
    const [x1, y1, x2, y2] = handlesOf(a);
    const span = b.t - a.t;
    const dv = Math.abs(b.v - a.v) < 1e-6 ? (vhi - vlo) * 0.5 : b.v - a.v;
    const h1 = { x: tx(a.t + x1 * span), y: vy(a.v + y1 * dv) };
    const h2 = { x: tx(a.t + x2 * span), y: vy(a.v + y2 * dv) };
    const sel = selected.has(selId(tk, a.t)) || selected.has(selId(tk, b.t));
    handles.push(
      <g key={`h${i}`} class={`tl-handles ${sel ? 'sel' : ''} ${a.ease === 'CUBIC' ? 'cubic' : ''}`}>
        <line x1={tx(a.t)} y1={vy(a.v)} x2={h1.x} y2={h1.y} />
        <line x1={tx(b.t)} y1={vy(b.v)} x2={h2.x} y2={h2.y} />
        <circle cx={h1.x} cy={h1.y} r={4} onPointerDown={(e) => down(e as any, { kind: 'handle', i, which: 1 })}>
          <title>Drag to shape the ease out of this key (makes it Bezier)</title>
        </circle>
        <circle cx={h2.x} cy={h2.y} r={4} onPointerDown={(e) => down(e as any, { kind: 'handle', i, which: 2 })}>
          <title>Drag to shape the ease into the next key</title>
        </circle>
      </g>,
    );
  }

  return (
    <div class="tl-graph">
      <svg ref={svgRef} width={width} height={H} onPointerMove={move as any} onPointerUp={up} onPointerCancel={up}>
        {grid}
        <rect x={tx(len)} y={0} width={Math.max(0, width - tx(len))} height={H} class="tl-gout" />
        <path d={d} class="tl-curve" />
        {handles}
        {track.keys.map((k, i) => (
          <rect
            key={i}
            x={tx(k.t) - 5}
            y={vy(k.v) - 5}
            width={10}
            height={10}
            transform={`rotate(45 ${tx(k.t)} ${vy(k.v)})`}
            class={`tl-gkey ${selected.has(selId(tk, k.t)) ? 'sel' : ''}`}
            onPointerDown={(e) => down(e as any, { kind: 'key', i })}
          >
            <title>{`${k.t.toFixed(2)} s · ${fmtValue(track, k.v)} (drag; Alt = no snap)`}</title>
          </rect>
        ))}
      </svg>
      <div class="tl-graph-label muted small">{trackLabel(S.spec!, track)} · click a track name to graph it</div>
    </div>
  );
}

function niceStep(raw: number): number {
  const p = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1e-6))));
  const n = raw / p;
  return (n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10) * p;
}

// ---------------------------------------------------------------- keyboard (called from main before other keys)

/** Handles timeline shortcuts; returns true when the key was used. */
export function timelineKey(e: KeyboardEvent): boolean {
  const mod = e.ctrlKey || e.metaKey;
  const key = e.key.toLowerCase();
  const len = S.spec?.timeline?.length ?? 4;
  if (e.key === ' ' && !mod) {
    togglePlay();
    return true;
  }
  if (e.key === 'Home') {
    scrubTo(0);
    return true;
  }
  if (e.key === 'End') {
    scrubTo(len);
    return true;
  }
  if (e.key === ',' || e.key === '.') {
    scrubTo(Math.round(PH.time / FRAME) * FRAME + (e.key === ',' ? -FRAME : FRAME));
    return true;
  }
  if (key === 'k' && !mod) {
    toggleKeyAtPlayhead();
    return true;
  }
  if (!selected.size) {
    if (mod && key === 'v' && clipboard.length) {
      pasteKeys();
      return true;
    }
    return false;
  }
  if (e.key === 'Delete' || e.key === 'Backspace') {
    deleteSelectedKeys();
    return true;
  }
  if (e.key === 'Escape') {
    selected = new Set();
    notify();
    return true;
  }
  if (mod && key === 'c') {
    copyKeys();
    return true;
  }
  if (mod && key === 'v') {
    pasteKeys();
    return true;
  }
  if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
    nudgeKeys((e.key === 'ArrowLeft' ? -1 : 1) * (e.shiftKey ? 10 : 1));
    return true;
  }
  return false;
}
