import type { StreamHandlers } from './stream';
import type { CalendarEvent, KioskConfig, WorldState } from './types';

/** Canned config for `?demo`, for working on scenes without a backend. */
export const DEMO_CONFIG: KioskConfig = {
  kiosk: 'demo',
  scenes: [
    { type: 'clock', weight: 1, durationMs: 15_000 },
    { type: 'snakes', weight: 2, durationMs: 30_000 },
    { type: 'calendar', weight: 2, durationMs: 20_000 },
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
  calendar: {
    allDayUntil: '18:00',
    calendars: [
      { id: 'family', label: 'Family', color: '#3b82f6', textColor: '#ffffff' },
      { id: 'school', label: 'School', color: '#f97316', textColor: '#ffffff' },
      { id: 'work', label: 'Work', color: '#22c55e', textColor: '#ffffff' },
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
    calendar: { events: demoCalendar(timeMs) },
  };
}

/** Events placed around the current hour, so `?demo&scene=calendar` always has something to show. */
export function demoCalendar(timeMs: number): CalendarEvent[] {
  const now = new Date(timeMs);
  const pad = (n: number) => String(n).padStart(2, '0');
  const date = (dayOffset: number) => {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + dayOffset);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  };
  /** Minutes from the start of the current hour today. */
  const fromNow = (minutes: number) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), minutes).toISOString();
  /** A clock time tomorrow. */
  const tomorrowAt = (hour: number, minute = 0) =>
    new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, hour, minute).toISOString();
  const timed = (title: string, start: string, end: string, calendar: string): CalendarEvent =>
    ({ title, start, end, allDay: false, calendar });
  return [
    { title: "Grandma's birthday", start: date(0), end: date(1), allDay: true, calendar: 'family' },
    timed('Dentist', fromNow(-180), fromNow(-90), 'family'),
    timed('Lunch with Dave', fromNow(-60), fromNow(30), 'work'),
    timed('Soccer practice', fromNow(60), fromNow(180), 'school'),
    timed('Piano lesson', fromNow(90), fromNow(150), 'family'),
    timed('Book club', fromNow(240), fromNow(330), 'family'),
    timed('Soccer game', tomorrowAt(8), tomorrowAt(9, 30), 'school'),
    timed("Farmers' market", tomorrowAt(10), tomorrowAt(11, 30), 'family'),
    timed('Dinner at the Hendersons', tomorrowAt(17), tomorrowAt(18, 45), 'family'),
  ];
}

export function startDemo(handlers: StreamHandlers): void {
  handlers.onConnection(true, false);
  handlers.onConfig(DEMO_CONFIG);
  handlers.onState(demoState(Date.now()));
  window.setInterval(() => handlers.onState(demoState(Date.now())), 1000);
}
