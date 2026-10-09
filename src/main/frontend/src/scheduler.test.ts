import { describe, expect, it } from 'vitest';
import { mulberry32, Scheduler, type Candidate } from './scheduler';

const clock: Candidate = { key: 'clock', type: 'clock', weight: 1, durationMs: 1000 };
const snakes: Candidate = { key: 'snakes', type: 'snakes', weight: 1, durationMs: 1000 };

describe('mulberry32', () => {
  it('is repeatable and in [0, 1)', () => {
    const a = mulberry32(42);
    const b = mulberry32(42);
    for (let i = 0; i < 100; i++) {
      const value = a();
      expect(value).toBe(b());
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThan(1);
    }
  });
});

describe('Scheduler', () => {
  it('returns null with no candidates', () => {
    expect(new Scheduler(mulberry32(1)).next([])).toBeNull();
  });

  it('repeats the only candidate', () => {
    const scheduler = new Scheduler(mulberry32(1));
    expect(scheduler.next([clock])).toBe(clock);
    expect(scheduler.next([clock])).toBe(clock);
  });

  it('never shows the same candidate twice in a row when there is a choice', () => {
    const scheduler = new Scheduler(mulberry32(7));
    const heavy = { ...snakes, weight: 100 };
    let previous = scheduler.next([clock, heavy]);
    for (let i = 0; i < 500; i++) {
      const next = scheduler.next([clock, heavy]);
      expect(next?.key).not.toBe(previous?.key);
      previous = next;
    }
  });

  it('respects weights', () => {
    const scheduler = new Scheduler(mulberry32(3));
    const a: Candidate = { key: 'a', type: 'clock', weight: 6, durationMs: 1 };
    const b: Candidate = { key: 'b', type: 'snakes', weight: 1, durationMs: 1 };
    const c: Candidate = { key: 'c', type: 'snakes', weight: 1, durationMs: 1 };
    const counts: Record<string, number> = { a: 0, b: 0, c: 0 };
    for (let i = 0; i < 9000; i++) {
      counts[scheduler.next([a, b, c])!.key]++;
    }
    // With no immediate repeats, `a` settles near 12/7 of `b` (it can't follow itself).
    expect(counts.a).toBeGreaterThan(counts.b * 1.5);
    expect(counts.a).toBeGreaterThan(counts.c * 1.5);
    expect(Math.abs(counts.b - counts.c)).toBeLessThan(400);
  });

  it('skips zero-weight candidates', () => {
    const scheduler = new Scheduler(mulberry32(1));
    for (let i = 0; i < 50; i++) {
      expect(scheduler.next([{ ...clock, weight: 0 }, snakes])).toBe(snakes);
    }
  });

  it('skips sidelined scene types until the sideline expires', () => {
    let now = 0;
    const scheduler = new Scheduler(mulberry32(1), () => now);
    scheduler.sideline('snakes', 1000);
    for (let i = 0; i < 20; i++) {
      expect(scheduler.next([clock, snakes])).toBe(clock);
    }
    now = 1000;
    const picks = new Set<string>();
    for (let i = 0; i < 20; i++) {
      picks.add(scheduler.next([clock, snakes])!.key);
    }
    expect(picks).toContain('snakes');
  });

  it('returns null when everything is sidelined', () => {
    const scheduler = new Scheduler(mulberry32(1), () => 0);
    scheduler.sideline('clock');
    expect(scheduler.next([clock])).toBeNull();
  });
});
