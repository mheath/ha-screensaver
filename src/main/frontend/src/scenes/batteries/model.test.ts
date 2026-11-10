import { describe, expect, it } from 'vitest';
import { batteryLabel, fillFraction, layout } from './model';

describe('layout', () => {
  it('uses one row for up to five', () => {
    expect(layout(1)).toEqual({ shown: 1, rows: 1, more: 0 });
    expect(layout(5)).toEqual({ shown: 5, rows: 1, more: 0 });
  });

  it('wraps to two rows from six to ten', () => {
    expect(layout(6)).toEqual({ shown: 6, rows: 2, more: 0 });
    expect(layout(10)).toEqual({ shown: 10, rows: 2, more: 0 });
  });

  it('draws at most ten and counts the rest', () => {
    expect(layout(12)).toEqual({ shown: 10, rows: 2, more: 2 });
  });
});

describe('batteryLabel', () => {
  it('says DEAD, LOW, or the level', () => {
    expect(batteryLabel({ id: 'a', name: 'A', status: 'dead', level: null })).toBe('DEAD');
    expect(batteryLabel({ id: 'a', name: 'A', status: 'low', level: null })).toBe('LOW');
    expect(batteryLabel({ id: 'a', name: 'A', status: 'low', level: 0 })).toBe('0%');
    expect(batteryLabel({ id: 'a', name: 'A', status: 'low', level: 7 })).toBe('7%');
  });
});

describe('fillFraction', () => {
  it('follows the level but always leaves a visible sliver', () => {
    expect(fillFraction({ id: 'a', name: 'A', status: 'low', level: 9 })).toBeCloseTo(0.09);
    expect(fillFraction({ id: 'a', name: 'A', status: 'low', level: 0 })).toBe(0.03);
    expect(fillFraction({ id: 'a', name: 'A', status: 'low', level: null })).toBe(0.03);
    expect(fillFraction({ id: 'a', name: 'A', status: 'dead', level: null })).toBe(0);
  });
});
