// Left panel: root, groups (folders) and layers, with drag reorder, visibility, solo and rename.

import { useState } from 'preact/hooks';
import type { Group, Layer, Spec } from './protocol';
import { REG, S, edit, select, solo } from './store';

export function layerName(l: Layer): string {
  switch (l.kind) {
    case 'BLOCK':
      return REG.blocks.get(l.id)?.name ?? l.id;
    case 'ENTITY':
      return REG.entities.get(l.id) ?? l.id;
    default:
      return l.id.replace(/^minecraft:/, '').replace(/_/g, ' ');
  }
}

export const KIND_BADGE: Record<Layer['kind'], string> = { BLOCK: 'B', ENTITY: 'E', PARTICLE: 'P' };

type Drag = { type: 'layer'; index: number } | { type: 'group'; id: string };
let dragging: Drag | null = null;
type DropPos = 'before' | 'after' | 'into';

/** Moves layer {@code from} next to layer {@code to} and into its group; returns the moved layer's new index. */
function moveLayer(spec: Spec, from: number, to: number, pos: 'before' | 'after', group: string): number {
  const [l] = spec.layers.splice(from, 1);
  l.group = group;
  let target = to > from ? to - 1 : to;
  if (pos === 'after') target++;
  spec.layers.splice(target, 0, l);
  return target;
}

function uniqueGroupId(spec: Spec): string {
  for (;;) {
    const id = 'g' + Math.random().toString(36).slice(2, 7);
    if (!spec.groups.some((g) => g.id === id)) return id;
  }
}

export function addGroup(): void {
  const spec = S.spec;
  const reg = S.registries;
  if (!spec || !reg || spec.groups.length >= reg.limits.maxGroups) return;
  const id = uniqueGroupId(spec);
  const sel = S.selection;
  edit((s) => {
    s.groups.push({
      id,
      name: `Group ${s.groups.length + 1}`,
      visible: true,
      offset: [0, 0, 0],
      rotation: [0, 0, 0],
      scale: 1,
      spin: 0,
      spinAxis: 0,
      hover: 0,
      hoverPeriod: 4,
      fullbright: false,
      effects: [],
    });
    // A selected ungrouped layer goes into the new group: the usual reason to make one.
    if (sel.kind === 'layer' && s.layers[sel.layer] && !s.layers[sel.layer].group) s.layers[sel.layer].group = id;
  });
  select('group', -1, id);
}

