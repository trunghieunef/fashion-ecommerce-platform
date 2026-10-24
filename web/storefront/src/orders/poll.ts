// TASK:WEB-03: read one order and keep it fresh while the server is still working on it (spec §5, 14 §3 points 6–7).
// Polls only while processing_status is PROCESSING, after retry_after_seconds; a hidden tab pauses and fetches again when
// it shows; network errors back off up to 30 s; after two minutes it stops (a MANUAL saga stays PROCESSING for ever).
import { useCallback, useEffect, useState } from 'react';

import { getOrder, type Order } from '../checkout/api';
import { HttpError } from '../http';

const LIMIT_MS = 120_000;
const DEFAULT_S = 3;
const CAP_MS = 30_000;

export type Polled = { state: 'loading' | 'ready' | 'missing' | 'error'; order: Order | null; stalled: boolean; check(): void };

/** `member` null: the session is still being restored, nothing is read yet. */
export function usePolledOrder(orderNo: string, member: boolean | null): Polled {
  const [state, setState] = useState<Polled['state']>('loading');
  const [order, setOrder] = useState<Order | null>(null);
  const [stalled, setStalled] = useState(false);
  const [round, setRound] = useState(0);

  useEffect(() => {
    if (member === null) return;
    const started = Date.now();
    let stopped = false;
    let paused = false;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setStalled(false);

    const later = (ms: number) => {
      if (Date.now() - started + ms > LIMIT_MS) { setStalled(true); return; }
      timer = setTimeout(tick, ms);
    };
    async function tick() {
      if (document.hidden) { paused = true; return; }
      try {
        const next = await getOrder(orderNo, member!);
        if (stopped) return;
        failures = 0;
        setOrder(next);
        setState('ready');
        if (next.processing_status === 'PROCESSING') later((next.retry_after_seconds ?? DEFAULT_S) * 1000);
      } catch (error) {
        if (stopped) return;
        if (error instanceof HttpError && error.status === 404) { setState('missing'); return; }
        setState((s) => (s === 'ready' ? s : 'error'));
        failures += 1;
        later(Math.min(DEFAULT_S * 1000 * 2 ** (failures - 1), CAP_MS));
      }
    }
    const onVisible = () => {
      if (document.hidden || !paused || stopped) return;
      paused = false;
      void tick();
    };
    document.addEventListener('visibilitychange', onVisible);
    void tick();
    return () => { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [orderNo, member, round]);

  const check = useCallback(() => setRound((r) => r + 1), []);
  return { state, order, stalled, check };
}
