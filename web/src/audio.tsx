// Audio modulations: "value += amount x signal" with an envelope, on effects (strength / speed / offset / hue) and on
// root / group / layer values (kept on the timeline). The game does all listening and analysis; this page only edits
// the numbers and shows levels the game streams (never sound).

import type { AudioLink, AudioMod, AudioParam, AudioSignal, AudioSource, Spec, TargetKind } from './protocol';
import { S, edit, newGesture, useMeter } from './store';
import { emptyTimeline } from './anim';
import { NumRow, Row, Section, SelectField } from './ui';

export const SOURCES: { value: AudioSource; label: string }[] = [
  { value: 'AUTO', label: 'Any music' },
  { value: 'JUKEBOX', label: 'Jukeboxes' },
  { value: 'MUSIC', label: 'Game music' },
  { value: 'SYSTEM', label: 'This PC (system audio)' },
];

export const SIGNALS: { value: AudioSignal; label: string }[] = [
  { value: 'LEVEL', label: 'Level' },
  { value: 'BASS', label: 'Bass' },
  { value: 'MID', label: 'Mid' },
  { value: 'HIGH', label: 'High' },
  { value: 'BEAT', label: 'Beat' },
];

interface ParamDef {
  label: string;
  max: number;
  step: number;
  def: number;
  unit?: string;
}

/** Mirrors HologramAudioMod.Param (label, maxAmount, amountStep, defaultAmount). */
export const PARAMS: Record<AudioParam, ParamDef> = {
  STRENGTH: { label: 'Strength', max: 720, step: 0.05, def: 0.8 },
  SPEED: { label: 'Speed', max: 20, step: 0.05, def: 1 },
  PHASE: { label: 'Offset', max: 1, step: 0.05, def: 0.25 },
  HUE: { label: 'Hue', max: 1, step: 0.05, def: 0.3 },
  OFFSET_X: { label: 'X', max: 8, step: 0.05, def: 0.3 },
  OFFSET_Y: { label: 'Y', max: 8, step: 0.05, def: 0.3 },
  OFFSET_Z: { label: 'Z', max: 8, step: 0.05, def: 0.3 },
  ROT_X: { label: 'Pitch', max: 360, step: 5, def: 20, unit: '°' },
  ROT_Y: { label: 'Yaw', max: 360, step: 5, def: 20, unit: '°' },
  ROT_Z: { label: 'Roll', max: 360, step: 5, def: 20, unit: '°' },
  SCALE: { label: 'Scale', max: 4, step: 0.05, def: 0.25 },
  VISIBLE: { label: 'Visible', max: 1, step: 0.05, def: 1 },
  BRIGHT: { label: 'Brightness', max: 1, step: 0.05, def: 1 },
  RATE: { label: 'Playback rate', max: 4, step: 0.05, def: 0.5 },
};

export const EFFECT_PARAMS: AudioParam[] = ['STRENGTH', 'SPEED', 'PHASE', 'HUE'];
const PROP_PARAMS: AudioParam[] = ['OFFSET_X', 'OFFSET_Y', 'OFFSET_Z', 'ROT_X', 'ROT_Y', 'ROT_Z', 'SCALE', 'VISIBLE', 'BRIGHT'];

export function freshMod(param: AudioParam, signal: AudioSignal = 'LEVEL'): AudioMod {
  return {
    param,
    source: 'AUTO',
    signal,
    amount: PARAMS[param].def,
    sensitivity: 1,
    attack: signal === 'BEAT' ? 0 : 0.03,
    release: signal === 'BEAT' ? 0.15 : 0.2,
    threshold: 0,
  };
}

/** Audio links of one target, for the badges and the inspector. */
export function linksOf(spec: Spec, target: TargetKind, id: string): AudioLink[] {
  return (spec.timeline?.audio ?? []).filter((a) => a.target === target && a.id === id);
}

// ---------------------------------------------------------------- meter