export function Outliner() {
  const spec = S.spec!;
  const sel = S.selection;
  const [dropAt, setDropAt] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const dropPos = (e: DragEvent, allowInto: boolean): DropPos => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const y = (e.clientY - r.top) / r.height;
    if (allowInto && y > 0.25 && y < 0.75) return 'into';
    return y < 0.5 ? 'before' : 'after';
  };

  const onLayerDrop = (e: DragEvent, index: number) => {
    e.preventDefault();
    setDropAt(null);
    const d = dragging;
    dragging = null;
    if (!d || d.type !== 'layer' || d.index === index) return;
    const pos = dropPos(e, false) as 'before' | 'after';
    let moved = -1;
    edit((s) => (moved = moveLayer(s, d.index, index, pos, s.layers[index].group)));
    select('layer', moved);
  };

  const onGroupDrop = (e: DragEvent, g: Group) => {
    e.preventDefault();
    setDropAt(null);
    const d = dragging;
    dragging = null;
    if (!d) return;
    if (d.type === 'layer') {
      let moved = -1;
      edit((s) => {
        const last = s.layers.map((l, i) => (l.group === g.id ? i : -1)).filter((i) => i >= 0 && i !== d.index).pop();
        moved = last === undefined ? d.index : moveLayer(s, d.index, last, 'after', g.id);
        s.layers[moved].group = g.id;
      });
      select('layer', moved);
    } else if (d.id !== g.id) {
      const pos = dropPos(e, false);
      edit((s) => {
        const from = s.groups.findIndex((x) => x.id === d.id);
        const [moving] = s.groups.splice(from, 1);
        let to = s.groups.findIndex((x) => x.id === g.id);
        if (pos === 'after') to++;
        s.groups.splice(to, 0, moving);
      });
    }
  };

  const onUngroupedDrop = (e: DragEvent) => {
    e.preventDefault();
    setDropAt(null);
    const d = dragging;
    dragging = null;
    if (d?.type !== 'layer') return;
    edit((s) => (s.layers[d.index].group = ''));
  };

  const markDrop = (e: DragEvent, key: string, allowInto: boolean) => {
    if (!dragging) return;
    e.preventDefault();
    const k = `${key}:${dropPos(e, allowInto)}`;
    if (k !== dropAt) setDropAt(k);
  };

  const dropClass = (key: string) =>
    dropAt?.startsWith(key + ':') ? `drop-${dropAt.slice(key.length + 1)}` : '';

  const layerRow = (l: Layer, i: number, depth: number) => {
    const key = `L${i}`;
    const active = sel.kind === 'layer' && sel.layer === i;
    const soloed = sel.soloLayer === i;
    return (
      <div
        key={key}
        class={`tree-row layer ${active ? 'active' : ''} ${l.visible ? '' : 'hidden'} ${dropClass(key)}`}
        style={{ paddingLeft: `${8 + depth * 16}px` }}
        draggable
        onDragStart={(e) => {
          dragging = { type: 'layer', index: i };
          e.dataTransfer?.setData('text/plain', key);
        }}
        onDragEnd={() => {
          dragging = null;
          setDropAt(null);
        }}
        onDragOver={(e) => markDrop(e, key, false)}
        onDragLeave={() => setDropAt(null)}
        onDrop={(e) => onLayerDrop(e, i)}
        onClick={() => select('layer', i)}
      >
        <span class={`kind kind-${l.kind.toLowerCase()}`}>{KIND_BADGE[l.kind]}</span>
        <span class="tree-name" title={l.id}>
          {layerName(l)}
        </span>
        {l.effects.length > 0 && <span class="fx-count">{l.effects.length} fx</span>}
        <button
          class={`icon-btn ${soloed ? 'on solo' : ''}`}
          title="Solo: show only this layer in game"
          onClick={(e) => {
            e.stopPropagation();
            solo(soloed ? -1 : i, null);
          }}
        >
          S
        </button>
        <button
          class={`icon-btn eye ${l.visible ? 'on' : ''}`}
          title={l.visible ? 'Hide layer' : 'Show layer'}
          onClick={(e) => {
            e.stopPropagation();
            edit((s) => (s.layers[i].visible = !s.layers[i].visible));
          }}
        >
          {l.visible ? '●' : '○'}
        </button>
      </div>
    );
  };

  const groupRow = (g: Group) => {
    const key = `G${g.id}`;
    const active = sel.kind === 'group' && sel.group === g.id;
    const soloed = sel.soloGroup === g.id;
    const members = spec.layers.map((l, i) => [l, i] as const).filter(([l]) => l.group === g.id);
    const isCollapsed = collapsed[g.id];
    return (
      <div key={key}>
        <div
          class={`tree-row group ${active ? 'active' : ''} ${g.visible ? '' : 'hidden'} ${dropClass(key)}`}
          draggable={renaming !== g.id}
          onDragStart={(e) => {
            dragging = { type: 'group', id: g.id };
            e.dataTransfer?.setData('text/plain', key);
          }}
          onDragEnd={() => {
            dragging = null;
            setDropAt(null);
          }}
          onDragOver={(e) => markDrop(e, key, dragging?.type === 'layer')}
          onDragLeave={() => setDropAt(null)}
          onDrop={(e) => onGroupDrop(e, g)}
          onClick={() => select('group', -1, g.id)}
          onDblClick={() => setRenaming(g.id)}
        >
          <span
            class="caret"
            onClick={(e) => {
              e.stopPropagation();
              setCollapsed({ ...collapsed, [g.id]: !isCollapsed });
            }}
          >
            {isCollapsed ? '▸' : '▾'}
          </span>
          <span class="folder">▤</span>
          {renaming === g.id ? (
            <input
              class="rename"
              value={g.name}
              maxLength={S.registries?.limits.maxNameLength ?? 32}
              ref={(el) => el && requestAnimationFrame(() => el === document.activeElement || el.select())}
              autoFocus
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === 'Escape') (e.currentTarget as HTMLInputElement).blur();
              }}
              onBlur={(e) => {
                const name = (e.currentTarget as HTMLInputElement).value.trim();
                setRenaming(null);
                if (name && name !== g.name) edit((s) => (s.groups.find((x) => x.id === g.id)!.name = name));
              }}
            />
          ) : (
            <span class="tree-name" title="Double-click to rename">
              {g.name}
            </span>
          )}
          <span class="fx-count">{members.length}</span>
          <button
            class={`icon-btn ${soloed ? 'on solo' : ''}`}
            title="Solo: show only this group in game"
            onClick={(e) => {
              e.stopPropagation();
              solo(-1, soloed ? null : g.id);
            }}
          >
            S
          </button>
          <button
            class={`icon-btn eye ${g.visible ? 'on' : ''}`}
            title={g.visible ? 'Hide group' : 'Show group'}
            onClick={(e) => {
              e.stopPropagation();
              edit((s) => {
                const x = s.groups.find((y) => y.id === g.id)!;
                x.visible = !x.visible;
              });
            }}
          >
            {g.visible ? '●' : '○'}
          </button>
        </div>
        {!isCollapsed && members.map(([l, i]) => layerRow(l, i, 1))}
      </div>
    );
  };

  const ungrouped = spec.layers.map((l, i) => [l, i] as const).filter(([l]) => !l.group || !spec.groups.some((g) => g.id === l.group));
  const reg = S.registries!;

  return (
    <div class="outliner">
      <div class="panel-head">
        <span>Layers</span>
        <span class="panel-tools">
          <button
            class="mini-btn"
            title="New group"
            disabled={spec.groups.length >= reg.limits.maxGroups}
            onClick={addGroup}
          >
            + Group
          </button>
        </span>
      </div>
      <div class="tree">
        <div class={`tree-row root ${sel.kind === 'root' ? 'active' : ''}`} onClick={() => select('root')}>
          <span class="folder">◆</span>
          <span class="tree-name">Hologram</span>
          <span class="muted mono">{spec.id}</span>
        </div>
        {spec.groups.map(groupRow)}
        {spec.groups.length > 0 && (
          <div
            class={`tree-sep ${dropAt === 'U:into' ? 'drop-into' : ''}`}
            onDragOver={(e) => {
              if (dragging?.type !== 'layer') return;
              e.preventDefault();
              setDropAt('U:into');
            }}
            onDragLeave={() => setDropAt(null)}
            onDrop={onUngroupedDrop}
          >
            Ungrouped
          </div>
        )}
        {ungrouped.map(([l, i]) => layerRow(l, i, 0))}
      </div>
      <div class="panel-foot muted">
        {spec.layers.length}/{reg.limits.maxLayers} layers · {spec.groups.length}/{reg.limits.maxGroups} groups
      </div>
    </div>
  );
}
