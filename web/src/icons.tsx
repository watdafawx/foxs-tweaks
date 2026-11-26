// A kind's icon: the picture the game drew (protocol 5) or, until it arrives or when there is none, the letter badge.

import { useEffect, useRef } from 'preact/hooks';
import type { Kind } from './protocol';
import { ICONS, S, iconKey, unwantIcon, wantIcon } from './store';
import type { IconKind } from './store';

const DRAWN: ReadonlySet<string> = new Set(['BLOCK', 'ENTITY', 'PARTICLE']);

interface Watched {
  kind: IconKind;
  id: string;
}

const watched = new WeakMap<Element, Watched>();
let observer: IntersectionObserver | null = null;

/** One observer for every tile: a tile asks the game for its icon only while it is on screen. */
function observe(el: Element, w: Watched): void {
  if (typeof IntersectionObserver === 'undefined') {
    wantIcon(w.kind, w.id);
    return;
  }
  observer ??= new IntersectionObserver((entries) => {
    for (const e of entries) {
      const t = watched.get(e.target);
      if (!t) continue;
      if (e.isIntersecting) wantIcon(t.kind, t.id);
      else unwantIcon(t.kind, t.id);
    }
  });
  watched.set(el, w);
  observer.observe(el);
}

function unobserve(el: Element): void {
  const w = watched.get(el);
  observer?.unobserve(el);
  watched.delete(el);
  if (w) unwantIcon(w.kind, w.id);
}

/**
 * @param px display size; the sheet's cells are drawn larger and scaled down here.
 * @param badge the letter shown while there is no picture.
 */
export function Icon({ kind, id, px, badge }: { kind: Kind; id: string; px: number; badge: string }) {
  const ref = useRef<HTMLSpanElement>(null);
  const drawn = DRAWN.has(kind) && !!S.iconCaps?.available;
  const cell = drawn ? ICONS.get(iconKey(kind as IconKind, id)) : undefined;

  useEffect(() => {
    const el = ref.current;
    if (!drawn || !el) return;
    observe(el, { kind: kind as IconKind, id });
    return () => unobserve(el);
  }, [drawn, kind, id]);

  if (cell) {
    const k = px / cell.size;
    return (
      <span
        ref={ref}
        class="kind-icon"
        style={{
          width: `${px}px`,
          height: `${px}px`,
          backgroundImage: `url(${cell.sheet})`,
          backgroundSize: `${cell.width * k}px ${cell.height * k}px`,
          backgroundPosition: `${-cell.col * px}px ${-cell.row * px}px`,
        }}
      />
    );
  }
  return (
    <span
      ref={ref}
      class={`kind kind-${kind.toLowerCase()}`}
      style={px === 16 ? undefined : { width: `${px}px`, height: `${px}px`, fontSize: `${Math.round(px * 0.6)}px` }}
    >
      {badge}
    </span>
  );
}
