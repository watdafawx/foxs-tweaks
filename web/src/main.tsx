import { render } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import './styles.css';
import { ContentBrowser, deleteSelection, duplicateSelection } from './browser';
import { Inspector } from './inspector';
import { Outliner, layerName } from './outliner';
import { S, cancel, connect, redo, save, undo, useApp } from './store';

function isTyping(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null;
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
}

function onKey(e: KeyboardEvent): void {
  if (S.status !== 'open' || !S.spec) return;
  const mod = e.ctrlKey || e.metaKey;
  const key = e.key.toLowerCase();
  if (mod && key === 's') {
    e.preventDefault();
    (document.activeElement as HTMLElement | null)?.blur();
    setTimeout(save, 0); // after the blur commits a typed value
    return;
  }
  if (isTyping(e)) return;
  if (mod && key === 'z') {
    e.preventDefault();
    if (e.shiftKey) redo();
    else undo();
  } else if (mod && key === 'y') {
    e.preventDefault();
    redo();
  } else if (mod && key === 'd') {
    e.preventDefault();
    duplicateSelection();
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    e.preventDefault();
    deleteSelection();
  }
}

function Header() {
  const spec = S.spec;
  const [confirmCancel, setConfirmCancel] = useState(false);
  useEffect(() => {
    if (!confirmCancel) return;
    const t = setTimeout(() => setConfirmCancel(false), 3000);
    return () => clearTimeout(t);
  }, [confirmCancel]);
  const live = S.status === 'open';
  return (
    <header class="topbar">
      <div class="brand">
        <span class="logo">◈</span>
        <span class="brand-name">Hologram Studio</span>
        {spec && (
          <span class="crumb mono">
            {spec.id} <span class="muted">@ {spec.pos.join(' ')}</span>
          </span>
        )}
      </div>
      <div class={`status status-${S.status}`}>
        <span class="dot" />
        {S.status === 'open'
          ? 'Live in game'
          : S.status === 'connecting'
            ? 'Connecting…'
            : S.status === 'lost'
              ? 'Reconnecting…'
              : 'Session ended'}
      </div>
      <div class="actions">
        <button class="btn" disabled={!live || !S.canUndo} onClick={undo} title="Undo (Ctrl+Z)">
          Undo
        </button>
        <button class="btn" disabled={!live || !S.canRedo} onClick={redo} title="Redo (Ctrl+Shift+Z)">
          Redo
        </button>
        <span class="sep" />
        <button
          class={`btn ${confirmCancel ? 'danger' : ''}`}
          disabled={!live}
          onClick={() => (confirmCancel ? cancel() : setConfirmCancel(true))}
          title="Discard every change since the editor opened"
        >
          {confirmCancel ? 'Discard changes?' : 'Cancel'}
        </button>
        <button class="btn primary" disabled={!live} onClick={save} title="Save to the world (Ctrl+S)">
          Save
        </button>
      </div>
    </header>
  );
}

function Timeline() {
  const [open, setOpen] = useState(true);
  const spec = S.spec!;
  return (
    <div class={`timeline ${open ? 'open' : ''}`}>
      <div class="panel-head" onClick={() => setOpen(!open)}>
        <span>
          <span class="caret">{open ? '▾' : '▸'}</span> Timeline
        </span>
        <span class="chip">coming in phase 2</span>
      </div>
      {open && (
        <div class="timeline-body">
          <div class="ruler" />
          {spec.layers.slice(0, 4).map((l, i) => (
            <div key={i} class="track">
              <span class="track-name">{layerName(l)}</span>
              <span class="track-lane" />
            </div>
          ))}
          <div class="timeline-note muted small">
            Keyframes, clips and audio sync land here in phase 2. Effects in the inspector already animate in game.
          </div>
        </div>
      )}
    </div>
  );
}

function Toasts() {
  return (
    <div class="toasts">
      {S.toasts.map((t) => (
        <div key={t.id} class={`toast ${t.kind}`}>
          {t.text}
        </div>
      ))}
    </div>
  );
}

function Overlay() {
  if (S.status === 'closed') {
    const saved = S.closedReason === 'saved';
    return (
      <div class="overlay">
        <div class="overlay-card">
          <div class="overlay-title">{saved ? 'Saved' : 'Session ended'}</div>
          <div class="muted">
            {saved ? 'The hologram was saved to the world.' : `The in-game editor closed (${S.closedReason}).`} This link
            no longer works; press Web in the editor for a new one. You can close this tab.
          </div>
        </div>
      </div>
    );
  }
  if (!S.spec || !S.registries) {
    return (
      <div class="overlay">
        <div class="overlay-card">
          <div class="overlay-title">{S.status === 'lost' ? 'Lost the game' : 'Connecting to the game…'}</div>
          <div class="muted">
            {S.status === 'lost'
              ? 'Retrying. If the editor was closed in game, press Web there again for a new link.'
              : 'Keep the hologram editor open in game.'}
          </div>
        </div>
      </div>
    );
  }
  return null;
}

function App() {
  useApp();
  useEffect(() => {
    connect();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  const ready = S.spec && S.registries;
  return (
    <div class={`app ${S.status !== 'open' ? 'offline' : ''}`}>
      <Header />
      {ready && (
        <main class="workspace">
          <aside class="left">
            <Outliner />
          </aside>
          <section class="center">
            <ContentBrowser />
          </section>
          <aside class="right">
            <Inspector />
          </aside>
          <Timeline />
        </main>
      )}
      <Overlay />
      <Toasts />
      <footer class="statusbar muted small">
        <span>Fox's Tweaks {S.modVersion}</span>
        <span>Minecraft {S.minecraft}</span>
        <span class="grow" />
        <span>Drag a label to scrub · Shift fine · Ctrl coarse · Ctrl+Z / Ctrl+Shift+Z · Ctrl+S save · Del delete · Ctrl+D duplicate</span>
      </footer>
    </div>
  );
}

render(<App />, document.getElementById('app')!);
