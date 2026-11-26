// Inspector controls: numbers you can scrub by dragging their label, type into, or step with the arrow keys.

import type { ComponentChildren } from 'preact';
import { useRef, useState } from 'preact/hooks';
import { AXIS_X, AXIS_Y, AXIS_Z } from './protocol';
import { newGesture } from './store';

export function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

function fmt(v: number, decimals: number): string {
  const s = v.toFixed(decimals);
  return decimals > 0 ? s.replace(/\.?0+$/, '') || '0' : s;
}

function snap(v: number, step: number, decimals: number): number {
  const snapped = Math.round(v / step) * step;
  return Number(snapped.toFixed(Math.max(decimals, 0)));
}

export interface NumProps {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  decimals?: number;
  unit?: string;
  title?: string;
  labelClass?: string;
  onChange: (value: number, gesture: string) => void;
}

/**
 * Drag the label left/right to scrub (Shift = fine, Ctrl = coarse), click the value to type, arrows to step.
 * One drag is one gesture, so the game records it as a single undo step however many frames it sends.
 */
export function NumField(p: NumProps) {
  const decimals = p.decimals ?? (p.step >= 1 ? 0 : p.step >= 0.1 ? 1 : 2);
  const [text, setText] = useState<string | null>(null);
  const drag = useRef<{ startX: number; start: number; gesture: string; moved: boolean } | null>(null);
  const arrow = useRef<{ gesture: string; at: number } | null>(null);

  const set = (v: number, gesture: string) => {
    const next = clamp(snap(v, p.step, decimals), p.min, p.max);
    if (next !== p.value) p.onChange(next, gesture);
  };

  const onPointerDown = (e: PointerEvent) => {
    if (e.button !== 0) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = { startX: e.clientX, start: p.value, gesture: newGesture(), moved: false };
    document.body.classList.add('scrubbing');
    e.preventDefault();
  };
  const onPointerMove = (e: PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.startX;
    if (Math.abs(dx) > 2) d.moved = true;
    if (!d.moved) return;
    const mod = e.shiftKey ? 0.1 : e.ctrlKey || e.metaKey ? 10 : 1;
    set(d.start + dx * p.step * mod, d.gesture);
  };
  const onPointerUp = () => {
    drag.current = null;
    document.body.classList.remove('scrubbing');
  };

  const commit = () => {
    if (text === null) return;
    const parsed = Number(text.replace(',', '.'));
    setText(null);
    if (Number.isFinite(parsed)) set(parsed, newGesture());
  };

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Enter') {
      commit();
      (e.currentTarget as HTMLInputElement).blur();
    } else if (e.key === 'Escape') {
      setText(null);
      (e.currentTarget as HTMLInputElement).blur();
    } else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
      e.preventDefault();
      const now = performance.now();
      // Held or quickly repeated arrows are one undo step.
      if (!arrow.current || now - arrow.current.at > 800) arrow.current = { gesture: newGesture(), at: now };
      arrow.current.at = now;
      const mul = e.shiftKey ? 10 : e.altKey ? 0.1 : 1;
      const base = text !== null && Number.isFinite(Number(text)) ? Number(text) : p.value;
      setText(null);
      set(base + (e.key === 'ArrowUp' ? 1 : -1) * p.step * mul, arrow.current.gesture);
    }
  };

  return (
    <div class="num" title={p.title}>
      <span
        class={`num-label ${p.labelClass ?? ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        {p.label}
      </span>
      <input
        class="num-input"
        value={text ?? fmt(p.value, decimals)}
        onFocus={(e) => {
          setText(fmt(p.value, decimals));
          (e.currentTarget as HTMLInputElement).select();
        }}
        onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
        onBlur={commit}
        onKeyDown={onKeyDown}
        spellcheck={false}
      />
      {p.unit && <span class="num-unit">{p.unit}</span>}
    </div>
  );
}

export function Vec3Field(p: {
  label: string;
  value: [number, number, number];
  min: number;
  max: number;
  step: number;
  decimals?: number;
  unit?: string;
  onChange: (v: [number, number, number], gesture: string) => void;
}) {
  const names = ['X', 'Y', 'Z'];
  return (
    <div class="row">
      <span class="row-label">{p.label}</span>
      <div class="vec3">
        {p.value.map((v, i) => (
          <NumField
            key={i}
            label={names[i]}
            labelClass={`axis-${names[i].toLowerCase()}`}
            value={v}
            min={p.min}
            max={p.max}
            step={p.step}
            decimals={p.decimals}
            unit={p.unit}
            onChange={(nv, g) => {
              const next = [...p.value] as [number, number, number];
              next[i] = nv;
              p.onChange(next, g);
            }}
          />
        ))}
      </div>
    </div>
  );
}

export function Row(p: { label: string; children: ComponentChildren; title?: string }) {
  return (
    <div class="row" title={p.title}>
      <span class="row-label">{p.label}</span>
      <div class="row-body">{p.children}</div>
    </div>
  );
}

export function NumRow(p: NumProps & { rowLabel: string }) {
  return (
    <Row label={p.rowLabel} title={p.title}>
      <NumField {...p} />
    </Row>
  );
}

export function Toggle(p: { value: boolean; onChange: (v: boolean) => void; label?: string }) {
  return (
    <button class={`toggle ${p.value ? 'on' : ''}`} onClick={() => p.onChange(!p.value)} type="button">
      <span class="toggle-knob" />
      {p.label && <span class="toggle-label">{p.label}</span>}
    </button>
  );
}

export function SelectField(p: {
  value: string;
  options: { value: string; label: string }[];
  onChange: (v: string) => void;
}) {
  return (
    <select class="select" value={p.value} onChange={(e) => p.onChange((e.currentTarget as HTMLSelectElement).value)}>
      {p.options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

const AXES = [
  { v: AXIS_X, l: 'X' },
  { v: AXIS_Y, l: 'Y' },
  { v: AXIS_Z, l: 'Z' },
];

export function AxisField(p: { value: number; onChange: (v: number) => void }) {
  return (
    <div class="seg">
      {AXES.map((a) => (
        <button
          key={a.v}
          type="button"
          class={`seg-btn axis-${a.l.toLowerCase()} ${p.value === a.v ? 'on' : ''}`}
          onClick={() => p.onChange(a.v)}
        >
          {a.l}
        </button>
      ))}
    </div>
  );
}

export function toHex(c: number): string {
  return '#' + (c & 0xffffff).toString(16).padStart(6, '0');
}

export function fromHex(h: string): number {
  const m = /^#?([0-9a-f]{6})$/i.exec(h.trim());
  return m ? parseInt(m[1], 16) : 0;
}

/** Native colour picker + hex text. One open picker is one gesture. */
export function ColorField(p: { value: number; onChange: (v: number, gesture: string) => void }) {
  const gesture = useRef(newGesture());
  const [text, setText] = useState<string | null>(null);
  return (
    <div class="color">
      <input
        type="color"
        value={toHex(p.value)}
        onFocus={() => (gesture.current = newGesture())}
        onPointerDown={() => (gesture.current = newGesture())}
        onInput={(e) => p.onChange(fromHex((e.currentTarget as HTMLInputElement).value), gesture.current)}
      />
      <input
        class="color-hex"
        value={text ?? toHex(p.value).toUpperCase()}
        onFocus={() => setText(toHex(p.value).toUpperCase())}
        onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
        onBlur={() => {
          if (text !== null && /^#?[0-9a-f]{6}$/i.test(text.trim())) p.onChange(fromHex(text), newGesture());
          setText(null);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') (e.currentTarget as HTMLInputElement).blur();
          if (e.key === 'Escape') {
            setText(null);
            (e.currentTarget as HTMLInputElement).blur();
          }
        }}
        spellcheck={false}
      />
    </div>
  );
}

export function Section(p: { title: string; children: ComponentChildren; right?: ComponentChildren; open?: boolean }) {
  const [open, setOpen] = useState(p.open ?? true);
  return (
    <section class={`section ${open ? 'open' : ''}`}>
      <header class="section-head" onClick={() => setOpen(!open)}>
        <span class="caret">{open ? '▾' : '▸'}</span>
        <span class="section-title">{p.title}</span>
        <span class="section-right" onClick={(e) => e.stopPropagation()}>
          {p.right}
        </span>
      </header>
      {open && <div class="section-body">{p.children}</div>}
    </section>
  );
}
