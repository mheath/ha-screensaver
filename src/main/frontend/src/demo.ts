import type { StreamHandlers } from './stream';
import type { KioskConfig, WorldState } from './types';

/** Canned config for `?demo`, for working on scenes without a backend. */
export const DEMO_CONFIG: KioskConfig = {
  kiosk: 'demo',
  scenes: [
    { type: 'clock', weight: 1, durationMs: 15_000 },
    { type: 'snakes', weight: 2, durationMs: 30_000 },
  ],
  clock: { use24Hour: false },
  snakes: {
    guestCount: 2,
    minLength: 4,
    maxLength: 30,
    minSpeed: 2,
    maxSpeed: 20,
    pinned: [
      { id: 'internet-download', label: 'Internet download bandwidth', color: 'light-green', unit: null, precision: 1, min: 0, max: 1000 },
      { id: 'house-power', label: 'House power consumption', color: 'light-red', unit: null, precision: 0, min: 0, max: 12000 },
    ],
    guests: [
      { id: 'outdoor-wind', label: 'Wind speed', color: null, unit: null, precision: 1, min: 0, max: 40 },
      { id: 'people-home', label: 'People home', color: null, unit: null, precision: 0, min: 0, max: 6 },
    ],
  },
};

/** Slowly varying values; `people-home` is unavailable to show the frozen look. */
export function demoState(timeMs: number): WorldState {
  const wave = (periodSeconds: number, phase: number) =>
    (Math.sin((timeMs / 1000) * ((2 * Math.PI) / periodSeconds) + phase) + 1) / 2;
  return {
    haConnected: true,
    snakes: [
      { id: 'internet-download', value: 1000 * wave(20, 0), unit: 'Mbit/s', available: true },
      { id: 'house-power', value: 12000 * wave(45, 1), unit: 'W', available: true },
      { id: 'outdoor-wind', value: 40 * wave(13, 2), unit: 'mph', available: true },
      { id: 'people-home', value: null, unit: null, available: false },
    ],
  };
}

export function startDemo(handlers: StreamHandlers): void {
  handlers.onConnection(true, false);
  handlers.onConfig(DEMO_CONFIG);
  handlers.onState(demoState(Date.now()));
  window.setInterval(() => handlers.onState(demoState(Date.now())), 1000);
}
