// Right panel: what the selected root / group / layer is, and its effect stack.

import type { Effect, Group, Layer, Motion, Spec } from './protocol';
import { ASSETS, REG, S, edit, newGesture } from './store';
import { AxisField, ColorField, NumRow, Row, Section, SelectField, Toggle, Vec3Field } from './ui';
import { layerName } from './outliner';
import { effectRemoved, effectsSwapped } from './anim';
import { aspectOf, imageHeight } from './library';

type Target = { kind: 'group'; g: Group } | { kind: 'layer'; l: Layer; index: number };

/** Runs {@code fn} on the selected group or layer inside an edit. */
function editTarget(t: Target, fn: (x: Group | Layer, s: Spec) => void, gesture?: string) {
  edit((s) => {
    const x = t.kind === 'group' ? s.groups.find((g) => g.id === t.g.id) : s.layers[t.index];
    if (x) fn(x, s);
  }, gesture);
}

export function Inspector() {
  const spec = S.spec!;
  const sel = S.selection;
  if (sel.kind === 'root') return <RootInspector spec={spec} />;
  if (sel.kind === 'group') {
    const g = spec.groups.find((x) => x.id === sel.group);
    if (g) return <TargetInspector t={{ kind: 'group', g }} />;
  }
  const index = Math.min(Math.max(sel.layer, 0), spec.layers.length - 1);
  return <TargetInspector t={{ kind: 'layer', l: spec.layers[index], index }} />;
}

function RootInspector({ spec }: { spec: Spec }) {
  const lim = S.registries!.limits;
  const styles = S.registries!.revealStyles;
  return (
    <div class="inspector">
      <div class="insp-title">
        <span class="kind kind-root">◆</span>
        <div>
          <div class="insp-name">Hologram {spec.id}</div>
          <div class="muted mono small">
            {spec.pos.join(', ')} · {spec.owner || 'no owner'}
          </div>
        </div>
      </div>
      <Section title="Transform">
        <Vec3Field
          label="Offset"
          value={spec.root.offset}
          min={lim.offset[0]}
          max={lim.offset[1]}
          step={0.05}
          decimals={2}
          onChange={(v, g) => edit((s) => (s.root.offset = v), g)}
        />
        <Vec3Field
          label="Rotation"
          value={spec.root.rotation}
          min={-360}
          max={360}
          step={1}
          decimals={1}
          unit="°"
          onChange={(v, g) => edit((s) => (s.root.rotation = v), g)}
        />
        <NumRow
          rowLabel="Scale"
          label="↔"
          value={spec.root.scale}
          min={lim.rootScale[0]}
          max={lim.rootScale[1]}
          step={0.05}
          decimals={2}
          onChange={(v, g) => edit((s) => (s.root.scale = v), g)}
        />
      </Section>
      <Section title="Reveal">
        <NumRow
          rowLabel="Radius"
          label="r"
          title="Players closer than this see it appear; 0 = always visible"
          value={spec.reveal.radius}
          min={lim.revealRadius[0]}
          max={lim.revealRadius[1]}
          step={0.5}
          decimals={1}
          unit="blocks"
          onChange={(v, g) => edit((s) => (s.reveal.radius = v), g)}
        />
        <NumRow
          rowLabel="Duration"
          label="t"
          value={spec.reveal.seconds}
          min={lim.revealSeconds[0]}
          max={lim.revealSeconds[1]}
          step={0.05}
          decimals={2}
          unit="s"
          onChange={(v, g) => edit((s) => (s.reveal.seconds = v), g)}
        />
        <Row label="Style">
          <SelectField
            value={String(spec.reveal.style)}
            options={styles.map((n, i) => ({ value: String(i), label: n }))}
            onChange={(v) => edit((s) => (s.reveal.style = Number(v)))}
          />
        </Row>
      </Section>
      <Section title="Display">
        <NumRow
          rowLabel="Range"
          label="d"
          title={`Render distance; this server allows up to ${spec.maxRenderDistance}`}
          value={spec.renderDistance}
          min={lim.renderDistance[0]}
          max={spec.maxRenderDistance}
          step={1}
          decimals={0}
          unit="blocks"
          onChange={(v, g) => edit((s) => (s.renderDistance = v), g)}
        />
      </Section>
    </div>
  );
}

