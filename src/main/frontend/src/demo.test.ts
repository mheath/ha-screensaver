import { describe, expect, it } from 'vitest';
import { DEMO_CONFIG, demoState } from './demo';
import { addDays, eventsOn } from './scenes/calendar/model';

describe('demo data', () => {
  it('has a value for every configured snake', () => {
    const ids = [...DEMO_CONFIG.snakes.pinned, ...DEMO_CONFIG.snakes.guests].map((s) => s.id);
    expect(demoState(0).snakes.map((s) => s.id).sort()).toEqual([...ids].sort());
  });

  it('keeps values within each snake range', () => {
    const defs = [...DEMO_CONFIG.snakes.pinned, ...DEMO_CONFIG.snakes.guests];
    for (const time of [0, 1234, 98765, 1_000_000]) {
      for (const value of demoState(time).snakes) {
        if (value.value === null) continue;
        const def = defs.find((d) => d.id === value.id)!;
        expect(value.value).toBeGreaterThanOrEqual(def.min);
        expect(value.value).toBeLessThanOrEqual(def.max);
      }
    }
  });

  it('includes an unavailable snake to show the frozen look', () => {
    expect(demoState(0).snakes.some((s) => !s.available)).toBe(true);
  });
});

describe('demo calendar', () => {
  it('only uses configured calendars', () => {
    const ids = DEMO_CONFIG.calendar.calendars.map((c) => c.id);
    for (const event of demoState(Date.now()).calendar.events) {
      expect(ids).toContain(event.calendar);
    }
  });

  it('has events today and tomorrow, including an overlap and an all-day event', () => {
    const noon = new Date(2026, 9, 9, 12, 0).getTime();
    const events = demoState(noon).calendar.events;
    const today = eventsOn(events, new Date(noon));
    const tomorrow = eventsOn(events, addDays(new Date(noon), 1));
    expect(today.allDay.length).toBeGreaterThan(0);
    expect(today.timed.length).toBeGreaterThanOrEqual(4);
    expect(tomorrow.timed.length).toBeGreaterThan(0);
  });

  it('lists the calendar scene', () => {
    expect(DEMO_CONFIG.scenes.map((s) => s.type)).toContain('calendar');
  });
});
