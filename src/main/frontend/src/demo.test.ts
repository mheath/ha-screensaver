import { describe, expect, it } from 'vitest';
import { DEMO_CONFIG, demoState } from './demo';

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
