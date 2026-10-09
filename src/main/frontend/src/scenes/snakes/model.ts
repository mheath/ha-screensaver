import { SNAKE_COLOR_PREFERENCE, type PaletteName } from '../../palette';
import type { SnakeDef } from '../../types';

/** 0 right, 1 down, 2 left, 3 up. */
export type Dir = 0 | 1 | 2 | 3;

export interface Cell {
  x: number;
  y: number;
}

const DX = [1, 0, -1, 0];
const DY = [0, 1, 0, -1];

/** Chance per step that a snake turns even though it could go straight. */
const TURN_CHANCE = 0.12;

export function normalize(value: number, min: number, max: number): number {
  if (max <= min) {
    return 0;
  }
  return Math.min(1, Math.max(0, (value - min) / (max - min)));
}

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

export function targetLength(t: number, minLength: number, maxLength: number): number {
  return Math.max(1, Math.round(lerp(minLength, maxLength, t)));
}

export function speedFor(t: number, minSpeed: number, maxSpeed: number): number {
  return lerp(minSpeed, maxSpeed, t);
}

/** Legend value: at most `precision` decimals, trailing zeros dropped, unit if known. */
export function formatValue(value: number | null, unit: string | null, precision: number, available: boolean): string {
  if (!available || value === null) {
    return 'unavailable';
  }
  const rounded = Number(value.toFixed(precision));
  const text = Object.is(rounded, -0) ? '0' : String(rounded);
  return unit ? `${text} ${unit}` : text;
}

/**
 * Gives every snake a distinct color. Pass pinned snakes first: configured colors
 * are honored in order, and a later snake whose color is taken gets an unused one.
 */
export function assignColors(snakes: SnakeDef[]): Map<string, PaletteName> {
  const result = new Map<string, PaletteName>();
  const used = new Set<PaletteName>();
  for (const snake of snakes) {
    if (snake.color && snake.color !== 'black' && !used.has(snake.color)) {
      result.set(snake.id, snake.color);
      used.add(snake.color);
    }
  }
  for (const snake of snakes) {
    if (!result.has(snake.id)) {
      const color = SNAKE_COLOR_PREFERENCE.find((c) => !used.has(c));
      if (color) {
        result.set(snake.id, color);
        used.add(color);
      }
    }
  }
  return result;
}

export function pickGuests(guests: SnakeDef[], count: number, random: () => number): SnakeDef[] {
  const shuffled = [...guests];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled.slice(0, count);
}

export type ShadeGlyph = '█' | '▓' | '▒' | '░';

/** Glyph for body segment `index`: full block at the head, fading through ▓ ▒ ░ to the tail. */
export function shade(index: number, length: number): ShadeGlyph {
  const f = index / Math.max(1, length);
  if (f < 0.25) return '█';
  if (f < 0.5) return '▓';
  if (f < 0.75) return '▒';
  return '░';
}

/** A snake's cells (head first) on a character grid. */
export class SnakeBody {
  readonly cells: Cell[];
  dir: Dir;
  private progress = 0;

  constructor(start: Cell, dir: Dir) {
    this.cells = [start];
    this.dir = dir;
  }

  /** Moves `seconds × speed` cells, growing toward or trimming to `targetLen`. */
  advance(seconds: number, speed: number, targetLen: number, cols: number, rows: number, random: () => number): void {
    this.progress += seconds * speed;
    while (this.progress >= 1) {
      this.progress -= 1;
      this.step(cols, rows, random);
      if (this.cells.length > targetLen) {
        this.cells.pop();
      }
    }
    while (this.cells.length > targetLen) {
      this.cells.pop();
    }
  }

  step(cols: number, rows: number, random: () => number): void {
    const left = ((this.dir + 3) % 4) as Dir;
    const right = ((this.dir + 1) % 4) as Dir;
    const turns: Dir[] = random() < 0.5 ? [left, right] : [right, left];
    const options: Dir[] = random() < TURN_CHANCE ? [...turns, this.dir] : [this.dir, ...turns];
    const head = this.cells[0];
    for (const dir of options) {
      const x = head.x + DX[dir];
      const y = head.y + DY[dir];
      if (x >= 0 && x < cols && y >= 0 && y < rows) {
        this.dir = dir;
        this.cells.unshift({ x, y });
        return;
      }
    }
    // Only reachable on a one-cell-wide grid: stay put this step.
  }
}
