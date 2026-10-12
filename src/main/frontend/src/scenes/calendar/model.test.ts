import { describe, expect, it } from 'vitest';
import type { CalendarEvent } from '../../types';
import {
  addDays, chooseDay, eventsOn, formatRange, hourLabel, hourSpan, isShort, packLanes, startOfDay, type TimedEvent,
} from './model';

/** Local time on Friday, October 9 2026 (`day` shifts the date). */
const at = (hour: number, minute = 0, day = 0) => new Date(2026, 9, 9 + day, hour, minute);
const FRIDAY = startOfDay(at(0));

const timed = (title: string, start: Date, end: Date, calendar = 'family'): CalendarEvent => ({
  title, start: start.toISOString(), end: end.toISOString(), allDay: false, calendar,
});
const allDay = (title: string, start: string, end: string): CalendarEvent => ({
  title, start, end, allDay: true, calendar: 'family',
});
const clipped = (start: Date, end: Date, title = 'x'): TimedEvent => ({ event: timed(title, start, end), start, end });

describe('eventsOn', () => {
  it('sorts timed events by start', () => {
    const day = eventsOn([timed('Late', at(18), at(19)), timed('Early', at(9), at(10))], FRIDAY);
    expect(day.timed.map((t) => t.event.title)).toEqual(['Early', 'Late']);
  });

  it('shows a multi-day all-day event on each day it covers, but not its exclusive end date', () => {
    const trip = allDay('Trip', '2026-10-08', '2026-10-11');
    expect(eventsOn([trip], at(12, 0, -1)).allDay).toHaveLength(1);
    expect(eventsOn([trip], at(12)).allDay).toHaveLength(1);
    expect(eventsOn([trip], at(12, 0, 1)).allDay).toHaveLength(1);
    expect(eventsOn([trip], at(12, 0, 2)).allDay).toHaveLength(0);
  });

  it('clips a timed event that crosses midnight to each day', () => {
    const party = timed('Party', at(22), at(2, 0, 1));
    const friday = eventsOn([party], FRIDAY).timed[0];
    const saturday = eventsOn([party], addDays(FRIDAY, 1)).timed[0];
    expect([friday.start, friday.end]).toEqual([at(22), at(0, 0, 1)]);
    expect([saturday.start, saturday.end]).toEqual([at(0, 0, 1), at(2, 0, 1)]);
  });

  it('keeps an event that ends exactly at midnight off the next day', () => {
    const late = timed('Late show', at(22), at(0, 0, 1));
    expect(eventsOn([late], FRIDAY).timed).toHaveLength(1);
    expect(eventsOn([late], addDays(FRIDAY, 1)).timed).toHaveLength(0);
  });
});

describe('chooseDay', () => {
  const tomorrowSoccer = timed('Soccer', at(8, 0, 1), at(9, 0, 1));

  it('shows today while a timed event is still to come', () => {
    expect(chooseDay([timed('Book club', at(18), at(19)), tomorrowSoccer], at(14), '18:00')).toBe('today');
  });

  it('shows today while a timed event is in progress', () => {
    expect(chooseDay([timed('Lunch', at(12), at(13))], at(12, 30), '18:00')).toBe('today');
  });

  it('flips to tomorrow when the last timed event ends exactly now', () => {
    expect(chooseDay([timed('Lunch', at(12), at(13)), tomorrowSoccer], at(13), '18:00')).toBe('tomorrow');
  });

  it('keeps today for an all-day event until the cutoff, then flips', () => {
    const events = [allDay('Birthday', '2026-10-09', '2026-10-10'), tomorrowSoccer];
    expect(chooseDay(events, at(17, 59), '18:00')).toBe('today');
    expect(chooseDay(events, at(18), '18:00')).toBe('tomorrow');
  });

  it('keeps today for a timed event from yesterday that spills over midnight', () => {
    const overnight = timed('Overnight shift', at(22, 0, -1), at(6));
    expect(chooseDay([overnight, tomorrowSoccer], at(3), '18:00')).toBe('today');
    expect(chooseDay([overnight, tomorrowSoccer], at(6), '18:00')).toBe('tomorrow');
  });

  it('shows tomorrow when it has only all-day events', () => {
    expect(chooseDay([allDay('Holiday', '2026-10-10', '2026-10-11')], at(20), '18:00')).toBe('tomorrow');
  });

  it('is null when nothing is left today and tomorrow is empty', () => {
    expect(chooseDay([timed('Lunch', at(12), at(13))], at(20), '18:00')).toBeNull();
    expect(chooseDay([], at(9), '18:00')).toBeNull();
  });
});

