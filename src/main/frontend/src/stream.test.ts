import { describe, expect, it } from 'vitest';
import { openStream, type EventSourceLike, type StreamHandlers } from './stream';

class FakeSource implements EventSourceLike {
  readyState = 0;
  onopen: ((ev: Event) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  closed = false;
  listeners = new Map<string, (ev: MessageEvent) => void>();
  addEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    this.listeners.set(type, listener);
  }
  close(): void {
    this.closed = true;
    this.readyState = 2;
  }
  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }
  fail(readyState: number): void {
    this.readyState = readyState;
    this.onerror?.(new Event('error'));
  }
  emit(type: string, data: unknown): void {
    this.listeners.get(type)?.({ data: JSON.stringify(data) } as MessageEvent);
  }
}

function setup() {
  const sources: FakeSource[] = [];
  const timers: (() => void)[] = [];
  const connections: [boolean, boolean][] = [];
  const configs: unknown[] = [];
  const states: unknown[] = [];
  const handlers: StreamHandlers = {
    onConfig: (c) => configs.push(c),
    onState: (s) => states.push(s),
    onConnection: (connected, reconnected) => connections.push([connected, reconnected]),
  };
  const stream = openStream('/api/stream?kiosk=x', handlers, {
    factory: () => {
      const source = new FakeSource();
      sources.push(source);
      return source;
    },
    setTimer: (fn) => timers.push(fn),
  });
  return { sources, timers, connections, configs, states, stream };
}

describe('openStream', () => {
  it('reports the first open as not a reconnect', () => {
    const t = setup();
    t.sources[0].open();
    expect(t.connections).toEqual([[true, false]]);
  });

  it('reports a reopen after an error as a reconnect', () => {
    const t = setup();
    t.sources[0].open();
    t.sources[0].fail(0); // CONNECTING: the browser retries on its own
    t.sources[0].open();
    expect(t.connections).toEqual([[true, false], [false, false], [true, true]]);
    expect(t.sources).toHaveLength(1);
  });

  it('opens a new EventSource when the browser gives up (e.g. HTTP 502)', () => {
    const t = setup();
    t.sources[0].open();
    t.sources[0].fail(2); // CLOSED: the browser will not retry
    expect(t.timers).toHaveLength(1);
    t.timers[0]();
    expect(t.sources).toHaveLength(2);
    t.sources[1].open();
    expect(t.connections.at(-1)).toEqual([true, true]);
  });

  it('keeps retrying while the backend stays down', () => {
    const t = setup();
    t.sources[0].fail(2);
    t.timers[0]();
    t.sources[1].fail(2);
    t.timers[1]();
    expect(t.sources).toHaveLength(3);
  });

  it('parses config and state events', () => {
    const t = setup();
    t.sources[0].emit('config', { kiosk: 'x' });
    t.sources[0].emit('state', { haConnected: true, snakes: [] });
    expect(t.configs).toEqual([{ kiosk: 'x' }]);
    expect(t.states).toEqual([{ haConnected: true, snakes: [] }]);
  });

  it('stops retrying after close', () => {
    const t = setup();
    t.stream.close();
    t.sources[0].fail(2);
    expect(t.timers).toHaveLength(0);
  });
});
