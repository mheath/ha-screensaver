import type { KioskConfig, WorldState } from './types';

export interface StreamHandlers {
  onConfig(config: KioskConfig): void;
  onState(state: WorldState): void;
  /** `reconnected` is true when this open follows an earlier successful open. */
  onConnection(connected: boolean, reconnected: boolean): void;
}

/** The parts of EventSource this module uses, so tests can substitute a fake. */
export interface EventSourceLike {
  readyState: number;
  onopen: ((ev: Event) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  addEventListener(type: string, listener: (ev: MessageEvent) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

interface StreamOptions {
  factory?: EventSourceFactory;
  retryMs?: number;
  setTimer?: (fn: () => void, ms: number) => unknown;
}

const CLOSED = 2;

/**
 * Opens the kiosk stream and keeps it open. The browser retries dropped
 * connections itself, but gives up for good after a non-200 response (such as
 * HAProxy's 502 while the backend restarts), so this replaces a CLOSED source.
 */
export function openStream(url: string, handlers: StreamHandlers, options: StreamOptions = {}): { close(): void } {
  const factory = options.factory ?? ((u: string) => new EventSource(u) as unknown as EventSourceLike);
  const retryMs = options.retryMs ?? 5000;
  const setTimer = options.setTimer ?? ((fn: () => void, ms: number) => window.setTimeout(fn, ms));
  let everOpened = false;
  let stopped = false;
  let source: EventSourceLike;

  const connect = (): void => {
    const current = factory(url);
    source = current;
    current.onopen = () => {
      const reconnected = everOpened;
      everOpened = true;
      handlers.onConnection(true, reconnected);
    };
    current.onerror = () => {
      handlers.onConnection(false, false);
      if (current.readyState === CLOSED && !stopped) {
        current.close();
        setTimer(connect, retryMs);
      }
    };
    current.addEventListener('config', (e) => handlers.onConfig(JSON.parse(e.data) as KioskConfig));
    current.addEventListener('state', (e) => handlers.onState(JSON.parse(e.data) as WorldState));
  };

  connect();
  return {
    close() {
      stopped = true;
      source.close();
    },
  };
}
