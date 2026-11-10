import { describe, expect, it } from 'vitest';
import { mulberry32 } from '../../scheduler';
import type { SnakeDef } from '../../types';
import {
  assignColors, formatValue, normalize, pickGuests, shade, SnakeBody, speedFor, tailCatchUpSpeed, targetLength,
  widestValueChars,
} from './model';

const def = (id: string, color: SnakeDef['color'] = null): SnakeDef => ({
  id, label: id, color, unit: null, precision: 1, min: 0, max: 100,
});

describe('value mapping', () => {
  it('normalizes and clamps', () => {
    expect(normalize(50, 0, 100)).toBe(0.5);
    expect(normalize(-5, 0, 100)).toBe(0);
    expect(normalize(500, 0, 100)).toBe(1);
    expect(normalize(5, 10, 10)).toBe(0);
  });

  it('interpolates length and speed', () => {
    expect(targetLength(0, 4, 30)).toBe(4);
    expect(targetLength(1, 4, 30)).toBe(30);
    expect(targetLength(0.5, 4, 30)).toBe(17);
    expect(speedFor(0, 2, 20)).toBe(2);
    expect(speedFor(0.5, 2, 20)).toBe(11);
  });
});

describe('widestValueChars', () => {
  it('fits the integer digits of the larger bound plus the decimals', () => {
    expect(widestValueChars(0, 1000, 1)).toBe(6);
    expect(widestValueChars(0, 100, 0)).toBe(3);
    expect(widestValueChars(0, 9.5, 2)).toBe(4);
  });

  it('makes room for a minus sign when the range goes negative', () => {
    expect(widestValueChars(-40, 40, 1)).toBe(5);
    expect(widestValueChars(-400, 40, 0)).toBe(4);
  });

  it('covers every value formatValue shows within the range', () => {
    for (const value of [0, 0.1, 99.9, 100, 543.21, 999.9, 1000]) {
      expect(formatValue(value, null, 1, true).length).toBeLessThanOrEqual(widestValueChars(0, 1000, 1));
    }
  });
});

describe('formatValue', () => {
  it('shows value and unit', () => {
    expect(formatValue(243.14, 'Mbit/s', 1, true)).toBe('243.1 Mbit/s');
  });

  it('drops trailing zeros', () => {
    expect(formatValue(8.0, 'kW', 1, true)).toBe('8 kW');
    expect(formatValue(8.7, '%', 2, true)).toBe('8.7 %');
  });

  it('shows just the number without a unit', () => {
    expect(formatValue(3, null, 0, true)).toBe('3');
  });

  it('rounds to the precision', () => {
    expect(formatValue(1234.567, 'W', 0, true)).toBe('1235 W');
    expect(formatValue(-0.04, 'W', 1, true)).toBe('0 W');
  });

  it('shows unavailable', () => {
    expect(formatValue(null, 'W', 1, false)).toBe('unavailable');
    expect(formatValue(5, 'W', 1, false)).toBe('unavailable');
  });
});

describe('assignColors', () => {
  it('honors configured colors and fills the rest by preference', () => {
    const colors = assignColors([def('a', 'light-red'), def('b'), def('c')]);
    expect(colors.get('a')).toBe('light-red');
    expect(colors.get('b')).toBe('light-green');
    expect(colors.get('c')).toBe('light-cyan');
  });

  it('gives a later snake a new color when its configured color is taken', () => {
    const colors = assignColors([def('pinned', 'yellow'), def('guest', 'yellow')]);
    expect(colors.get('pinned')).toBe('yellow');
    expect(colors.get('guest')).not.toBe('yellow');
  });

  it('never repeats a color among 15 snakes', () => {
    const snakes = Array.from({ length: 15 }, (_, i) => def(`s${i}`));
    expect(new Set(assignColors(snakes).values()).size).toBe(15);
  });
});

