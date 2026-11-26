// Middle panel: searchable blocks / entities / particles. Click replaces the selected layer's content,
// "+" (or double-click) adds a new layer.

import { useMemo, useState } from 'preact/hooks';
import type { Kind, Layer } from './protocol';
import { S, edit, select } from './store';
import { KIND_BADGE } from './outliner';

interface Item {
  id: string;
  name: string;
  extra?: string;
}

const SHOWN = 240;

function items(kind: Kind): Item[] {
  const reg = S.registries!;
  switch (kind) {
    case 'BLOCK':
      return reg.blocks.map((b) => ({ id: b.id, name: b.name, extra: b.blockEntity ? 'BE' : undefined }));
    case 'ENTITY':
      return reg.entities.map((e) => ({ id: e.id, name: e.name }));
    default:
      return reg.particles.map((p) => ({ id: p, name: p.replace(/^[^:]+:/, '').replace(/_/g, ' ') }));
  }
}

/** Plain words match name or id; "@mod" limits to one namespace. */
function matches(it: Item, words: string[], mod: string | null): boolean {
  if (mod && !it.id.startsWith(mod)) return false;
  const hay = (it.name + ' ' + it.id).toLowerCase();
  return words.every((w) => hay.includes(w));
}

export function newLayer(kind: Kind, id: string): Layer {
  return {
    kind,
    id,
    props: {},
    group: '',
    visible: true,
    offset: [0, 0, 0],
    rotation: [0, 0, 0],
    scale: kind === 'PARTICLE' ? 0.35 : 1,
    spin: 0,
    spinAxis: 0,
    hover: 0,
    hoverPeriod: 4,
    fullbright: false,
    particleRate: 10,
    particleSpeed: 0.01,
    effects: [],
  };
}

export function addLayer(kind: Kind, id: string): void {
  const spec = S.spec!;
  const max = S.registries!.limits.maxLayers;
  if (spec.layers.length >= max) return;
  const sel = S.selection;
  // New layers stack on top of what is there, like the in-game editor, and join the selected layer's group.
  let top = 0;
  for (const l of spec.layers) top = Math.max(top, l.offset[1] + (l.kind === 'PARTICLE' ? 0.5 : l.scale));
  const group = sel.kind === 'layer' ? spec.layers[sel.layer]?.group ?? '' : sel.kind === 'group' ? sel.group ?? '' : '';
  edit((s) => {
    const l = newLayer(kind, id);
    l.offset = [0, Math.min(top, 16), 0];
    l.group = s.groups.some((g) => g.id === group) ? group : '';
    s.layers.push(l);
  });
  select('layer', spec.layers.length);
}

export function ContentBrowser() {
  const [kind, setKind] = useState<Kind>('BLOCK');
  const [query, setQuery] = useState('');
  const spec = S.spec!;
  const sel = S.selection;
  const layer = sel.kind === 'layer' ? spec.layers[sel.layer] : undefined;
  const full = spec.layers.length >= S.registries!.limits.maxLayers;

  const all = useMemo(() => items(kind), [kind, S.registries]);
  const found = useMemo(() => {
    const raw = query.trim().toLowerCase();
    const mod = raw.split(/\s+/).find((w) => w.startsWith('@'));
    const words = raw.split(/\s+/).filter((w) => w && !w.startsWith('@'));
    const ns = mod && mod.length > 1 ? mod.slice(1) + ':' : null;
    return all.filter((it) => matches(it, words, ns));
  }, [all, query]);

  const replace = (it: Item) => {
    if (!layer) return;
    const index = sel.layer;
    edit((s) => {
      const l = s.layers[index];
      if (l.kind === kind && l.id === it.id) return;
      l.kind = kind;
      l.id = it.id;
      l.props = {};
      delete l.data; // block-entity / entity data belongs to what it was captured from
      if (kind === 'PARTICLE' && l.scale > 1) l.scale = 0.35;
    });
  };

  return (
    <div class="browser">
      <div class="panel-head">
        <span>Content</span>
        <div class="tabs">
          {(['BLOCK', 'ENTITY', 'PARTICLE'] as Kind[]).map((k) => (
            <button key={k} class={`tab ${kind === k ? 'on' : ''}`} onClick={() => setKind(k)}>
              {k === 'BLOCK' ? 'Blocks' : k === 'ENTITY' ? 'Entities' : 'Particles'}
            </button>
          ))}
        </div>
      </div>
      <div class="browser-search">
        <input
          class="search"
          placeholder="Search… (@mod to filter by mod)"
          value={query}
          onInput={(e) => setQuery((e.currentTarget as HTMLInputElement).value)}
          spellcheck={false}
        />
        <span class="muted small">
          {found.length.toLocaleString()} {found.length > SHOWN ? `· first ${SHOWN}` : ''}
        </span>
      </div>
      <div class="browser-hint muted small">
        {layer
          ? `Click: replace layer ${sel.layer + 1} (${layer.id}). + or double-click: add a new layer.`
          : 'Select a layer to replace its content, or use + to add one.'}
      </div>
      <div class="browser-list">
        {found.slice(0, SHOWN).map((it) => {
          const current = layer && layer.kind === kind && layer.id === it.id;
          return (
            <div
              key={it.id}
              class={`item ${current ? 'current' : ''}`}
              onClick={() => replace(it)}
              onDblClick={() => !full && addLayer(kind, it.id)}
              title={it.id}
            >
              <span class={`kind kind-${kind.toLowerCase()}`}>{KIND_BADGE[kind]}</span>
              <span class="item-name">{it.name}</span>
              <span class="item-id mono">{it.id}</span>
              {it.extra && <span class="chip">{it.extra}</span>}
              <button
                class="icon-btn add"
                title={full ? 'Layer limit reached' : 'Add as a new layer'}
                disabled={full}
                onClick={(e) => {
                  e.stopPropagation();
                  addLayer(kind, it.id);
                }}
              >
                +
              </button>
            </div>
          );
        })}
        {found.length === 0 && <div class="empty">Nothing matches “{query}”.</div>}
      </div>
    </div>
  );
}

/** Used by the Delete key and the toolbar. */
export function deleteSelection(): void {
  const spec = S.spec;
  if (!spec) return;
  const sel = S.selection;
  if (sel.kind === 'layer') {
    if (spec.layers.length <= 1) return;
    const i = sel.layer;
    edit((s) => s.layers.splice(i, 1));
    select('layer', Math.max(0, Math.min(i, spec.layers.length - 2)));
  } else if (sel.kind === 'group' && sel.group) {
    const id = sel.group;
    edit((s) => {
      s.groups = s.groups.filter((g) => g.id !== id);
      s.layers.forEach((l) => l.group === id && (l.group = ''));
    });
    select('root');
  }
}

export function duplicateSelection(): void {
  const spec = S.spec;
  if (!spec || S.selection.kind !== 'layer' || spec.layers.length >= S.registries!.limits.maxLayers) return;
  const i = S.selection.layer;
  edit((s) => s.layers.splice(i + 1, 0, structuredClone(s.layers[i])));
  select('layer', i + 1);
}

