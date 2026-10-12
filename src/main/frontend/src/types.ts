import type { PaletteName } from './palette';

/** Matches net.toadhead.ha.screensaver.config.SceneType ids. */
export type SceneType = 'clock' | 'snakes';

export interface SceneConfig {
  type: SceneType;
  weight: number;
  durationMs: number;
}

export interface SnakeDef {
  id: string;
  label: string;
  color: PaletteName | null;
  /** Overrides the entity's unit when set. */
  unit: string | null;
  precision: number;
  min: number;
  max: number;
}

export interface SnakeOptions {
  guestCount: number;
  minLength: number;
  maxLength: number;
  minSpeed: number;
  maxSpeed: number;
  pinned: SnakeDef[];
  guests: SnakeDef[];
}

/** The stream's `config` event (net.toadhead.ha.screensaver.kiosk.KioskConfig). */
export interface KioskConfig {
  kiosk: string;
  scenes: SceneConfig[];
  clock: { use24Hour: boolean };
  snakes: SnakeOptions;
}

export interface SnakeValue {
  id: string;
  value: number | null;
  unit: string | null;
  available: boolean;
}

/** The stream's `state` event (net.toadhead.ha.screensaver.stream.WorldState). */
export interface WorldState {
  haConnected: boolean;
  snakes: SnakeValue[];
}

/** One calendar event (net.toadhead.ha.screensaver.calendar.CalendarEvent). */
export interface CalendarEvent {
  title: string;
  /** `2026-10-09` for all-day events, otherwise an ISO date-time with offset. */
  start: string;
  /** Exclusive, like `start`. */
  end: string;
  allDay: boolean;
  calendar: string;
}