describe('pickGuests', () => {
  it('picks the requested number of distinct guests', () => {
    const guests = ['a', 'b', 'c', 'd'].map((id) => def(id));
    const picked = pickGuests(guests, 2, mulberry32(4));
    expect(picked).toHaveLength(2);
    expect(new Set(picked.map((g) => g.id)).size).toBe(2);
  });

  it('picks every guest when asked for more than there are', () => {
    const guests = ['a', 'b'].map((id) => def(id));
    expect(pickGuests(guests, 4, mulberry32(4)).map((g) => g.id).sort()).toEqual(['a', 'b']);
    expect(pickGuests([], 4, mulberry32(4))).toEqual([]);
  });

  it('varies between appearances', () => {
    const guests = ['a', 'b', 'c', 'd', 'e'].map((id) => def(id));
    const random = mulberry32(11);
    const seen = new Set<string>();
    for (let i = 0; i < 20; i++) {
      seen.add(pickGuests(guests, 1, random)[0].id);
    }
    expect(seen.size).toBeGreaterThan(2);
  });
});

describe('shade', () => {
  it('fades from head to tail', () => {
    expect(shade(0, 20)).toBe('█');
    expect(shade(6, 20)).toBe('▓');
    expect(shade(11, 20)).toBe('▒');
    expect(shade(19, 20)).toBe('░');
  });
});

describe('tailCatchUpSpeed', () => {
  it('shrinks from the longest to the shortest snake in two seconds', () => {
    expect(tailCatchUpSpeed(4, 30)).toBe(13);
    expect(tailCatchUpSpeed(10, 50)).toBe(20);
  });

  it('stays positive when the lengths are equal', () => {
    expect(tailCatchUpSpeed(5, 5)).toBeGreaterThan(0);
  });
});

describe('SnakeBody', () => {
  const SHRINK = tailCatchUpSpeed(4, 30);

  it('takes one step per cell of speed', () => {
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 5, 100, 80, 20, mulberry32(1), SHRINK);
    expect(body.cells).toHaveLength(6);
  });

  it('grows to and trims to the target length', () => {
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 20, 8, 80, 20, mulberry32(1), SHRINK);
    expect(body.cells).toHaveLength(8);
    body.advance(1, 20, 3, 80, 20, mulberry32(1), SHRINK);
    expect(body.cells).toHaveLength(3);
  });

  it('takes two seconds to pull its tail in from the longest to the shortest', () => {
    const random = mulberry32(1);
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 30, 30, 80, 20, random, SHRINK);
    expect(body.cells).toHaveLength(30);
    const lengths: number[] = [];
    for (let frame = 0; frame < 150; frame++) {
      body.advance(1 / 60, 2, 4, 80, 20, random, SHRINK);
      lengths.push(body.cells.length);
    }
    expect(lengths.every((length, i) => i === 0 || length <= lengths[i - 1])).toBe(true);
    expect(lengths[59]).toBeGreaterThan(15); // after 1 s: about halfway
    expect(lengths[59]).toBeLessThan(19);
    expect(lengths[114]).toBeGreaterThan(4); // after 1.9 s: not quite there
    expect(lengths[124]).toBe(4); // after 2.1 s: done
  });

  it('keeps its head moving while the tail catches up', () => {
    const random = mulberry32(1);
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 30, 30, 80, 20, random, SHRINK);
    const head = body.cells[0];
    body.advance(0.5, 10, 4, 80, 20, random, SHRINK);
    expect(body.cells[0]).not.toEqual(head);
  });

  it('starts a fresh shrink from where the tail is', () => {
    const random = mulberry32(1);
    const body = new SnakeBody({ x: 10, y: 10 }, 0);
    body.advance(1, 30, 30, 80, 20, random, SHRINK);
    body.advance(1, 0.001, 30, 80, 20, random, SHRINK);
    body.advance(1 / 60, 0.001, 4, 80, 20, random, SHRINK);
    expect(body.cells.length).toBeGreaterThan(28);
  });

  it('stays in bounds and never reverses', () => {
    const random = mulberry32(21);
    const body = new SnakeBody({ x: 0, y: 0 }, 2);
    for (let i = 0; i < 5000; i++) {
      const before = body.dir;
      body.step(10, 6, random);
      const head = body.cells[0];
      expect(head.x).toBeGreaterThanOrEqual(0);
      expect(head.x).toBeLessThan(10);
      expect(head.y).toBeGreaterThanOrEqual(0);
      expect(head.y).toBeLessThan(6);
      expect(body.dir).not.toBe((before + 2) % 4);
    }
  });
});