function Bar({ label, v, beat }: { label: string; v: number; beat?: boolean }) {
  const pct = Math.round(Math.max(0, Math.min(1, v)) * 100);
  return (
    <div class={`au-bar ${beat ? 'beat' : ''}`} title={`${label} ${pct}%`}>
      <div class="au-fill" style={{ height: `${pct}%` }} />
      <span class="au-bar-label">{label}</span>
    </div>
  );
}

export function AudioMeter({ source = 'AUTO' }: { source?: AudioSource }) {
  const m = useMeter(source);
  return (
    <div class="au-meter">
      <div class="au-bars">
        <Bar label="L" v={m.level} />
        <Bar label="B" v={m.bass} />
        <Bar label="M" v={m.mid} />
        <Bar label="H" v={m.high} />
        <Bar label="♪" v={m.beat} beat />
      </div>
      <div class="muted small au-note">
        Levels only. The game listens; no sound reaches this page.
        {source === 'SYSTEM' || source === 'AUTO' ? ` System audio: ${m.system || 'Off'}.` : ''}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- one modulation

function ModCard({
  m,
  params,
  onChange,
  onRemove,
}: {
  m: AudioMod;
  params: AudioParam[];
  onChange: (fn: (x: AudioMod) => void, gesture?: string) => void;
  onRemove: () => void;
}) {
  const p = PARAMS[m.param];
  const clamped = m.min !== undefined || m.max !== undefined;
  return (
    <div class="au-card">
      <div class="au-head">
        <span class="au-badge">♪</span>
        <SelectField
          value={m.param}
          options={params.map((x) => ({ value: x, label: PARAMS[x].label }))}
          onChange={(v) => onChange((x) => (x.param = v as AudioParam), newGesture())}
        />
        <span class="muted small">by</span>
        <SelectField
          value={m.signal}
          options={SIGNALS}
          onChange={(v) => onChange((x) => (x.signal = v as AudioSignal), newGesture())}
        />
        <button class="icon-btn danger" title="Remove this audio modulation" onClick={onRemove}>
          ×
        </button>
      </div>
      <Row label="Listen to">
        <SelectField
          value={m.source}
          options={SOURCES}
          onChange={(v) => onChange((x) => (x.source = v as AudioSource), newGesture())}
        />
      </Row>
      <NumRow
        rowLabel="Amount"
        label="±"
        title="Added at full signal; negative pulls the value down"
        value={m.amount}
        min={-p.max}
        max={p.max}
        step={p.step}
        decimals={p.step >= 1 ? 0 : 2}
        unit={p.unit}
        onChange={(v, g) => onChange((x) => (x.amount = v), g)}
      />
      <NumRow
        rowLabel="Sensitivity"
        label="×"
        title="Gain on the signal before it is clamped to 0..1"
        value={m.sensitivity}
        min={0.1}
        max={8}
        step={0.05}
        decimals={2}
        onChange={(v, g) => onChange((x) => (x.sensitivity = v), g)}
      />
      <NumRow
        rowLabel="Attack"
        label="↑"
        value={m.attack}
        min={0}
        max={2}
        step={0.01}
        decimals={2}
        unit="s"
        onChange={(v, g) => onChange((x) => (x.attack = v), g)}
      />
      <NumRow
        rowLabel="Release"
        label="↓"
        value={m.release}
        min={0}
        max={4}
        step={0.01}
        decimals={2}
        unit="s"
        onChange={(v, g) => onChange((x) => (x.release = v), g)}
      />
      <NumRow
        rowLabel="Beat step"
        label="⌐"
        title="0 = smooth. Above 0 the signal snaps on while it is over this level"
        value={m.threshold}
        min={0}
        max={1}
        step={0.01}
        decimals={2}
        onChange={(v, g) => onChange((x) => (x.threshold = v), g)}
      />
      <Row label="Clamp" title="Keep the modulated value inside a range">
        <button
          type="button"
          class={`btn small ${clamped ? 'on' : ''}`}
          onClick={() =>
            onChange((x) => {
              if (clamped) {
                delete x.min;
                delete x.max;
              } else {
                x.min = -p.max;
                x.max = p.max;
              }
            }, newGesture())
          }
        >
          {clamped ? 'On' : 'Off'}
        </button>
      </Row>
      {clamped && (
        <>
          <NumRow
            rowLabel="Min"
            label="⌊"
            value={m.min ?? -p.max}
            min={-1000}
            max={1000}
            step={p.step}
            decimals={p.step >= 1 ? 0 : 2}
            onChange={(v, g) => onChange((x) => (x.min = v), g)}
          />
          <NumRow
            rowLabel="Max"
            label="⌈"
            value={m.max ?? p.max}
            min={-1000}
            max={1000}
            step={p.step}
            decimals={p.step >= 1 ? 0 : 2}
            onChange={(v, g) => onChange((x) => (x.max = v), g)}
          />
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- lists

/** An effect's modulations; {@code locate} finds the live effect inside an edit. */
export function EffectAudio({
  mods,
  locate,
}: {
  mods: AudioMod[];
  locate: (s: Spec) => { audio?: AudioMod[] } | undefined;
}) {
  const change = (i: number, fn: (x: AudioMod) => void, gesture?: string) =>
    edit((s) => {
      const e = locate(s);
      if (e?.audio?.[i]) fn(e.audio[i]);
    }, gesture);
  return (
    <div class="au-list">
      {mods.map((m, i) => (
        <ModCard
          key={i}
          m={m}
          params={EFFECT_PARAMS}
          onChange={(fn, g) => change(i, fn, g)}
          onRemove={() =>
            edit((s) => {
              const e = locate(s);
              if (!e?.audio) return;
              e.audio.splice(i, 1);
              if (e.audio.length === 0) delete e.audio;
            })
          }
        />
      ))}
      {mods.length > 0 && <AudioMeter source={mods[0].source} />}
      <button
        type="button"
        class="btn small au-add"
        title="Make this effect react to music"
        onClick={() =>
          edit((s) => {
            const e = locate(s);
            if (!e) return;
            (e.audio ??= []).push(freshMod('STRENGTH'));
          })
        }
      >
        ♪ Add audio
      </button>
    </div>
  );
}

/** Root / group / layer value modulations (stored on the timeline). */
export function TargetAudio({ target, id }: { target: TargetKind; id: string }) {
  const spec = S.spec!;
  const all = spec.timeline?.audio ?? [];
  const mine = all.map((a, i) => ({ a, i })).filter((x) => x.a.target === target && x.a.id === id);
  const params = target === 'ROOT' ? [...PROP_PARAMS, 'RATE' as AudioParam] : PROP_PARAMS;
  const add = () =>
    edit((s) => {
      s.timeline ??= emptyTimeline();
      (s.timeline.audio ??= []).push({ ...freshMod('SCALE', 'BASS'), target, id });
    });
  return (
    <Section title={`Audio${mine.length ? ` (${mine.length})` : ''}`} open={mine.length > 0}>
      {mine.length === 0 && (
        <div class="muted small">
          Move, turn, scale, show or brighten this with music: jukeboxes, game music or (if you allow it in the game's
          audio settings) what this PC plays.
        </div>
      )}
      {mine.map(({ a, i }) => (
        <ModCard
          key={i}
          m={a}
          params={params}
          onChange={(fn, g) =>
            edit((s) => {
              const x = s.timeline?.audio?.[i];
              if (x) fn(x);
            }, g)
          }
          onRemove={() =>
            edit((s) => {
              const list = s.timeline?.audio;
              if (!list) return;
              list.splice(i, 1);
              if (list.length === 0) delete s.timeline!.audio;
            })
          }
        />
      ))}
      {mine.length > 0 && <AudioMeter source={mine[0].a.source} />}
      <button type="button" class="btn small au-add" onClick={add}>
        ♪ Add audio
      </button>
    </Section>
  );
}
