import { describe, expect, it } from 'vitest';
import { mulberry32 } from '../../scheduler';
import { drift, formatDigital, handAngles, placeWithin } from './layout';

const area = { width: 1280, height: 800 };
const face = { width: 400, height: 200 };

describe('placeWithin', () => {
  it('keeps the face fully on screen', () => {
    const random = mulberry32(5);
    for (let i = 0; i < 500; i++) {
      const p = placeWithin(area, face, random);
      expect(p.x).toBeGreaterThanOrEqual(0);
      expect(p.y).toBeGreaterThanOrEqual(0);
      expect(p.x + face.width).toBeLessThanOrEqual(area.width);
      expect(p.y + face.height).toBeLessThanOrEqual(area.height);
    }
  });

  it('uses the whole available range', () => {
    expect(placeWithin(area, face, () => 0)).toEqual({ x: 0, y: 0 });
    expect(placeWithin(area, face, () => 0.999999)).toEqual({ x: 880, y: 600 });
  });

  it('pins to the corner when the face is bigger than the screen', () => {
    expect(placeWithin({ width: 100, height: 100 }, face, () => 0.5)).toEqual({ x: 0, y: 0 });
  });
});

describe('drift', () => {
  it('moves at most a few pixels and stays on screen', () => {
    const random = mulberry32(9);
    let p = { x: 0, y: 600 };
    for (let i = 0; i < 1000; i++) {
      const next = drift(p, area, face, random);
      expect(Math.abs(next.x - p.x)).toBeLessThanOrEqual(8);
      expect(Math.abs(next.y - p.y)).toBeLessThanOrEqual(8);
      expect(next.x).toBeGreaterThanOrEqual(0);
      expect(next.y + face.height).toBeLessThanOrEqual(area.height);
      p = next;
    }
  });
});

describe('formatDigital', () => {
  it('formats 12-hour time with a suffix', () => {
    expect(formatDigital(new Date(2026, 9, 9, 0, 5), false)).toEqual({ time: '12:05', suffix: 'AM' });
    expect(formatDigital(new Date(2026, 9, 9, 13, 7), false)).toEqual({ time: '1:07', suffix: 'PM' });
    expect(formatDigital(new Date(2026, 9, 9, 12, 0), false)).toEqual({ time: '12:00', suffix: 'PM' });
  });

  it('formats 24-hour time without a suffix', () => {
    expect(formatDigital(new Date(2026, 9, 9, 7, 3), true)).toEqual({ time: '07:03', suffix: '' });
    expect(formatDigital(new Date(2026, 9, 9, 23, 59), true)).toEqual({ time: '23:59', suffix: '' });
  });
});

describe('handAngles', () => {
  it('points the hands correctly', () => {
    expect(handAngles(new Date(2026, 9, 9, 3, 0, 0))).toEqual({ hour: 90, minute: 0, second: 0 });
    const half = handAngles(new Date(2026, 9, 9, 6, 30, 15));
    expect(half.hour).toBeCloseTo(195.125);
    expect(half.minute).toBeCloseTo(181.5);
    expect(half.second).toBe(90);
  });
});