function TargetInspector({ t }: { t: Target }) {
  const lim = S.registries!.limits;
  const x: Group | Layer = t.kind === 'group' ? t.g : t.l;
  const scaleRange = t.kind === 'group' ? lim.groupScale : lim.layerScale;
  return (
    <div class="inspector">
      {t.kind === 'layer' ? <LayerHeader l={t.l} index={t.index} /> : <GroupHeader g={t.g} />}
      {t.kind === 'layer' && t.l.kind === 'BLOCK' && <BlockProps l={t.l} index={t.index} />}
      {t.kind === 'layer' && t.l.kind === 'IMAGE' && <ImageSection l={t.l} index={t.index} />}
      <Section title="Transform">
        <Vec3Field
          label="Offset"
          value={x.offset}
          min={lim.offset[0]}
          max={lim.offset[1]}
          step={0.05}
          decimals={2}
          onChange={(v, g) => editTarget(t, (y) => (y.offset = v), g)}
        />
        <Vec3Field
          label="Rotation"
          value={x.rotation}
          min={-360}
          max={360}
          step={1}
          decimals={1}
          unit="°"
          onChange={(v, g) => editTarget(t, (y) => (y.rotation = v), g)}
        />
        <NumRow
          rowLabel="Scale"
          label="↔"
          value={x.scale}
          min={scaleRange[0]}
          max={scaleRange[1]}
          step={0.05}
          decimals={2}
          onChange={(v, g) => editTarget(t, (y) => (y.scale = v), g)}
        />
      </Section>
      <MotionSection t={t} x={x} />
      {t.kind === 'layer' && t.l.kind === 'PARTICLE' && (
        <Section title="Particles">
          <NumRow
            rowLabel="Rate"
            label="n"
            value={t.l.particleRate}
            min={lim.particleRate[0]}
            max={lim.particleRate[1]}
            step={0.5}
            decimals={1}
            unit="/s"
            onChange={(v, g) => editTarget(t, (y) => ((y as Layer).particleRate = v), g)}
          />
          <NumRow
            rowLabel="Speed"
            label="v"
            value={t.l.particleSpeed}
            min={lim.particleSpeed[0]}
            max={lim.particleSpeed[1]}
            step={0.005}
            decimals={3}
            onChange={(v, g) => editTarget(t, (y) => ((y as Layer).particleSpeed = v), g)}
          />
        </Section>
      )}
      <EffectStack t={t} effects={x.effects} />
      {t.kind === 'layer' && t.l.data && (
        <Section title="Saved data" open={false}>
          <div class="muted small">
            {t.l.data.bytes} bytes of {t.l.kind === 'BLOCK' ? 'block entity' : 'entity'} data, captured in game. It
            follows the layer but cannot be edited here.
          </div>
          {t.l.data.snbt ? <pre class="snbt">{t.l.data.snbt}</pre> : <div class="muted small">Too large to preview.</div>}
        </Section>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- image layers

/** Same rounding as HologramImage.format, so what the page shows is what the game stores. */
function fmt(v: number): string {
  return String(Math.round(v * 1000) / 1000);
}

const BILLBOARDS = [
  { value: 'FIXED', label: 'Fixed (follows rotation)' },
  { value: 'FACE', label: 'Face the camera' },
  { value: 'FACE_Y', label: 'Face the camera, upright' },
];

const PLAYBACKS = [
  { value: 'LOOP', label: 'Loop' },
  { value: 'PINGPONG', label: 'Ping-pong' },
  { value: 'ONCE', label: 'Once, then hold' },
];

/** Sets (or, with undefined, removes) one image prop; the game drops values that equal the defaults. */
function setImageProp(index: number, key: string, value: string | undefined, gesture?: string) {
  edit((s) => {
    const props = { ...s.layers[index].props };
    if (value === undefined) delete props[key];
    else props[key] = value;
    s.layers[index].props = props;
  }, gesture);
}

function ImageSection({ l, index }: { l: Layer; index: number }) {
  const asset = ASSETS.get(l.id);
  const width = Number(l.props.w ?? 1) || 1;
  const keepAspect = !(Number(l.props.h ?? 0) > 0);
  const height = imageHeight(l);
  const billboard = l.props.billboard ?? 'FIXED';
  const animated = !asset || asset.frames > 1;
  return (
    <Section title="Image">
      <Row label="Picture">
        <span class="small">
          {asset ? (
            <>
              {asset.name} <span class="muted">· {asset.width}×{asset.height}</span>
              {asset.frames > 1 && <span class="muted"> · {asset.frames} frames</span>}
            </>
          ) : (
            <span class="muted mono">{l.id.slice(0, 16)}…</span>
          )}
        </span>
      </Row>
      <NumRow
        rowLabel="Width"
        label="w"
        value={width}
        min={0.05}
        max={16}
        step={0.05}
        decimals={2}
        unit="blocks"
        onChange={(v, g) => {
          // With a set height, keep the shape the user had: both sides scale together.
          if (keepAspect) setImageProp(index, 'w', fmt(v), g);
          else
            edit((s) => {
              const ratio = height / width;
              s.layers[index].props = { ...s.layers[index].props, w: fmt(v), h: fmt(Math.min(16, Math.max(0.05, v * ratio))) };
            }, g);
        }}
      />
      <Row label="Keep aspect" title="Height follows the picture's own shape">
        <Toggle
          value={keepAspect}
          onChange={(v) => setImageProp(index, 'h', v ? undefined : fmt(Math.min(16, Math.max(0.05, width * aspectOf(l.id)))))}
        />
      </Row>
      {keepAspect ? (
        <Row label="Height">
          <span class="muted small">{height.toFixed(2)} blocks, from the picture</span>
        </Row>
      ) : (
        <NumRow
          rowLabel="Height"
          label="h"
          value={height}
          min={0.05}
          max={16}
          step={0.05}
          decimals={2}
          unit="blocks"
          onChange={(v, g) => setImageProp(index, 'h', fmt(v), g)}
        />
      )}
      <Row label="Facing">
        <SelectField
          value={billboard}
          options={BILLBOARDS}
          onChange={(v) => setImageProp(index, 'billboard', v === 'FIXED' ? undefined : v)}
        />
      </Row>
      {billboard === 'FIXED' && (
        <Row label="Double-sided" title="Off hides the back of the picture">
          <Toggle value={l.props.sides !== '1'} onChange={(v) => setImageProp(index, 'sides', v ? undefined : '1')} />
        </Row>
      )}
      <Row label="Emissive" title="Full brightness, day and night">
        <Toggle value={l.fullbright} onChange={(v) => edit((s) => (s.layers[index].fullbright = v))} />
      </Row>
      {animated && (
        <>
          <Row label="Playback">
            <SelectField
              value={l.props.play ?? 'LOOP'}
              options={PLAYBACKS}
              onChange={(v) => setImageProp(index, 'play', v === 'LOOP' ? undefined : v)}
            />
          </Row>
          <NumRow
            rowLabel="Speed"
            label="×"
            title="Negative plays backwards"
            value={Number(l.props.speed ?? 1)}
            min={-8}
            max={8}
            step={0.05}
            decimals={2}
            onChange={(v, g) => setImageProp(index, 'speed', v === 1 ? undefined : fmt(v), g)}
          />
          <NumRow
            rowLabel="Start at"
            label="t₀"
            title="Seconds into the animation the hologram's clock starts from"
            value={Number(l.props.start ?? 0)}
            min={0}
            max={600}
            step={0.05}
            decimals={2}
            unit="s"
            onChange={(v, g) => setImageProp(index, 'start', v === 0 ? undefined : fmt(v), g)}
          />
          <div class="muted small">
            The animation runs on the hologram's clock: the timeline's playhead, pause and scrub move it too.
          </div>
        </>
      )}
    </Section>
  );
}

function LayerHeader({ l, index }: { l: Layer; index: number }) {
  const spec = S.spec!;
  const groups = [{ value: '', label: 'No group' }, ...spec.groups.map((g) => ({ value: g.id, label: g.name }))];
  return (
    <>
      <div class="insp-title">
        <span class={`kind kind-${l.kind.toLowerCase()}`}>{l.kind[0]}</span>
        <div>
          <div class="insp-name">{layerName(l)}</div>
          <div class="muted mono small">
            {l.id} · layer {index + 1}
          </div>
        </div>
      </div>
      <Section title="Layer">
        <Row label="Visible">
          <Toggle value={l.visible} onChange={(v) => edit((s) => (s.layers[index].visible = v))} />
        </Row>
        <Row label="Group">
          <SelectField value={l.group} options={groups} onChange={(v) => edit((s) => (s.layers[index].group = v))} />
        </Row>
        <Row label="Content">
          <span class="muted small">
            {l.kind === 'IMAGE'
              ? 'Pick another picture in the Images tab, middle panel.'
              : 'Pick a replacement in the browser, middle panel.'}
          </span>
        </Row>
      </Section>
    </>
  );
}

function GroupHeader({ g }: { g: Group }) {
  const members = S.spec!.layers.filter((l) => l.group === g.id).length;
  return (
    <>
      <div class="insp-title">
        <span class="kind kind-group">▤</span>
        <div>
          <div class="insp-name">{g.name}</div>
          <div class="muted mono small">
            group {g.id} · {members} layer{members === 1 ? '' : 's'}
          </div>
        </div>
      </div>
      <Section title="Group">
        <Row label="Name">
          <input
            class="text"
            value={g.name}
            maxLength={S.registries!.limits.maxNameLength}
            onChange={(e) => {
              const name = (e.currentTarget as HTMLInputElement).value.trim();
              if (name) edit((s) => (s.groups.find((x) => x.id === g.id)!.name = name));
            }}
          />
        </Row>
        <Row label="Visible">
          <Toggle value={g.visible} onChange={(v) => edit((s) => (s.groups.find((x) => x.id === g.id)!.visible = v))} />
        </Row>
      </Section>
    </>
  );
}

function BlockProps({ l, index }: { l: Layer; index: number }) {
  const reg = S.registries!;
  const def = REG.blocks.get(l.id);
  if (!def?.props?.length) return null;
  return (
    <Section title="Block state">
      {def.props.map((pi) => {
        const p = reg.properties[pi];
        const value = l.props[p.name] ?? def.defaults?.[p.name] ?? p.values[0];
        return (
          <Row key={p.name} label={p.name}>
            {p.values.length === 2 && p.values.includes('true') ? (
              <Toggle
                value={value === 'true'}
                onChange={(v) => edit((s) => (s.layers[index].props[p.name] = String(v)))}
              />
            ) : (
              <SelectField
                value={value}
                options={p.values.map((v) => ({ value: v, label: v }))}
                onChange={(v) => edit((s) => (s.layers[index].props[p.name] = v))}
              />
            )}
          </Row>
        );
      })}
    </Section>
  );
}

function MotionSection({ t, x }: { t: Target; x: Motion }) {
  const lim = S.registries!.limits;
  return (
    <Section title="Motion" open={x.spin !== 0 || x.hover !== 0}>
      <NumRow
        rowLabel="Spin"
        label="ω"
        title="Legacy spin; a SPIN effect does the same with more options"
        value={x.spin}
        min={lim.spin[0]}
        max={lim.spin[1]}
        step={1}
        decimals={0}
        unit="°/s"
        onChange={(v, g) => editTarget(t, (y) => (y.spin = v), g)}
      />
      <Row label="Spin axis">
        <AxisField value={x.spinAxis} onChange={(v) => editTarget(t, (y) => (y.spinAxis = v))} />
      </Row>
      <NumRow
        rowLabel="Hover"
        label="h"
        value={x.hover}
        min={lim.hover[0]}
        max={lim.hover[1]}
        step={0.01}
        decimals={2}
        unit="blocks"
        onChange={(v, g) => editTarget(t, (y) => (y.hover = v), g)}
      />
      <NumRow
        rowLabel="Period"
        label="T"
        value={x.hoverPeriod}
        min={lim.hoverPeriod[0]}
        max={lim.hoverPeriod[1]}
        step={0.1}
        decimals={1}
        unit="s"
        onChange={(v, g) => editTarget(t, (y) => (y.hoverPeriod = v), g)}
      />
      <Row label="Fullbright">
        <Toggle value={x.fullbright} onChange={(v) => editTarget(t, (y) => (y.fullbright = v))} />
      </Row>
    </Section>
  );
}

// ---------------------------------------------------------------- effects

function EffectStack({ t, effects }: { t: Target; effects: Effect[] }) {
  const reg = S.registries!;
  const max = reg.limits.maxEffects;
  const families: [string, string][] = [
    ['fx', 'Look'],
    ['motion', 'Motion'],
    ['entity', 'Entity animation'],
  ];
  const add = (value: string) => {
    if (!value) return;
    if (value.startsWith('preset:')) {
      const preset = reg.presets.find((p) => p.name === value.slice(7));
      if (preset)
        editTarget(t, (y) => (y.effects = [...y.effects, ...structuredClone(preset.effects)].slice(0, max)));
    } else {
      const def = REG.effects.get(value);
      if (def) editTarget(t, (y) => y.effects.length < max && y.effects.push(structuredClone(def.defaults)));
    }
  };
  return (
    <Section
      title={`Effects ${effects.length ? `(${effects.length})` : ''}`}
      right={
        <select
          class="select add-effect"
          value=""
          disabled={effects.length >= max}
          onChange={(e) => {
            add((e.currentTarget as HTMLSelectElement).value);
            (e.currentTarget as HTMLSelectElement).value = '';
          }}
        >
          <option value="">{effects.length >= max ? `Max ${max}` : '+ Add'}</option>
          {families.map(([f, label]) => (
            <optgroup key={f} label={label}>
              {reg.effects
                .filter((e) => e.family === f)
                .map((e) => (
                  <option key={e.type} value={e.type}>
                    {e.label}
                  </option>
                ))}
            </optgroup>
          ))}
          {reg.presets.length > 0 && (
            <optgroup label="Presets">
              {reg.presets.map((p) => (
                <option key={p.name} value={`preset:${p.name}`}>
                  {p.name}
                </option>
              ))}
            </optgroup>
          )}
        </select>
      }
    >
      {effects.length === 0 && <div class="empty">No effects. Add one from the menu above.</div>}
      {effects.map((e, i) => (
        <EffectCard key={`${i}-${e.type}`} t={t} e={e} i={i} count={effects.length} />
      ))}
    </Section>
  );
}

function EffectCard({ t, e, i, count }: { t: Target; e: Effect; i: number; count: number }) {
  const reg = S.registries!;
  const def = REG.effects.get(e.type);
  const set = (fn: (x: Effect) => void, gesture?: string) => editTarget(t, (y) => y.effects[i] && fn(y.effects[i]), gesture);
  const move = (d: number) =>
    editTarget(t, (y, s) => {
      const j = i + d;
      if (j < 0 || j >= y.effects.length) return;
      [y.effects[i], y.effects[j]] = [y.effects[j], y.effects[i]];
      effectsSwapped(s, t.kind === 'group' ? 'GROUP' : 'LAYER', t.kind === 'group' ? t.g.id : (t.l.uid ?? ''), i, j);
    });
  return (
    <div class={`fx-card fam-${def?.family ?? 'fx'}`}>
      <div class="fx-head">
        <span class="fx-badge">{def?.short ?? e.type}</span>
        <span class="fx-name">{def?.label ?? e.type}</span>
        <span class="fx-tools">
          <button class="icon-btn" title="Move up" disabled={i === 0} onClick={() => move(-1)}>
            ↑
          </button>
          <button class="icon-btn" title="Move down" disabled={i === count - 1} onClick={() => move(1)}>
            ↓
          </button>
          <button
            class="icon-btn danger"
            title="Remove effect"
            onClick={() =>
              editTarget(t, (y, s) => {
                y.effects.splice(i, 1);
                effectRemoved(s, t.kind === 'group' ? 'GROUP' : 'LAYER', t.kind === 'group' ? t.g.id : (t.l.uid ?? ''), i);
              })
            }
          >
            ×
          </button>
        </span>
      </div>
      <div class="fx-body">
        {(def?.usesAmount ?? true) && (
          <NumRow
            rowLabel="Strength"
            label="a"
            value={e.strength}
            min={def?.min ?? 0}
            max={def?.max ?? 2}
            step={def?.step ?? 0.05}
            decimals={def?.decimals}
            onChange={(v, g) => set((x) => (x.strength = v), g)}
          />
        )}
        {(def?.usesSpeed ?? true) && (
          <NumRow
            rowLabel="Speed"
            label="×"
            value={e.speed}
            min={reg.limits.speed[0]}
            max={reg.limits.speed[1]}
            step={0.05}
            decimals={2}
            onChange={(v, g) => set((x) => (x.speed = v), g)}
          />
        )}
        {def?.usesAxis && (
          <Row label="Axis">
            <AxisField value={e.axis} onChange={(v) => set((x) => (x.axis = v))} />
          </Row>
        )}
        {def?.usesPhase && (
          <NumRow
            rowLabel="Phase"
            label="φ"
            value={e.phase}
            min={0}
            max={1}
            step={0.01}
            decimals={2}
            onChange={(v, g) => set((x) => (x.phase = v), g)}
          />
        )}
        {def?.usesColor && (
          <Row label={def.usesColor2 ? 'From' : 'Colour'}>
            <ColorField value={e.color} onChange={(v, g) => set((x) => (x.color = v), g)} />
          </Row>
        )}
        {def?.usesColor2 && (
          <Row label="To">
            <ColorField value={e.color2} onChange={(v, g) => set((x) => (x.color2 = v), g)} />
          </Row>
        )}
        <Row label="When">
          <SelectField
            value={e.trigger}
            options={reg.triggers.map((tr) => ({ value: tr.name, label: tr.label }))}
            onChange={(v) => set((x) => (x.trigger = v), newGesture())}
          />
        </Row>
      </div>
    </div>
  );
}