describe('hourSpan', () => {
  it('covers every event, rounded out to whole hours', () => {
    const span = hourSpan([clipped(at(9, 30), at(13, 15)), clipped(at(18), at(19, 30))], FRIDAY);
    expect(span).toEqual({ startHour: 9, endHour: 20 });
  });

  it('stretches a short span to six hours', () => {
    expect(hourSpan([clipped(at(10), at(11))], FRIDAY)).toEqual({ startHour: 10, endHour: 16 });
  });

  it('stretches earlier when the end reaches midnight', () => {
    expect(hourSpan([clipped(at(22), at(23, 30))], FRIDAY)).toEqual({ startHour: 18, endHour: 24 });
  });

  it('treats an event clipped at midnight as ending at hour 24', () => {
    expect(hourSpan([clipped(at(17), at(0, 0, 1))], FRIDAY)).toEqual({ startHour: 17, endHour: 24 });
  });
});

describe('packLanes', () => {
  const lanes = (placed: ReturnType<typeof packLanes>) =>
    placed.map((p) => `${p.event.title}:${p.lane}/${p.laneCount}`);

  it('gives separate events the full width', () => {
    expect(lanes(packLanes([clipped(at(9), at(10), 'A'), clipped(at(11), at(12), 'B')]))).toEqual(['A:0/1', 'B:0/1']);
  });

  it('puts overlapping events side by side', () => {
    expect(lanes(packLanes([clipped(at(15), at(17), 'Soccer'), clipped(at(15, 30), at(16, 30), 'Piano')])))
      .toEqual(['Soccer:0/2', 'Piano:1/2']);
  });

  it('reuses a lane once its event ends, across a chain of overlaps', () => {
    const placed = packLanes([clipped(at(13), at(15), 'A'), clipped(at(14), at(16), 'B'), clipped(at(15, 30), at(17), 'C')]);
    expect(lanes(placed)).toEqual(['A:0/2', 'B:1/2', 'C:0/2']);
  });

  it('uses three lanes when three overlap at once', () => {
    const placed = packLanes([clipped(at(9), at(12), 'A'), clipped(at(10), at(11), 'B'), clipped(at(10, 30), at(11, 30), 'C')]);
    expect(lanes(placed)).toEqual(['A:0/3', 'B:1/3', 'C:2/3']);
  });

  it('does not treat a shared endpoint as an overlap', () => {
    expect(lanes(packLanes([clipped(at(9), at(10), 'A'), clipped(at(10), at(11), 'B')]))).toEqual(['A:0/1', 'B:0/1']);
  });
});

describe('isShort', () => {
  it('is true under 40 minutes', () => {
    expect(isShort(clipped(at(9), at(9, 39)))).toBe(true);
    expect(isShort(clipped(at(9), at(9, 40)))).toBe(false);
  });
});

describe('formatRange', () => {
  it('shares the suffix when both ends are on the same half of the day', () => {
    expect(formatRange(at(15), at(17), false)).toBe('3:00 – 5:00 PM');
  });

  it('shows both suffixes across noon', () => {
    expect(formatRange(at(11), at(13), false)).toBe('11:00 AM – 1:00 PM');
  });

  it('uses 24-hour times when asked', () => {
    expect(formatRange(at(15), at(17, 30), true)).toBe('15:00 – 17:30');
  });
});

describe('hourLabel', () => {
  it('labels 12-hour clocks', () => {
    expect([0, 9, 12, 15, 24].map((h) => hourLabel(h, false))).toEqual(['12 AM', '9 AM', '12 PM', '3 PM', '12 AM']);
  });

  it('labels 24-hour clocks', () => {
    expect([0, 9, 15, 24].map((h) => hourLabel(h, true))).toEqual(['00:00', '09:00', '15:00', '00:00']);
  });
});
