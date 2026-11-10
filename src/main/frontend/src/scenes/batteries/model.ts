import type { LowBattery } from '../../types';

/** Most batteries drawn on screen; any beyond this are counted as "and N more". */
export const MAX_SHOWN = 10;

/** Most batteries drawn in one row. */
export const PER_ROW = 5;

/** Smallest fill drawn for a low battery, so an empty one still shows a sliver. */
const MIN_FILL = 0.03;

export interface Layout {
  shown: number;
  rows: number;
  more: number;
}

/** How many batteries to draw, how many rows they take, and how many are left out. */
export function layout(count: number): Layout {
  const shown = Math.min(count, MAX_SHOWN);
  return { shown, rows: Math.ceil(shown / PER_ROW), more: count - shown };
}

/** Text shown on a battery: DEAD, LOW for binary sensors, or the level as a percent. */
export function batteryLabel(battery: LowBattery): string {
  if (battery.status === 'dead') {
    return 'DEAD';
  }
  return battery.level === null ? 'LOW' : `${battery.level}%`;
}

/** Share of the battery outline to fill, from 0 to 1. Dead batteries have no fill. */
export function fillFraction(battery: LowBattery): number {
  if (battery.status === 'dead') {
    return 0;
  }
  return Math.max(MIN_FILL, (battery.level ?? 0) / 100);
}
