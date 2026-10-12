import type { CalendarEvent } from '../../types';
import { formatDigital } from '../clock/layout';

/** The timeline never shows fewer hours than this. */
export const MIN_SPAN_HOURS = 6;
/** Shorter events put their title and time on one line. */
export const SHORT_EVENT_MINUTES = 40;

export type Day = 'today' | 'tomorrow';

/** A timed event clipped to one day. */
export interface TimedEvent {
  event: CalendarEvent;
  start: Date;
  end: Date;
}

export interface DayEvents {
  allDay: CalendarEvent[];
  timed: TimedEvent[];
}

/** A timed event placed in one of `laneCount` side-by-side lanes. */
export interface PlacedEvent extends TimedEvent {
  lane: number;
  laneCount: number;
}

export function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/** Calendar-day arithmetic, so results stay on midnight across daylight-saving changes. */
export function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
}

/** All-day dates (`2026-10-09`) are local midnights; timed values are instants. */
function parseTime(value: string, allDay: boolean): Date {
  if (!allDay) return new Date(value);
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day);
}

/** The events touching the local day that contains `day`; timed ones clipped to it, in start order. */
export function eventsOn(events: CalendarEvent[], day: Date): DayEvents {
  const dayStart = startOfDay(day);
  const dayEnd = addDays(dayStart, 1);
  const allDay: CalendarEvent[] = [];
  const timed: TimedEvent[] = [];
  for (const event of events) {
    const start = parseTime(event.start, event.allDay);
    const end = parseTime(event.end, event.allDay);
    if (!(start < dayEnd && end > dayStart)) continue;
    if (event.allDay) {
      allDay.push(event);
    } else {
      timed.push({ event, start: start < dayStart ? dayStart : start, end: end > dayEnd ? dayEnd : end });
    }
  }
  timed.sort((a, b) => a.start.getTime() - b.start.getTime() || a.event.title.localeCompare(b.event.title));
  return { allDay, timed };
}

/** `HH:mm` → minutes after midnight. */
function minutesOf(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

/**
 * Today while it has a timed event that hasn't ended, or an all-day event before
 * `allDayUntil`; otherwise tomorrow if it has any events; otherwise nothing.
 */
export function chooseDay(events: CalendarEvent[], now: Date, allDayUntil: string): Day | null {
  const today = eventsOn(events, now);
  if (today.timed.some((t) => t.end > now)) return 'today';
  if (today.allDay.length > 0 && now.getHours() * 60 + now.getMinutes() < minutesOf(allDayUntil)) return 'today';
  const tomorrow = eventsOn(events, addDays(now, 1));
  return tomorrow.allDay.length + tomorrow.timed.length > 0 ? 'tomorrow' : null;
}

/** Wall-clock hours since `dayStart`; the next midnight counts as 24. */
export function hourOfDay(date: Date, dayStart: Date): number {
  if (date >= addDays(dayStart, 1)) return 24;
  return date.getHours() + date.getMinutes() / 60 + date.getSeconds() / 3600;
}

/** Whole hours covering every event, at least MIN_SPAN_HOURS long and within the day. `timed` must not be empty. */
export function hourSpan(timed: TimedEvent[], dayStart: Date): { startHour: number; endHour: number } {
  let startHour = Math.floor(Math.min(...timed.map((t) => hourOfDay(t.start, dayStart))));
  let endHour = Math.ceil(Math.max(...timed.map((t) => hourOfDay(t.end, dayStart))));
  if (endHour - startHour < MIN_SPAN_HOURS) {
    endHour = Math.min(24, startHour + MIN_SPAN_HOURS);
    startHour = Math.max(0, endHour - MIN_SPAN_HOURS);
  }
  return { startHour, endHour };
}

/**
 * Splits overlapping events into side-by-side lanes. Each event takes the first
 * free lane; every event in an overlapping group shares the group's lane count.
 * Events that only share an endpoint don't overlap.
 */
export function packLanes(timed: TimedEvent[]): PlacedEvent[] {
  const sorted = [...timed].sort((a, b) => a.start.getTime() - b.start.getTime() || b.end.getTime() - a.end.getTime());
  const placed: PlacedEvent[] = [];
  let group: PlacedEvent[] = [];
  let laneEnds: number[] = [];
  let groupEnd = -Infinity;
  const closeGroup = () => {
    for (const member of group) member.laneCount = laneEnds.length;
    group = [];
    laneEnds = [];
  };
  for (const event of sorted) {
    const start = event.start.getTime();
    const end = event.end.getTime();
    if (start >= groupEnd) closeGroup();
    let lane = laneEnds.findIndex((laneEnd) => laneEnd <= start);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(end);
    } else {
      laneEnds[lane] = end;
    }
    const member: PlacedEvent = { ...event, lane, laneCount: 1 };
    group.push(member);
    placed.push(member);
    groupEnd = group.length === 1 ? end : Math.max(groupEnd, end);
  }
  closeGroup();
  return placed;
}

export function isShort(event: TimedEvent): boolean {
  return event.end.getTime() - event.start.getTime() < SHORT_EVENT_MINUTES * 60_000;
}

/** `3:00 – 5:00 PM`, `11:00 AM – 1:00 PM`, or `15:00 – 17:00`. */
export function formatRange(start: Date, end: Date, use24Hour: boolean): string {
  const from = formatDigital(start, use24Hour);
  const to = formatDigital(end, use24Hour);
  if (use24Hour) return `${from.time} – ${to.time}`;
  return from.suffix === to.suffix
    ? `${from.time} – ${to.time} ${to.suffix}`
    : `${from.time} ${from.suffix} – ${to.time} ${to.suffix}`;
}

/** Label for an hour line from 0 to 24. */
export function hourLabel(hour: number, use24Hour: boolean): string {
  const h = hour % 24;
  if (use24Hour) return `${String(h).padStart(2, '0')}:00`;
  return `${h % 12 === 0 ? 12 : h % 12} ${h < 12 ? 'AM' : 'PM'}`;
}
