import type { SceneType } from './types';

export interface Candidate {
  /** Identifies one rotation entry; the same key is never picked twice in a row. */
  key: string;
  type: SceneType;
  weight: number;
  durationMs: number;
}

/** Small seeded random generator, so tests can make "random" picks repeatable. */
export function mulberry32(seed: number): () => number {
  let state = seed | 0;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Weighted random rotation with no immediate repeats and sidelining of broken scenes. */
export class Scheduler {
  private lastKey: string | null = null;
  private readonly sidelinedUntil = new Map<SceneType, number>();

  constructor(
    private readonly random: () => number,
    private readonly now: () => number = Date.now,
  ) {}

  sideline(type: SceneType, forMs = 10 * 60_000): void {
    this.sidelinedUntil.set(type, this.now() + forMs);
  }

  next(candidates: Candidate[]): Candidate | null {
    const time = this.now();
    const active = candidates.filter((c) => c.weight > 0 && (this.sidelinedUntil.get(c.type) ?? 0) <= time);
    const pool = active.length > 1 ? active.filter((c) => c.key !== this.lastKey) : active;
    if (pool.length === 0) {
      return null;
    }
    const total = pool.reduce((sum, c) => sum + c.weight, 0);
    let remaining = this.random() * total;
    let chosen = pool[pool.length - 1];
    for (const candidate of pool) {
      remaining -= candidate.weight;
      if (remaining < 0) {
        chosen = candidate;
        break;
      }
    }
    this.lastKey = chosen.key;
    return chosen;
  }
}
